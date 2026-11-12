package com.omni.payment.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.response.AlipayTradePagePayResponse;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.payment.client.OrderClient;
import com.omni.payment.config.AlipayProperties;
import com.omni.payment.config.PaymentSentinelConfig;
import com.omni.payment.dto.OrderInfoResponse;
import com.omni.payment.dto.PagePayResponse;
import com.omni.payment.dto.PaymentSyncDecisionResponse;
import com.omni.payment.dto.PaymentStatusResponse;
import com.omni.payment.dto.QrPayResponse;
import com.omni.payment.entity.Payment;
import com.omni.payment.mapper.PaymentMapper;
import com.omni.payment.util.AlipayLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 支付宝支付核心服务。
 */
@Service
public class AlipayService {

    private static final Logger log = LoggerFactory.getLogger(AlipayService.class);

    private static final Integer ORDER_STATUS_PENDING = 1;
    private static final Integer ORDER_STATUS_PAID = 2;
    private static final String PAYMENT_METHOD = "ALIPAY";
    private static final String TRADE_SUCCESS = "TRADE_SUCCESS";
    private static final String TRADE_FINISHED = "TRADE_FINISHED";
    private static final String TRADE_NOT_EXIST = "ACQ.TRADE_NOT_EXIST";
    private static final int QRCODE_CREATE_MAX_ATTEMPTS = 2;
    private static final int QRCODE_QUERY_MAX_ATTEMPTS = 3;
    private static final long QRCODE_QUERY_RETRY_DELAY_MS = 300L;

    private final AlipayProperties alipayProperties;
    private final OrderClient orderClient;
    private final PaymentMapper paymentMapper;
    private final PaymentConfirmationService paymentConfirmationService;
    private final String internalApiToken;
    private final Supplier<AlipayClient> alipayClientFactory;

    @Autowired
    public AlipayService(AlipayProperties alipayProperties,
                          OrderClient orderClient,
                          PaymentMapper paymentMapper,
                          PaymentConfirmationService paymentConfirmationService,
                          @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this(alipayProperties, orderClient, paymentMapper, internalApiToken, null, paymentConfirmationService);
    }

    public AlipayService(AlipayProperties alipayProperties,
                          OrderClient orderClient,
                          PaymentMapper paymentMapper,
                          String internalApiToken,
                          Supplier<AlipayClient> alipayClientFactory) {
        this(alipayProperties, orderClient, paymentMapper, internalApiToken, alipayClientFactory,
                new PaymentConfirmationService(orderClient, paymentMapper, internalApiToken));
    }

    public AlipayService(AlipayProperties alipayProperties,
                          OrderClient orderClient,
                          PaymentMapper paymentMapper,
                          String internalApiToken,
                          Supplier<AlipayClient> alipayClientFactory,
                          PaymentConfirmationService paymentConfirmationService) {
        this.alipayProperties = alipayProperties;
        this.orderClient = orderClient;
        this.paymentMapper = paymentMapper;
        this.paymentConfirmationService = paymentConfirmationService;
        this.internalApiToken = internalApiToken;
        this.alipayClientFactory = alipayClientFactory;
    }

    public PagePayResponse createPagePay(Long orderId) {
        validateConfig();
        OrderInfoResponse order = getOrderOrThrow(orderId);
        if (ORDER_STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付");
        }
        if (!ORDER_STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前订单状态不允许支付");
        }

        String orderNo = requireText(order.getOrderNo(), "订单号不能为空");
        BigDecimal amount = normalizeAmount(order.getAmount());
        Payment payment = getLatestPaymentByOutTradeNo(orderNo);
        if (payment != null) {
            if (PaymentService.STATUS_SUCCESS == payment.getStatus()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付");
            }
            if (PaymentService.STATUS_PENDING != payment.getStatus()) {
                payment = null;
            }
        }
        AlipayTradePagePayRequest request = new AlipayTradePagePayRequest();
        request.setReturnUrl(appendOrderId(alipayProperties.getReturnUrl(), orderId));
        if (StringUtils.hasText(alipayProperties.getNotifyUrl())) {
            request.setNotifyUrl(alipayProperties.getNotifyUrl());
        }
        Map<String, String> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", orderNo);
        bizContent.put("total_amount", amount.toPlainString());
        bizContent.put("subject", "万象票务订单 " + orderNo);
        bizContent.put("product_code", "FAST_INSTANT_TRADE_PAY");
        request.setBizContent(buildJson(bizContent));

        if (payment == null) {
            payment = createPendingPayment(order);
        }

        try {
            AlipayTradePagePayResponse alipayResponse = callAlipayChannel(() -> createClient().pageExecute(request));
            if (alipayResponse == null || !alipayResponse.isSuccess() || !StringUtils.hasText(alipayResponse.getBody())) {
                markPaymentFailed(payment, "支付宝支付表单响应为空或失败");
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "生成支付宝支付表单失败");
            }

            PagePayResponse response = new PagePayResponse();
            response.setOrderId(orderId);
            response.setOrderNo(orderNo);
            response.setPayForm(alipayResponse.getBody());
            return response;
        } catch (BusinessException e) {
            throw e;
        } catch (AlipayApiException | RuntimeException e) {
            markPaymentFailed(payment, "生成支付宝支付表单异常");
            log.error("生成支付宝支付表单失败: orderId={}, message={}", orderId, AlipayLogSanitizer.safeMessage(e));
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "生成支付宝支付表单失败");
        }
    }

    public QrPayResponse createQrPay(Long orderId) {
        validateConfig();
        OrderInfoResponse order = getOrderOrThrow(orderId);
        if (ORDER_STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付");
        }
        if (!ORDER_STATUS_PENDING.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前订单状态不允许支付");
        }

        String orderNo = requireText(order.getOrderNo(), "订单号不能为空");
        BigDecimal amount = normalizeAmount(order.getAmount());
        Payment payment = getLatestPaymentByOutTradeNo(orderNo);
        if (payment != null) {
            if (PaymentService.STATUS_SUCCESS == payment.getStatus()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "订单已支付");
            }
            if (PaymentService.STATUS_PENDING != payment.getStatus()) {
                payment = null;
            }
        }
        if (payment == null) {
            payment = createPendingPayment(order);
        }

        String subject = "万象票务订单 " + orderNo;
        AlipayTradePrecreateRequest request = new AlipayTradePrecreateRequest();
        if (StringUtils.hasText(alipayProperties.getNotifyUrl())) {
            request.setNotifyUrl(alipayProperties.getNotifyUrl());
        }
        Map<String, String> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", orderNo);
        bizContent.put("total_amount", amount.toPlainString());
        bizContent.put("subject", subject);
        bizContent.put("timeout_express", "15m");
        request.setBizContent(buildJson(bizContent));

        try {
            AlipayClient client = createClient();
            AlipayTradePrecreateResponse alipayResponse = executePrecreateWithRetry(client, request, orderId);
            if (alipayResponse == null || !alipayResponse.isSuccess() || !StringUtils.hasText(alipayResponse.getQrCode())) {
                markPaymentFailed(payment, "支付宝二维码响应为空或失败");
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "生成支付宝支付二维码失败");
            }
            waitUntilPrecreatedTradeQueryable(client, orderNo);
            QrPayResponse response = new QrPayResponse();
            response.setOrderId(orderId);
            response.setOrderNo(orderNo);
            response.setAmount(amount);
            response.setSubject(subject);
            response.setQrCode(alipayResponse.getQrCode());
            return response;
        } catch (BusinessException e) {
            throw e;
        } catch (AlipayApiException | RuntimeException e) {
            markPaymentFailed(payment, "生成支付宝支付二维码异常");
            log.error("生成支付宝支付二维码失败: orderId={}, message={}", orderId, AlipayLogSanitizer.safeMessage(e));
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "生成支付宝支付二维码失败");
        }
    }

    private AlipayTradePrecreateResponse executePrecreateWithRetry(AlipayClient client, AlipayTradePrecreateRequest request, Long orderId) throws AlipayApiException {
        RuntimeException lastRuntimeException = null;
        for (int attempt = 1; attempt <= QRCODE_CREATE_MAX_ATTEMPTS; attempt++) {
            try {
                return callAlipayChannel(() -> client.execute(request));
            } catch (RuntimeException e) {
                lastRuntimeException = e;
                if (attempt >= QRCODE_CREATE_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("生成支付宝二维码临时失败，准备重试: orderId={}, attempt={}, message={}", orderId, attempt, AlipayLogSanitizer.safeMessage(e));
            }
        }
        throw lastRuntimeException;
    }

    private void waitUntilPrecreatedTradeQueryable(AlipayClient client, String orderNo) {
        for (int attempt = 1; attempt <= QRCODE_QUERY_MAX_ATTEMPTS; attempt++) {
            AlipayTradeQueryRequest queryRequest = new AlipayTradeQueryRequest();
            Map<String, String> bizContent = new LinkedHashMap<>();
            bizContent.put("out_trade_no", orderNo);
            queryRequest.setBizContent(buildJson(bizContent));
            AlipayTradeQueryResponse queryResponse;
            try {
                queryResponse = callAlipayChannel(() -> client.execute(queryRequest));
            } catch (RuntimeException | AlipayApiException e) {
                log.warn("支付宝二维码已生成，但交易可查询确认失败: outTradeNo={}, attempt={}, message={}", orderNo, attempt, AlipayLogSanitizer.safeMessage(e));
                return;
            }
            if (queryResponse != null && queryResponse.isSuccess()) {
                return;
            }
            if (queryResponse == null || !TRADE_NOT_EXIST.equals(queryResponse.getSubCode())) {
                return;
            }
            if (attempt < QRCODE_QUERY_MAX_ATTEMPTS) {
                sleepBeforeQrQueryRetry(orderNo, attempt);
            }
        }
    }

    private void sleepBeforeQrQueryRetry(String orderNo, int attempt) {
        try {
            Thread.sleep(QRCODE_QUERY_RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待支付宝二维码交易可查询时被中断: outTradeNo={}, attempt={}", orderNo, attempt);
        }
    }

    public boolean handleNotify(Map<String, String> params) {
        validateConfig();
        try {
            boolean valid = AlipaySignature.rsaCheckV1(
                    params,
                    alipayProperties.getAlipayPublicKey(),
                    charset(),
                    signType()
            );
            if (!valid) {
                log.warn("支付宝通知验签失败: outTradeNo={}", params.get("out_trade_no"));
                return false;
            }
        } catch (AlipayApiException e) {
            log.warn("支付宝通知验签异常: outTradeNo={}, message={}", params.get("out_trade_no"), AlipayLogSanitizer.safeMessage(e));
            return false;
        }

        if (!alipayProperties.getAppId().equals(params.get("app_id"))) {
            log.warn("支付宝通知 app_id 不匹配: outTradeNo={}, appId={}", params.get("out_trade_no"), params.get("app_id"));
            return false;
        }

        String tradeStatus = params.get("trade_status");
        if (!isPaidTradeStatus(tradeStatus)) {
            return true;
        }

        String outTradeNo = params.get("out_trade_no");
        String totalAmount = params.get("total_amount");
        String tradeNo = params.get("trade_no");
        String buyerId = params.get("buyer_id");
        Payment payment = getLatestPaymentByOutTradeNo(outTradeNo);
        if (payment == null) {
            log.warn("支付宝通知未找到本地支付流水: outTradeNo={}", outTradeNo);
            return false;
        }

        if (PaymentService.STATUS_SUCCESS == payment.getStatus()) {
            if (!StringUtils.hasText(tradeNo) || !tradeNo.equals(payment.getTradeNo())) {
                log.warn("支付宝通知流水已成功但交易号不一致: outTradeNo={}, oldTradeNo={}, newTradeNo={}", outTradeNo, payment.getTradeNo(), tradeNo);
                return false;
            }
            try {
                completePayment(payment, tradeNo, buyerId, toMapString(params), toMapString(params));
                return true;
            } catch (RuntimeException e) {
                log.warn("支付宝通知幂等确认订单支付失败: outTradeNo={}, orderId={}", outTradeNo, payment.getOrderId(), e);
                return false;
            }
        }

        OrderInfoResponse order;
        try {
            order = getOrderOrThrow(payment.getOrderId());
        } catch (RuntimeException e) {
            log.warn("支付宝通知加载订单失败: outTradeNo={}, orderId={}", outTradeNo, payment.getOrderId(), e);
            return false;
        }
        if (!outTradeNo.equals(order.getOrderNo())) {
            log.warn("支付宝通知订单号不匹配: outTradeNo={}, orderNo={}, orderId={}", outTradeNo, order.getOrderNo(), order.getId());
            return false;
        }
        if (!amountEquals(order.getAmount(), totalAmount)) {
            log.warn("支付宝通知金额校验失败: outTradeNo={}, totalAmount={}", outTradeNo, totalAmount);
            return false;
        }

        try {
            completePayment(payment, tradeNo, buyerId, toMapString(params), toMapString(params));
            return true;
        } catch (RuntimeException e) {
            log.warn("支付宝通知处理支付成功失败: outTradeNo={}, orderId={}", outTradeNo, payment.getOrderId(), e);
            return false;
        }
    }

    public PaymentStatusResponse syncByOrderId(Long orderId) {
        validateConfig();
        PaymentSyncLatencyTrace latencyTrace = new PaymentSyncLatencyTrace(orderId);
        try {
            OrderInfoResponse order = latencyTrace.measureOrderLoad(() -> getOrderOrThrow(orderId));
            latencyTrace.setOrder(order);
            Payment payment = latencyTrace.measurePaymentLoad(() -> getLatestPaymentByOutTradeNo(order.getOrderNo()));
            if (ORDER_STATUS_PAID.equals(order.getStatus())) {
                if (payment == null || PaymentService.STATUS_SUCCESS != payment.getStatus()) {
                    PaymentStatusResponse compensated = queryAndCompletePayment(order, payment, latencyTrace);
                    if (PaymentService.STATUS_SUCCESS == compensated.getPaymentStatus()) {
                        return compensated;
                    }
                    latencyTrace.setOutcome("ORDER_PAID_PAYMENT_PENDING");
                    return buildStatusResponse(
                            order,
                            payment,
                            payment != null ? payment.getStatus() : PaymentService.STATUS_PENDING,
                            "订单已支付，支付流水待补偿",
                            false
                    );
                }
                latencyTrace.setOutcome("ALREADY_CONFIRMED");
                return buildStatusResponse(order, payment, PaymentService.STATUS_SUCCESS, "支付成功", true);
            }

            return queryAndCompletePayment(order, payment, latencyTrace);
        } finally {
            latencyTrace.log();
        }
    }

    public PaymentSyncDecisionResponse syncDecisionForCancel(Long orderId) {
        PaymentStatusResponse status = syncByOrderId(orderId);
        PaymentSyncDecisionResponse response = new PaymentSyncDecisionResponse();
        response.setOrderId(status.getOrderId());
        response.setOrderNo(status.getOrderNo());
        response.setOrderStatus(status.getOrderStatus());
        response.setPaymentStatus(status.getPaymentStatus());
        response.setTradeNo(status.getTradeNo());
        response.setMessage(status.getMessage());
        boolean paid = PaymentService.STATUS_SUCCESS == status.getPaymentStatus()
                || ORDER_STATUS_PAID.equals(status.getOrderStatus());
        response.setPaid(paid);
        response.setSafeToCancel(!paid && Boolean.TRUE.equals(status.getStatusConfirmed()));
        return response;
    }

    private PaymentStatusResponse queryAndCompletePayment(OrderInfoResponse order, Payment payment, PaymentSyncLatencyTrace latencyTrace) {
        AlipayTradeQueryRequest request = new AlipayTradeQueryRequest();
        Map<String, String> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", order.getOrderNo());
        request.setBizContent(buildJson(bizContent));

        try {
            AlipayTradeQueryResponse response = latencyTrace.measureAlipayQuery(() -> callAlipayChannel(() -> createClient().execute(request)));
            if (response.isSuccess() && isPaidTradeStatus(response.getTradeStatus())) {
                if (!amountEquals(order.getAmount(), response.getTotalAmount())) {
                    latencyTrace.setOutcome("AMOUNT_MISMATCH");
                    throw new BusinessException(ResultCode.BAD_REQUEST, "支付宝支付金额与订单金额不一致");
                }
                Payment paymentToConfirm = payment;
                if (paymentToConfirm == null) {
                    paymentToConfirm = createPendingPayment(order);
                }
                Payment finalPayment = paymentToConfirm;
                latencyTrace.measureConfirmPayment(() -> completePayment(finalPayment, response.getTradeNo(), response.getBuyerUserId(), response.getBody(), response.getBody()));
                OrderInfoResponse paidOrder = latencyTrace.measureOrderReload(() -> getOrderOrThrow(order.getId()));
                latencyTrace.setOutcome("CONFIRMED");
                return buildStatusResponse(paidOrder, finalPayment, PaymentService.STATUS_SUCCESS, "支付成功", true);
            }
            if (!response.isSuccess() && TRADE_NOT_EXIST.equals(response.getSubCode())) {
                latencyTrace.setOutcome("TRADE_NOT_EXIST");
                return buildStatusResponse(order, payment, payment != null ? payment.getStatus() : PaymentService.STATUS_PENDING, "支付宝未查询到支付交易", true);
            }
            latencyTrace.setOutcome("QUERY_PENDING");
            return buildStatusResponse(order, payment, payment != null ? payment.getStatus() : PaymentService.STATUS_PENDING, "支付结果确认中", false);
        } catch (AlipayApiException e) {
            latencyTrace.setOutcome("ALIPAY_QUERY_FAILED");
            log.warn("查询支付宝支付结果失败: orderId={}, outTradeNo={}, message={}", order.getId(), order.getOrderNo(), AlipayLogSanitizer.safeMessage(e));
            return buildStatusResponse(order, payment, payment != null ? payment.getStatus() : PaymentService.STATUS_PENDING, "支付结果确认中", false);
        } catch (BusinessException e) {
            if ("支付渠道暂不可用，请稍后重试".equals(e.getMessage())) {
                latencyTrace.setOutcome("CHANNEL_UNAVAILABLE");
                return buildStatusResponse(order, payment, payment != null ? payment.getStatus() : PaymentService.STATUS_PENDING, "支付结果确认中", false);
            }
            throw e;
        } catch (RuntimeException e) {
            // 支付宝 SDK 内部会把 AlipayApiException 包成 RuntimeException 上抛（消息含完整签名参数），
            // 与 AlipayApiException 同等降级为"确认中"；其余运行时异常保持原样抛出。
            if (!isAlipayOriginFailure(e)) {
                throw e;
            }
            latencyTrace.setOutcome("ALIPAY_QUERY_FAILED");
            log.warn("查询支付宝支付结果异常: orderId={}, outTradeNo={}, message={}", order.getId(), order.getOrderNo(), AlipayLogSanitizer.safeMessage(e));
            return buildStatusResponse(order, payment, payment != null ? payment.getStatus() : PaymentService.STATUS_PENDING, "支付结果确认中", false);
        }
    }

    private boolean isAlipayOriginFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current instanceof AlipayApiException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Payment createPendingPayment(OrderInfoResponse order) {
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setPaymentNo(generatePaymentNo());
        payment.setPaymentMethod(PAYMENT_METHOD);
        payment.setOutTradeNo(order.getOrderNo());
        payment.setAmount(normalizeAmount(order.getAmount()));
        payment.setStatus(PaymentService.STATUS_PENDING);
        try {
            paymentMapper.insert(payment);
            return payment;
        } catch (DuplicateKeyException e) {
            Payment existing = getLatestPaymentByOutTradeNo(order.getOrderNo());
            if (existing != null && PaymentService.STATUS_PENDING == existing.getStatus()) {
                log.warn("并发创建支付流水命中唯一约束，复用已有待支付流水: outTradeNo={}, paymentId={}",
                        order.getOrderNo(), existing.getId());
                return existing;
            }
            throw e;
        }
    }

    private void markPaymentFailed(Payment payment, String callbackData) {
        payment.setStatus(PaymentService.STATUS_FAILED);
        payment.setCallbackData(callbackData);
        paymentMapper.updateById(payment);
    }

    private void completePayment(Payment payment, String tradeNo, String buyerId, String rawNotify, String callbackData) {
        paymentConfirmationService.confirmPayment(payment, tradeNo, buyerId, rawNotify, callbackData);
    }

    private OrderInfoResponse getOrderOrThrow(Long orderId) {
        if (orderId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单ID不能为空");
        }
        String token = requireInternalApiToken();
        Result<OrderInfoResponse> result;
        try {
            result = callOrderClient(() -> orderClient.getOrder(orderId, token));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("订单服务调用失败: orderId={}", orderId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "订单服务无响应");
        }
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return result.getData();
    }

    private <T> T callOrderClient(Supplier<T> call) {
        Entry entry = null;
        try {
            entry = SphU.entry(PaymentSentinelConfig.ORDER_CLIENT);
            return call.get();
        } catch (BlockException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "订单服务暂不可用，请稍后重试");
        } catch (RuntimeException e) {
            Tracer.traceEntry(e, entry);
            throw e;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private <T> T callAlipayChannel(AlipayCall<T> call) throws AlipayApiException {
        Entry entry = null;
        try {
            entry = SphU.entry(PaymentSentinelConfig.ALIPAY_CHANNEL);
            return call.execute();
        } catch (BlockException e) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "支付渠道暂不可用，请稍后重试");
        } catch (AlipayApiException | RuntimeException e) {
            Tracer.traceEntry(e, entry);
            throw e;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private static final class PaymentSyncLatencyTrace {
        private final Long requestedOrderId;
        private final long startedAtNanos = System.nanoTime();
        private Long orderId;
        private String orderNo;
        private String outcome = "FAILED";
        private long orderLoadNanos;
        private long paymentLoadNanos;
        private long alipayQueryNanos;
        private long confirmPaymentNanos;
        private long orderReloadNanos;

        private PaymentSyncLatencyTrace(Long requestedOrderId) {
            this.requestedOrderId = requestedOrderId;
        }

        private void setOrder(OrderInfoResponse order) {
            if (order == null) {
                return;
            }
            this.orderId = order.getId();
            this.orderNo = order.getOrderNo();
        }

        private void setOutcome(String outcome) {
            this.outcome = outcome;
        }

        private OrderInfoResponse measureOrderLoad(Supplier<OrderInfoResponse> action) {
            long startedAt = System.nanoTime();
            try {
                return action.get();
            } finally {
                orderLoadNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private Payment measurePaymentLoad(Supplier<Payment> action) {
            long startedAt = System.nanoTime();
            try {
                return action.get();
            } finally {
                paymentLoadNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private <T> T measureAlipayQuery(AlipayCall<T> action) throws AlipayApiException {
            long startedAt = System.nanoTime();
            try {
                return action.execute();
            } finally {
                alipayQueryNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private void measureConfirmPayment(Runnable action) {
            long startedAt = System.nanoTime();
            try {
                action.run();
            } finally {
                confirmPaymentNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private OrderInfoResponse measureOrderReload(Supplier<OrderInfoResponse> action) {
            long startedAt = System.nanoTime();
            try {
                return action.get();
            } finally {
                orderReloadNanos += Math.max(0L, System.nanoTime() - startedAt);
            }
        }

        private void log() {
            long totalNanos = Math.max(0L, System.nanoTime() - startedAtNanos);
            AlipayService.log.info(
                    "支付同步链路耗时: orderId={} orderNo={} outcome={} orderLoadMs={} paymentLoadMs={} alipayQueryMs={} confirmPaymentMs={} orderReloadMs={} totalMs={}",
                    orderId != null ? orderId : requestedOrderId,
                    orderNo,
                    outcome,
                    millis(orderLoadNanos),
                    millis(paymentLoadNanos),
                    millis(alipayQueryNanos),
                    millis(confirmPaymentNanos),
                    millis(orderReloadNanos),
                    millis(totalNanos)
            );
        }

        private long millis(long nanos) {
            return nanos / 1_000_000L;
        }
    }

    private interface AlipayCall<T> {
        T execute() throws AlipayApiException;
    }

    private String requireInternalApiToken() {
        if (!StringUtils.hasText(internalApiToken)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "内部接口令牌未配置");
        }
        return internalApiToken;
    }

    private Payment getLatestPaymentByOutTradeNo(String outTradeNo) {
        if (!StringUtils.hasText(outTradeNo)) {
            return null;
        }
        LambdaQueryWrapper<Payment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Payment::getOutTradeNo, outTradeNo)
                .orderByDesc(Payment::getCreateTime)
                .orderByDesc(Payment::getId)
                .last("LIMIT 1");
        return paymentMapper.selectOne(wrapper);
    }

    private PaymentStatusResponse buildStatusResponse(OrderInfoResponse order, Payment payment, Integer paymentStatus, String message, boolean statusConfirmed) {
        PaymentStatusResponse response = new PaymentStatusResponse();
        response.setOrderId(order.getId());
        response.setOrderNo(order.getOrderNo());
        response.setOrderStatus(order.getStatus());
        response.setPaymentStatus(paymentStatus);
        response.setTradeNo(payment != null ? payment.getTradeNo() : null);
        response.setMessage(message);
        response.setStatusConfirmed(statusConfirmed);
        return response;
    }

    private AlipayClient createClient() {
        if (alipayClientFactory != null) {
            return alipayClientFactory.get();
        }
        return new DefaultAlipayClient(
                alipayProperties.getGatewayUrl(),
                alipayProperties.getAppId(),
                alipayProperties.getMerchantPrivateKey(),
                format(),
                charset(),
                alipayProperties.getAlipayPublicKey(),
                signType()
        );
    }

    private void validateConfig() {
        requireText(alipayProperties.getAppId(), "支付宝 appId 未配置");
        requireText(alipayProperties.getMerchantPrivateKey(), "支付宝商户私钥未配置");
        requireText(alipayProperties.getAlipayPublicKey(), "支付宝公钥未配置");
        requireText(alipayProperties.getGatewayUrl(), "支付宝网关未配置");
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value) || isUnresolvedPlaceholder(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return value;
    }

    private boolean isUnresolvedPlaceholder(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单金额不能为空");
        }
        if (amount.scale() > 2) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单金额最多保留两位小数");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean amountEquals(BigDecimal expected, String actual) {
        if (expected == null || !StringUtils.hasText(actual)) {
            return false;
        }
        try {
            BigDecimal actualAmount = new BigDecimal(actual);
            if (actualAmount.scale() > 2) {
                return false;
            }
            return normalizeAmount(expected).compareTo(actualAmount.setScale(2, RoundingMode.HALF_UP)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isPaidTradeStatus(String tradeStatus) {
        return TRADE_SUCCESS.equals(tradeStatus) || TRADE_FINISHED.equals(tradeStatus);
    }

    private String appendOrderId(String returnUrl, Long orderId) {
        String baseUrl = requireText(returnUrl, "支付宝 returnUrl 未配置");
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "orderId=" + orderId;
    }

    private String generatePaymentNo() {
        return "ALI" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private String format() {
        return StringUtils.hasText(alipayProperties.getFormat()) ? alipayProperties.getFormat() : "json";
    }

    private String charset() {
        return StringUtils.hasText(alipayProperties.getCharset()) ? alipayProperties.getCharset() : "utf-8";
    }

    private String signType() {
        return StringUtils.hasText(alipayProperties.getSignType()) ? alipayProperties.getSignType() : "RSA2";
    }

    private String buildJson(Map<String, String> values) {
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append('"').append(escapeJson(entry.getKey())).append("\":\"")
                    .append(escapeJson(entry.getValue())).append('"');
            first = false;
        }
        builder.append('}');
        return builder.toString();
    }

    private String toMapString(Map<String, String> values) {
        return values == null ? "{}" : buildJson(values);
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }
}
