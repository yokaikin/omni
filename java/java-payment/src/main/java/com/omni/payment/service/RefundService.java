package com.omni.payment.service;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.Tracer;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.omni.common.dto.InternalAuthContextResponse;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.payment.client.OrderClient;
import com.omni.payment.client.TicketRefundReviewInternalClient;
import com.omni.payment.client.UserInternalClient;
import com.omni.payment.config.AlipayProperties;
import com.omni.payment.config.PaymentSentinelConfig;
import com.omni.payment.dto.DirectRefundResponse;
import com.omni.payment.dto.InternalUserRefResponse;
import com.omni.payment.dto.MarkPartialRefundedRequest;
import com.omni.payment.dto.OrderInfoResponse;
import com.omni.payment.dto.OrderRefundOptionsResponse;
import com.omni.payment.dto.RefundRequestVO;
import com.omni.payment.dto.TicketRefundReviewPermissionResponse;
import com.omni.payment.entity.Payment;
import com.omni.payment.entity.RefundRequest;
import com.omni.payment.mapper.PaymentMapper;
import com.omni.payment.mapper.RefundRequestMapper;
import com.omni.payment.mq.NotificationMqProducer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private static final Integer ORDER_STATUS_PAID = 2;
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Integer REFUND_STATUS_PENDING = 0;
    private static final Integer REFUND_STATUS_REFUNDED = 1;
    private static final Integer REFUND_STATUS_REJECTED = 2;
    private static final Integer REFUND_STATUS_FAILED = 3;
    private static final Integer REFUND_STATUS_PROCESSING = 4;
    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_PLATFORM_SUPER_ADMIN = "platform_super_admin";
    private static final String ROLE_ORGANIZER = "organizer";
    private static final String PERMISSION_REFUND_REVIEW = "refund.review";
    private static final String SCOPE_PLATFORM = "platform";
    private static final String DIRECT_REFUND_STATUS_SUCCESS = "SUCCESS";
    private static final String DIRECT_REFUND_STATUS_FAILED = "FAILED";
    private static final String DIRECT_REFUND_STATUS_UNKNOWN = "UNKNOWN";
    private static final String DIRECT_REFUND_STATUS_COMPENSATION_REQUIRED = "COMPENSATION_REQUIRED";
    private static final String ALIPAY_UNCONFIRMED_REFUND_MESSAGE =
            "支付流水未经过支付宝真实回执确认，不能发起渠道退款，请选择真实支付订单重新申请退款";

    private final AlipayProperties alipayProperties;
    private final OrderClient orderClient;
    private final RefundRequestMapper refundRequestMapper;
    private final PaymentMapper paymentMapper;
    private final UserInternalClient userInternalClient;
    private final TicketRefundReviewInternalClient ticketRefundReviewInternalClient;
    private final NotificationMqProducer notificationProducer;
    private final String internalApiToken;
    private final Supplier<AlipayClient> alipayClientFactory;

    @Autowired
    public RefundService(AlipayProperties alipayProperties,
                          OrderClient orderClient,
                          RefundRequestMapper refundRequestMapper,
                          PaymentMapper paymentMapper,
                          UserInternalClient userInternalClient,
                          TicketRefundReviewInternalClient ticketRefundReviewInternalClient,
                          NotificationMqProducer notificationProducer,
                          @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this(alipayProperties, orderClient, refundRequestMapper, paymentMapper,
                userInternalClient, ticketRefundReviewInternalClient, notificationProducer, internalApiToken, null);
    }

    public RefundService(AlipayProperties alipayProperties,
                         OrderClient orderClient,
                         RefundRequestMapper refundRequestMapper,
                         PaymentMapper paymentMapper,
                         UserInternalClient userInternalClient,
                         TicketRefundReviewInternalClient ticketRefundReviewInternalClient,
                         String internalApiToken,
                         Supplier<AlipayClient> alipayClientFactory) {
        this(alipayProperties, orderClient, refundRequestMapper, paymentMapper,
                userInternalClient, ticketRefundReviewInternalClient, null, internalApiToken, alipayClientFactory);
    }

    public RefundService(AlipayProperties alipayProperties,
                         OrderClient orderClient,
                         RefundRequestMapper refundRequestMapper,
                         PaymentMapper paymentMapper,
                         UserInternalClient userInternalClient,
                         TicketRefundReviewInternalClient ticketRefundReviewInternalClient,
                         NotificationMqProducer notificationProducer,
                         String internalApiToken,
                         Supplier<AlipayClient> alipayClientFactory) {
        this.alipayProperties = alipayProperties;
        this.orderClient = orderClient;
        this.refundRequestMapper = refundRequestMapper;
        this.paymentMapper = paymentMapper;
        this.userInternalClient = userInternalClient;
        this.ticketRefundReviewInternalClient = ticketRefundReviewInternalClient;
        this.notificationProducer = notificationProducer;
        this.internalApiToken = internalApiToken;
        this.alipayClientFactory = alipayClientFactory;
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequestVO applyRefund(Long orderId, Long userId, String reason) {
        return applyRefund(orderId, userId, reason, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequestVO applyRefund(Long orderId, Long userId, String reason, String reasonType) {
        return applyRefund(orderId, userId, reason, reasonType, null, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public RefundRequestVO applyRefund(Long orderId, Long userId, String reason, String reasonType,
                                       Integer quantity, List<Long> orderSeatIds) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        OrderInfoResponse order = getOrderOrThrow(orderId);
        if (!ORDER_STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已支付订单可申请退款");
        }
        if (!userId.equals(order.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "只能申请自己的订单退款");
        }

        if (hasBlockingRefund(orderId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已有退款申请，不允许重复申请");
        }

        OrderRefundOptionsResponse options = getRefundOptionsOrThrow(orderId);
        int refundableQuantity = valueOrZero(options.getRefundableQuantity());
        int refundedQuantity = valueOrZero(options.getRefundedQuantity());
        int refundQuantity = quantity == null ? refundableQuantity : quantity;
        if (refundQuantity <= 0 || refundQuantity > refundableQuantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "可退款票数不足");
        }
        validateOrderSeatIds(orderSeatIds, refundQuantity, options, false);
        LocalDateTime sessionTime = order.getSessionTime();
        LocalDateTime now = LocalDateTime.now(BUSINESS_ZONE);
        if (sessionTime == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "订单缺少演出时间，暂时无法计算退票金额");
        }
        int fullDaysBefore = order.getRefundFullDaysBefore() == null ? 30 : order.getRefundFullDaysBefore();
        int partialDaysBefore = order.getRefundPartialDaysBefore() == null ? 7 : order.getRefundPartialDaysBefore();
        int partialRate = order.getRefundPartialRate() == null ? 80 : order.getRefundPartialRate();
        if (!now.isBefore(sessionTime.minusDays(partialDaysBefore))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "开演前" + partialDaysBefore + "天内不可申请退票");
        }
        BigDecimal refundRate = now.isBefore(sessionTime.minusDays(fullDaysBefore))
                ? BigDecimal.ONE : BigDecimal.valueOf(partialRate).movePointLeft(2);
        BigDecimal amount = normalizeAmount(options.getUnitPrice())
                .multiply(BigDecimal.valueOf(refundQuantity))
                .multiply(refundRate)
                .setScale(2, RoundingMode.HALF_UP);
        String refundType = refundQuantity >= refundableQuantity && refundedQuantity == 0 ? "full" : "partial";

        Payment payment = getLatestSuccessfulPayment(order.getOrderNo());
        validatePaymentForOrder(payment, order);
        requireAlipayRefundablePayment(payment);

        if (hasBlockingRefund(orderId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已有退款申请，不允许重复申请");
        }

        RefundRequest refund = new RefundRequest();
        refund.setOrderId(order.getId());
        refund.setUserId(userId);
        refund.setPaymentId(payment.getId());
        refund.setRefundNo(generateRefundNo());
        refund.setAmount(amount);
        refund.setQuantity(refundQuantity);
        refund.setOrderSeatIds(joinIds(orderSeatIds));
        refund.setRefundType(refundType);
        refund.setReason(formatRefundReason(reason, reasonType));
        refund.setStatus(REFUND_STATUS_PENDING);
        refund.setCreateTime(LocalDateTime.now());
        try {
            refundRequestMapper.insert(refund);
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "该订单已有退款申请处理中");
            }
            throw e;
        }
        return toVO(refund, order);
    }

    private String formatRefundReason(String reason, String reasonType) {
        String text = reason == null ? "" : reason.trim();
        if ("cast_change".equals(reasonType)) {
            return text.isEmpty() ? "阵容变更" : "阵容变更：" + text;
        }
        return reason;
    }

    public List<RefundRequestVO> listUserRefunds(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "用户ID不能为空");
        }
        LambdaQueryWrapper<RefundRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RefundRequest::getUserId, userId)
                .orderByDesc(RefundRequest::getCreateTime)
                .orderByDesc(RefundRequest::getId);
        return refundRequestMapper.selectList(wrapper).stream()
                .map(this::toVOWithOrderNo)
                .collect(Collectors.toList());
    }

    public List<RefundRequestVO> listAdminRefunds(Long reviewerId, Integer status) {
        InternalUserRefResponse reviewer = requireReviewer(reviewerId);
        LambdaQueryWrapper<RefundRequest> wrapper = new LambdaQueryWrapper<>();
        if (status != null) {
            wrapper.eq(RefundRequest::getStatus, status);
        }
        wrapper.orderByDesc(RefundRequest::getCreateTime)
                .orderByDesc(RefundRequest::getId);
        List<RefundRequest> refunds = refundRequestMapper.selectList(wrapper);

        if (isAdminReviewer(reviewer) || hasPlatformRefundReviewPermission(reviewerId)) {
            return refunds.stream().map(this::toVOWithOrderNo).collect(Collectors.toList());
        }

        List<RefundRequestVO> result = new ArrayList<>();
        for (RefundRequest refund : refunds) {
            OrderInfoResponse order = getOrderOrThrow(refund.getOrderId());
            if (canOrganizerReview(order, reviewerId)) {
                result.add(toVO(refund, order));
            }
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<RefundRequestVO> batchReview(Long reviewerId, List<Long> ids, String action, String reason) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请不能为空");
        }
        List<Long> normalizedIds = new ArrayList<>(new LinkedHashSet<>(ids));
        for (Long id : normalizedIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请ID不正确");
            }
        }
        String normalizedAction = requireText(action, "退款审核动作不能为空").trim().toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(normalizedAction) && !"REJECT".equals(normalizedAction)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款审核动作不正确");
        }
        String reviewNote = requireText(reason, "批量审核原因不能为空").trim();

        List<RefundRequestVO> reviewed = new ArrayList<>();
        for (Long id : normalizedIds) {
            if ("APPROVE".equals(normalizedAction)) {
                reviewed.add(approve(id, reviewerId, reviewNote));
            } else {
                reviewed.add(reject(id, reviewerId, reviewNote));
            }
        }
        return reviewed;
    }

    public RefundRequestVO reject(Long refundId, Long reviewerId, String reviewNote) {
        RefundRequest refund = getRefundOrThrow(refundId);
        OrderInfoResponse order = getOrderOrThrow(refund.getOrderId());
        requireReviewPermission(reviewerId, order);
        requirePending(refund);

        LocalDateTime now = LocalDateTime.now();
        refund = updateRefundRejected(refundId, reviewerId, reviewNote, now);
        sendRefundNotificationEvent("REFUND_REJECTED", refund, order);
        return toVO(refund, order);
    }

    public RefundRequestVO approve(Long refundId, Long reviewerId, String reviewNote) {
        validateConfig();
        RefundRequest refund = getRefundOrThrow(refundId);
        OrderInfoResponse order = getOrderOrThrow(refund.getOrderId());
        requireReviewPermission(reviewerId, order);
        if (REFUND_STATUS_REFUNDED.equals(refund.getStatus())) {
            return toVO(refund, order);
        }
        requireApprovable(refund);
        if (!ORDER_STATUS_PAID.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅已支付订单可审核退款");
        }

        Payment payment = getPaymentOrThrow(refund.getPaymentId(), order.getOrderNo());
        validatePaymentForOrder(payment, order);
        if (!isAlipayRefundablePayment(payment)) {
            LocalDateTime now = LocalDateTime.now();
            markRefundValidationFailed(refund, reviewerId, appendMessage(reviewNote, ALIPAY_UNCONFIRMED_REFUND_MESSAGE),
                    ALIPAY_UNCONFIRMED_REFUND_MESSAGE, now);
            return toVO(getRefundOrThrow(refundId), order);
        }
        validateRefundAmount(refund, order);
        validatePartialRefundBeforeAlipay(refund, reviewerId, reviewNote);

        LocalDateTime now = LocalDateTime.now();
        if (REFUND_STATUS_PENDING.equals(refund.getStatus())) {
            refund = claimRefundProcessing(refundId, reviewerId, reviewNote, now);
        } else {
            refund = refreshProcessingReview(refundId, reviewerId, reviewNote, now);
        }
        validateRefundAmount(refund, order);

        AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
        Map<String, String> bizContent = new LinkedHashMap<>();
        bizContent.put("out_trade_no", order.getOrderNo());
        bizContent.put("trade_no", payment.getTradeNo());
        bizContent.put("refund_amount", normalizeAmount(refund.getAmount()).toPlainString());
        bizContent.put("out_request_no", refund.getRefundNo());
        bizContent.put("refund_reason", StringUtils.hasText(refund.getReason()) ? refund.getReason() : "用户申请退款");
        request.setBizContent(buildJson(bizContent));

        AlipayTradeRefundResponse response;
        try {
            response = callAlipayChannel(() -> createClient().execute(request));
        } catch (AlipayApiException | RuntimeException e) {
            refund = updateRefundUnknown(refundId, reviewerId, reviewNote,
                    "支付宝退款异常，退款结果未知，请稍后重试/查询: " + e.getMessage(), now);
            sendRefundNotificationEvent("REFUND_UNKNOWN", refund, order);
            return toVO(refund, order);
        }

        if (response != null && response.isSuccess()) {
            String alipayRefundNo = firstText(response.getTradeNo(), response.getOutTradeNo());
            try {
                markOrderRefundedByType(order.getId(), refund);
            } catch (RuntimeException e) {
                updateRefundCompensationRequired(refundId, reviewerId, reviewNote, alipayRefundNo, response.getBody(), e.getMessage(), now);
                sendRefundNotificationEvent("COMPENSATION_REQUIRED", refund, order);
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "支付宝退款已成功，但订单状态更新失败，需人工补偿");
            }
            refund = updateRefundSucceeded(refundId, reviewerId, reviewNote, alipayRefundNo, response.getBody(), now);
            sendRefundNotificationEvent("REFUND_APPROVED", refund, order);
            return toVO(refund, order);
        }

        if (response != null && !response.isSuccess()) {
            refund = updateRefundFailed(refundId, reviewerId, reviewNote, response.getBody(), now);
        } else {
            refund = updateRefundUnknown(refundId, reviewerId, reviewNote, "支付宝退款响应为空，退款结果未知，请稍后重试/查询", now);
            sendRefundNotificationEvent("REFUND_UNKNOWN", refund, order);
        }
        return toVO(refund, order);
    }

    public DirectRefundResponse directRefund(Long orderId, String reason) {
        DirectRefundResponse result = new DirectRefundResponse();
        result.setOrderId(orderId);
        try {
            validateConfig();
            OrderInfoResponse order = getOrderOrThrow(orderId);
            result.setOrderNo(order.getOrderNo());
            if (!ORDER_STATUS_PAID.equals(order.getStatus())) {
                return directRefundFailure(result, "仅已支付订单可直接退款");
            }

            Payment payment = getLatestSuccessfulPayment(order.getOrderNo());
            validatePaymentForOrder(payment, order);
            requireAlipayRefundablePayment(payment);

            AlipayTradeRefundRequest request = new AlipayTradeRefundRequest();
            Map<String, String> bizContent = new LinkedHashMap<>();
            bizContent.put("out_trade_no", order.getOrderNo());
            bizContent.put("trade_no", payment.getTradeNo());
            bizContent.put("refund_amount", normalizeAmount(order.getAmount()).toPlainString());
            bizContent.put("out_request_no", directRefundRequestNo(order));
            bizContent.put("refund_reason", StringUtils.hasText(reason) ? reason : "活动下架自动退款");
            request.setBizContent(buildJson(bizContent));

            AlipayTradeRefundResponse response = callAlipayChannel(() -> createClient().execute(request));
            if (response != null && response.isSuccess()) {
                try {
                    markOrderRefunded(order.getId());
                } catch (RuntimeException e) {
                    return directRefundResult(result, DIRECT_REFUND_STATUS_COMPENSATION_REQUIRED, false,
                            "支付宝已成功退款，但订单状态更新失败，需人工处理: " + e.getMessage());
                }
                result.setStatus(DIRECT_REFUND_STATUS_SUCCESS);
                result.setSuccess(true);
                result.setMessage("退款成功");
                return result;
            }
            if (response == null) {
                return directRefundResult(result, DIRECT_REFUND_STATUS_UNKNOWN, false,
                        "支付宝退款响应为空，退款结果未知，请查询/人工确认");
            }
            String message = StringUtils.hasText(response.getBody()) ? response.getBody() : "支付宝退款失败";
            return directRefundResult(result, DIRECT_REFUND_STATUS_FAILED, false, message);
        } catch (AlipayApiException e) {
            return directRefundResult(result, DIRECT_REFUND_STATUS_UNKNOWN, false,
                    "支付宝退款异常，退款结果未知，请查询/人工确认: " + e.getMessage());
        } catch (BusinessException e) {
            if ("支付宝退款结果未知，请稍后重试/查询".equals(e.getMessage())) {
                return directRefundResult(result, DIRECT_REFUND_STATUS_UNKNOWN, false, e.getMessage());
            }
            return directRefundResult(result, DIRECT_REFUND_STATUS_FAILED, false, e.getMessage());
        }
    }

    private DirectRefundResponse directRefundFailure(DirectRefundResponse result, String message) {
        return directRefundResult(result, DIRECT_REFUND_STATUS_FAILED, false, message);
    }

    private DirectRefundResponse directRefundResult(DirectRefundResponse result, String status, Boolean success, String message) {
        result.setStatus(status);
        result.setSuccess(success);
        result.setMessage(StringUtils.hasText(message) ? message : "退款失败");
        return result;
    }

    private String directRefundRequestNo(OrderInfoResponse order) {
        if (order != null && order.getId() != null) {
            return "DIRECT_REFUND_" + order.getId();
        }
        return "DIRECT_REFUND_" + requireText(order != null ? order.getOrderNo() : null, "订单号不能为空");
    }

    private void sendRefundNotificationEvent(String eventType, RefundRequest refund, OrderInfoResponse order) {
        if (notificationProducer == null || refund == null || order == null || refund.getId() == null) {
            return;
        }
        Long orderId = refund.getOrderId() != null ? refund.getOrderId() : order.getId();
        Long userId = refund.getUserId() != null ? refund.getUserId() : order.getUserId();
        if (orderId == null || userId == null) {
            log.warn("退款通知事件缺少必要字段，跳过发送 eventType={}, refundId={}, orderId={}, userId={}",
                    eventType, refund.getId(), orderId, userId);
            return;
        }

        NotificationEventMessage message = new NotificationEventMessage();
        message.setEventId(refundEventPrefix(eventType) + ":" + refund.getId() + ":" + refundEventSuffix(refund));
        message.setEventType(eventType);
        message.setAggregateKey(eventType + ":" + refund.getId());
        message.setUserId(userId);
        message.setOrderId(orderId);
        message.setActivityId(order.getActivityId());
        message.setChannels(List.of(NotificationEventMessage.CHANNEL_IN_APP, NotificationEventMessage.CHANNEL_SMS));
        if ("REFUND_UNKNOWN".equals(eventType) || "COMPENSATION_REQUIRED".equals(eventType)) {
            message.setPriority("HIGH");
        }
        message.setContent(refundNotificationContent(eventType));
        message.setActionHref("/orders/" + orderId);
        message.setActionLabel("查看订单");
        message.setPayload(refundNotificationPayload(eventType, refund, orderId, order));
        message.setOccurredAt(LocalDateTime.now());
        notificationProducer.sendNotificationEvent(message);
    }

    private String refundEventPrefix(String eventType) {
        return eventType.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private String refundEventSuffix(RefundRequest refund) {
        if (StringUtils.hasText(refund.getRefundNo())) {
            return refund.getRefundNo();
        }
        return String.valueOf(refund.getStatus());
    }

    private Map<String, Object> refundNotificationPayload(String eventType, RefundRequest refund,
                                                          Long orderId, OrderInfoResponse order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", eventType);
        payload.put("refundId", refund.getId());
        payload.put("refundNo", refund.getRefundNo());
        payload.put("orderId", orderId);
        payload.put("amount", refund.getAmount());
        payload.put("refundType", refund.getRefundType());
        payload.put("activityId", order.getActivityId());
        payload.put("activityName", order.getActivityName());
        return payload;
    }

    private String refundNotificationContent(String eventType) {
        if ("REFUND_APPROVED".equals(eventType)) {
            return "你的退款申请已通过，退款进度请查看订单。";
        }
        if ("REFUND_REJECTED".equals(eventType)) {
            return "你的退款申请未通过，详情请查看订单。";
        }
        if ("REFUND_UNKNOWN".equals(eventType)) {
            return "退款结果暂未确认，请稍后查看订单或联系客服。";
        }
        if ("COMPENSATION_REQUIRED".equals(eventType)) {
            return "退款已进入人工补偿处理，请查看订单。";
        }
        return "退款状态已更新，请查看订单。";
    }

    private RefundRequest updateRefundRejected(Long refundId, Long reviewerId, String reviewNote, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = pendingUpdateWrapper(refundId)
                .set(RefundRequest::getStatus, REFUND_STATUS_REJECTED)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, reviewNote)
                .set(RefundRequest::getReviewTime, now);
        updatePendingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private RefundRequest updateRefundSucceeded(Long refundId, Long reviewerId, String reviewNote,
                                                String alipayRefundNo, String rawResponse, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = processingUpdateWrapper(refundId)
                .set(RefundRequest::getStatus, REFUND_STATUS_REFUNDED)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, reviewNote)
                .set(RefundRequest::getAlipayRefundNo, alipayRefundNo)
                .set(RefundRequest::getRawResponse, rawResponse)
                .set(RefundRequest::getReviewTime, now)
                .set(RefundRequest::getRefundTime, now);
        updateProcessingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private RefundRequest updateRefundFailed(Long refundId, Long reviewerId, String reviewNote, String rawResponse, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = processingUpdateWrapper(refundId)
                .set(RefundRequest::getStatus, REFUND_STATUS_FAILED)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, reviewNote)
                .set(RefundRequest::getRawResponse, rawResponse)
                .set(RefundRequest::getReviewTime, now);
        updateProcessingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private RefundRequest markPendingRefundFailed(Long refundId, Long reviewerId, String reviewNote, String rawResponse, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = pendingUpdateWrapper(refundId)
                .set(RefundRequest::getStatus, REFUND_STATUS_FAILED)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, reviewNote)
                .set(RefundRequest::getRawResponse, rawResponse)
                .set(RefundRequest::getReviewTime, now);
        updatePendingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private RefundRequest updateRefundUnknown(Long refundId, Long reviewerId, String reviewNote, String rawResponse, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = processingUpdateWrapper(refundId)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, appendMessage(reviewNote, rawResponse))
                .set(RefundRequest::getRawResponse, rawResponse)
                .set(RefundRequest::getReviewTime, now);
        updateProcessingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private RefundRequest claimRefundProcessing(Long refundId, Long reviewerId, String reviewNote, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = pendingUpdateWrapper(refundId)
                .set(RefundRequest::getStatus, REFUND_STATUS_PROCESSING)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, reviewNote)
                .set(RefundRequest::getReviewTime, now);
        updatePendingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private RefundRequest refreshProcessingReview(Long refundId, Long reviewerId, String reviewNote, LocalDateTime now) {
        LambdaUpdateWrapper<RefundRequest> wrapper = processingUpdateWrapper(refundId)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, reviewNote)
                .set(RefundRequest::getReviewTime, now);
        updateProcessingOrThrow(wrapper);
        return getRefundOrThrow(refundId);
    }

    private void updateRefundCompensationRequired(Long refundId, Long reviewerId, String reviewNote,
                                                  String alipayRefundNo, String rawResponse,
                                                  String failureMessage, LocalDateTime now) {
        String message = "支付宝退款已成功，但订单退款状态更新失败，需要人工补偿/重试: " + failureMessage;
        LambdaUpdateWrapper<RefundRequest> wrapper = processingUpdateWrapper(refundId)
                .set(RefundRequest::getStatus, REFUND_STATUS_FAILED)
                .set(RefundRequest::getReviewerId, reviewerId)
                .set(RefundRequest::getReviewNote, appendMessage(reviewNote, message))
                .set(RefundRequest::getAlipayRefundNo, alipayRefundNo)
                .set(RefundRequest::getRawResponse, appendMessage(rawResponse, message))
                .set(RefundRequest::getReviewTime, now);
        updateProcessingOrThrow(wrapper);
    }

    private LambdaUpdateWrapper<RefundRequest> pendingUpdateWrapper(Long refundId) {
        LambdaUpdateWrapper<RefundRequest> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(RefundRequest::getId, refundId)
                .eq(RefundRequest::getStatus, REFUND_STATUS_PENDING);
        return wrapper;
    }

    private LambdaUpdateWrapper<RefundRequest> processingUpdateWrapper(Long refundId) {
        LambdaUpdateWrapper<RefundRequest> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(RefundRequest::getId, refundId)
                .eq(RefundRequest::getStatus, REFUND_STATUS_PROCESSING);
        return wrapper;
    }

    private void updatePendingOrThrow(LambdaUpdateWrapper<RefundRequest> wrapper) {
        int updated = refundRequestMapper.update(null, wrapper);
        if (updated != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请状态已变化");
        }
    }

    private void updateProcessingOrThrow(LambdaUpdateWrapper<RefundRequest> wrapper) {
        int updated = refundRequestMapper.update(null, wrapper);
        if (updated != 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请状态已变化");
        }
    }

    private void markOrderRefunded(Long orderId) {
        String token = requireInternalApiToken();
        Result<OrderInfoResponse> result = callOrderClient(() -> orderClient.markRefunded(orderId, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = result != null && StringUtils.hasText(result.getMessage()) ? result.getMessage() : "更新订单退款状态失败";
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新订单退款状态失败: " + message);
        }
    }

    private void markOrderRefundedByType(Long orderId, RefundRequest refund) {
        if ("partial".equals(refund.getRefundType())) {
            markOrderPartialRefunded(orderId, refund);
            return;
        }
        markOrderRefunded(orderId);
    }

    private void markOrderPartialRefunded(Long orderId, RefundRequest refund) {
        MarkPartialRefundedRequest request = new MarkPartialRefundedRequest();
        request.setQuantity(refund.getQuantity());
        request.setOrderSeatIds(parseIds(refund.getOrderSeatIds()));
        String token = requireInternalApiToken();
        Result<OrderInfoResponse> result = callOrderClient(() -> orderClient.markPartialRefunded(orderId, request, token));
        if (result == null || result.getCode() != ResultCode.SUCCESS.getCode() || result.getData() == null) {
            String message = result != null && StringUtils.hasText(result.getMessage()) ? result.getMessage() : "更新订单部分退款状态失败";
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "更新订单部分退款状态失败: " + message);
        }
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
        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "加载订单失败: 订单服务无响应");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode()) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "订单服务返回失败";
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "加载订单失败: " + message);
        }
        if (result.getData() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "订单不存在");
        }
        return result.getData();
    }

    private OrderRefundOptionsResponse getRefundOptionsOrThrow(Long orderId) {
        String token = requireInternalApiToken();
        Result<OrderRefundOptionsResponse> result;
        try {
            result = callOrderClient(() -> orderClient.getRefundOptions(orderId, token));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("订单退款明细调用失败: orderId={}", orderId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "订单服务无响应");
        }
        if (result == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "加载可退款明细失败: 订单服务无响应");
        }
        if (result.getCode() != ResultCode.SUCCESS.getCode()) {
            String message = StringUtils.hasText(result.getMessage()) ? result.getMessage() : "订单服务返回失败";
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "加载可退款明细失败: " + message);
        }
        if (result.getData() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "可退款明细不存在");
        }
        return result.getData();
    }

    private RefundRequest getRefundOrThrow(Long refundId) {
        if (refundId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请ID不能为空");
        }
        RefundRequest refund = refundRequestMapper.selectById(refundId);
        if (refund == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "退款申请不存在");
        }
        return refund;
    }

    private InternalUserRefResponse requireReviewer(Long reviewerId) {
        if (reviewerId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "审核人ID不能为空");
        }
        Result<InternalUserRefResponse> result;
        try {
            result = userInternalClient.getUserRef(reviewerId, internalApiToken);
        } catch (RuntimeException e) {
            log.error("用户服务调用失败: reviewerId={}", reviewerId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "用户服务无响应");
        }
        if (result == null || result.getCode() != 200 || result.getData() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "审核人不存在");
        }
        InternalUserRefResponse reviewer = result.getData();
        if (!isAdminReviewer(reviewer)
                && !ROLE_ORGANIZER.equals(reviewer.getRole())
                && !hasPlatformRefundReviewPermission(reviewerId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无退款审核权限");
        }
        return reviewer;
    }

    private void requireReviewPermission(Long reviewerId, OrderInfoResponse order) {
        InternalUserRefResponse reviewer = requireReviewer(reviewerId);
        if (isAdminReviewer(reviewer) || hasPlatformRefundReviewPermission(reviewerId)) {
            return;
        }
        requireRefundPermission(reviewerId);
        if (!canOrganizerReview(order, reviewerId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权审核该活动退款");
        }
    }

    private void requireRefundPermission(Long reviewerId) {
        try {
            Result<InternalAuthContextResponse> result = userInternalClient.getAuthContext(reviewerId, internalApiToken);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                InternalAuthContextResponse auth = result.getData();
                if (auth.getPermissionCodes() == null || !auth.getPermissionCodes().contains(PERMISSION_REFUND_REVIEW)) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "无权限");
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("RBAC权限查询失败，降级使用角色检查: reviewerId={}", reviewerId, e);
        }
    }

    private boolean canOrganizerReview(OrderInfoResponse order, Long reviewerId) {
        if (order == null || order.getSessionId() == null) {
            return false;
        }
        try {
            Result<TicketRefundReviewPermissionResponse> result =
                    ticketRefundReviewInternalClient.checkPermission(
                            order.getSessionId(), reviewerId, internalApiToken);
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                return false;
            }
            return Boolean.TRUE.equals(result.getData().getAllowed());
        } catch (RuntimeException e) {
            log.error("票务服务调用失败: sessionId={}, reviewerId={}", order.getSessionId(), reviewerId, e);
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "票务服务无响应");
        }
    }

    private boolean isAdminReviewer(InternalUserRefResponse reviewer) {
        return reviewer != null
                && (ROLE_ADMIN.equals(reviewer.getRole()) || ROLE_PLATFORM_SUPER_ADMIN.equals(reviewer.getRole()));
    }

    private boolean hasPlatformRefundReviewPermission(Long reviewerId) {
        try {
            Result<InternalAuthContextResponse> result = userInternalClient.getAuthContext(reviewerId, internalApiToken);
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                return false;
            }
            InternalAuthContextResponse auth = result.getData();
            return SCOPE_PLATFORM.equals(auth.getScopeType())
                    && auth.getPermissionCodes() != null
                    && auth.getPermissionCodes().contains(PERMISSION_REFUND_REVIEW);
        } catch (RuntimeException e) {
            log.warn("RBAC权限查询失败，降级使用角色检查: reviewerId={}", reviewerId, e);
            return false;
        }
    }

    private void requirePending(RefundRequest refund) {
        if (!REFUND_STATUS_PENDING.equals(refund.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅待审核退款申请可处理");
        }
    }

    private void requireApprovable(RefundRequest refund) {
        if (REFUND_STATUS_PENDING.equals(refund.getStatus()) || REFUND_STATUS_PROCESSING.equals(refund.getStatus())) {
            return;
        }
        if (REFUND_STATUS_REJECTED.equals(refund.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请已拒绝，不能同意退款");
        }
        if (REFUND_STATUS_FAILED.equals(refund.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款已明确失败，请用户重新申请退款");
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "退款申请状态已变化");
    }

    private RefundRequest getLatestRefundByOrderId(Long orderId) {
        LambdaQueryWrapper<RefundRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RefundRequest::getOrderId, orderId)
                .orderByDesc(RefundRequest::getCreateTime)
                .orderByDesc(RefundRequest::getId)
                .last("LIMIT 1");
        return refundRequestMapper.selectOne(wrapper);
    }

    private boolean hasBlockingRefund(Long orderId) {
        LambdaQueryWrapper<RefundRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RefundRequest::getOrderId, orderId)
                .and(w -> w.in(RefundRequest::getStatus, REFUND_STATUS_PENDING, REFUND_STATUS_PROCESSING)
                        .or(q -> q.eq(RefundRequest::getStatus, REFUND_STATUS_REFUNDED)
                                .eq(RefundRequest::getRefundType, "full"))
                        .or(q -> q.eq(RefundRequest::getStatus, REFUND_STATUS_FAILED)
                                .isNotNull(RefundRequest::getAlipayRefundNo)))
                .last("LIMIT 1");
        return refundRequestMapper.selectOne(wrapper) != null;
    }

    private Payment getLatestSuccessfulPayment(String outTradeNo) {
        if (!StringUtils.hasText(outTradeNo)) {
            return null;
        }
        LambdaQueryWrapper<Payment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Payment::getOutTradeNo, outTradeNo)
                .eq(Payment::getStatus, PaymentService.STATUS_SUCCESS)
                .isNotNull(Payment::getTradeNo)
                .orderByDesc(Payment::getCreateTime)
                .orderByDesc(Payment::getId)
                .last("LIMIT 1");
        return paymentMapper.selectOne(wrapper);
    }

    private Payment getPaymentOrThrow(Long paymentId, String outTradeNo) {
        Payment payment = paymentId != null ? paymentMapper.selectById(paymentId) : null;
        if (payment == null) {
            payment = getLatestSuccessfulPayment(outTradeNo);
        }
        if (payment == null || payment.getStatus() == null || PaymentService.STATUS_SUCCESS != payment.getStatus() || !StringUtils.hasText(payment.getTradeNo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未找到可退款的成功支付流水");
        }
        return payment;
    }

    private void validatePaymentForOrder(Payment payment, OrderInfoResponse order) {
        if (payment == null || !StringUtils.hasText(payment.getTradeNo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未找到可退款的成功支付流水");
        }
        if (!order.getId().equals(payment.getOrderId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付流水订单不匹配");
        }
        if (!order.getOrderNo().equals(payment.getOutTradeNo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付流水商户订单号不匹配");
        }
        if (!amountEquals(payment.getAmount(), order.getAmount())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "支付流水金额与订单金额不一致");
        }
    }

    private void requireAlipayRefundablePayment(Payment payment) {
        if (!isAlipayRefundablePayment(payment)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, ALIPAY_UNCONFIRMED_REFUND_MESSAGE);
        }
    }

    private boolean isAlipayRefundablePayment(Payment payment) {
        return payment != null
                && "ALIPAY".equalsIgnoreCase(payment.getPaymentMethod())
                && (containsTradeNo(payment.getRawNotify(), payment.getTradeNo())
                || containsTradeNo(payment.getCallbackData(), payment.getTradeNo()));
    }

    private boolean containsTradeNo(String payload, String tradeNo) {
        return StringUtils.hasText(payload) && StringUtils.hasText(tradeNo)
                && payload.contains("trade_no") && payload.contains(tradeNo);
    }

    private void validateRefundAmount(RefundRequest refund, OrderInfoResponse order) {
        normalizeAmount(refund.getAmount());
        if (!"partial".equals(refund.getRefundType()) && !amountEquals(refund.getAmount(), order.getAmount())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款金额与订单金额不一致");
        }
    }

    private void validatePartialRefundBeforeAlipay(RefundRequest refund, Long reviewerId, String reviewNote) {
        if (!"partial".equals(refund.getRefundType())) {
            return;
        }
        try {
            validatePartialRefundAgainstOptions(refund, getRefundOptionsOrThrow(refund.getOrderId()), true);
        } catch (BusinessException e) {
            markRefundValidationFailed(refund, reviewerId, appendMessage(reviewNote, e.getMessage()), e.getMessage(), LocalDateTime.now());
            throw e;
        }
    }

    private void markRefundValidationFailed(RefundRequest refund, Long reviewerId, String reviewNote, String rawResponse, LocalDateTime now) {
        if (REFUND_STATUS_PROCESSING.equals(refund.getStatus())) {
            updateRefundFailed(refund.getId(), reviewerId, reviewNote, rawResponse, now);
            return;
        }
        markPendingRefundFailed(refund.getId(), reviewerId, reviewNote, rawResponse, now);
    }

    private void validatePartialRefundAgainstOptions(RefundRequest refund, OrderRefundOptionsResponse options, boolean approval) {
        int refundQuantity = valueOrZero(refund.getQuantity());
        int refundableQuantity = valueOrZero(options.getRefundableQuantity());
        if (refundQuantity <= 0 || refundQuantity > refundableQuantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    approval ? "当前可退款票数不足，请拒绝后让用户重新申请" : "可退款票数不足");
        }
        List<Long> orderSeatIds = parseIds(refund.getOrderSeatIds());
        try {
            validateOrderSeatIds(orderSeatIds, refundQuantity, options, approval);
        } catch (BusinessException e) {
            if (approval && "选择座位不可退款".equals(e.getMessage())) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "退款座位当前不可退，请拒绝后让用户重新申请");
            }
            throw e;
        }
        BigDecimal expectedAmount = normalizeAmount(options.getUnitPrice())
                .multiply(BigDecimal.valueOf(refundQuantity))
                .setScale(2, RoundingMode.HALF_UP);
        if (!amountEquals(refund.getAmount(), expectedAmount)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    approval ? "退款金额已变化，请拒绝后让用户重新申请" : "退款金额与可退款明细不一致");
        }
    }

    private boolean isActiveRefundStatus(Integer status) {
        return REFUND_STATUS_PENDING.equals(status)
                || REFUND_STATUS_REFUNDED.equals(status)
                || REFUND_STATUS_PROCESSING.equals(status);
    }

    private boolean isBlockingRefund(RefundRequest refund) {
        if (refund == null || !isActiveRefundStatus(refund.getStatus())) {
            return false;
        }
        return !REFUND_STATUS_REFUNDED.equals(refund.getStatus()) || !"partial".equals(refund.getRefundType());
    }

    private boolean isUniqueViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException && "23505".equals(((SQLException) current).getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private RefundRequestVO toVOWithOrderNo(RefundRequest refund) {
        OrderInfoResponse order = getOrderOrThrow(refund.getOrderId());
        return toVO(refund, order);
    }

    private RefundRequestVO toVO(RefundRequest refund, OrderInfoResponse order) {
        RefundRequestVO vo = toVO(refund, order != null ? order.getOrderNo() : null);
        if (order != null) {
            vo.setActivityName(order.getActivityName());
            vo.setOrderName(StringUtils.hasText(order.getActivityName()) ? order.getActivityName() : order.getOrderNo());
        }
        return vo;
    }

    private RefundRequestVO toVO(RefundRequest refund, String orderNo) {
        RefundRequestVO vo = new RefundRequestVO();
        vo.setId(refund.getId());
        vo.setOrderId(refund.getOrderId());
        vo.setOrderNo(orderNo);
        vo.setUserId(refund.getUserId());
        vo.setPaymentId(refund.getPaymentId());
        vo.setRefundNo(refund.getRefundNo());
        vo.setAmount(refund.getAmount());
        vo.setQuantity(refund.getQuantity());
        vo.setOrderSeatIds(refund.getOrderSeatIds());
        vo.setRefundType(refund.getRefundType());
        vo.setReason(refund.getReason());
        vo.setStatus(refund.getStatus());
        vo.setReviewerId(refund.getReviewerId());
        vo.setReviewNote(refund.getReviewNote());
        vo.setAlipayRefundNo(refund.getAlipayRefundNo());
        vo.setCreateTime(refund.getCreateTime());
        vo.setReviewTime(refund.getReviewTime());
        vo.setRefundTime(refund.getRefundTime());
        return vo;
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款金额不能为空");
        }
        if (amount.scale() > 2) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款金额最多保留两位小数");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "退款金额必须大于0");
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private void validateOrderSeatIds(List<Long> orderSeatIds, int refundQuantity, OrderRefundOptionsResponse options, boolean approval) {
        boolean hasSeatOptions = options.getSeats() != null && !options.getSeats().isEmpty();
        if (hasSeatOptions && (orderSeatIds == null || orderSeatIds.isEmpty())) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    approval ? "退款座位当前不可退，请拒绝后让用户重新申请" : "有座订单必须选择退款座位");
        }
        if (orderSeatIds == null || orderSeatIds.isEmpty()) {
            return;
        }
        if (orderSeatIds.size() != refundQuantity) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "选择座位数量与退款票数不一致");
        }
        if (orderSeatIds.stream().distinct().count() != orderSeatIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "选择座位重复");
        }
        List<Long> allowedIds = options.getSeats() == null ? List.of() : options.getSeats().stream()
                .map(seat -> seat.getOrderSeatId())
                .collect(Collectors.toList());
        if (!allowedIds.containsAll(orderSeatIds)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "选择座位不可退款");
        }
    }

    private String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        return ids.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    private List<Long> parseIds(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : text.split(",")) {
            if (StringUtils.hasText(part)) {
                ids.add(Long.valueOf(part.trim()));
            }
        }
        return ids;
    }

    private boolean amountEquals(BigDecimal left, BigDecimal right) {
        return normalizeAmount(left).compareTo(normalizeAmount(right)) == 0;
    }

    private String appendMessage(String original, String message) {
        if (!StringUtils.hasText(original)) {
            return message;
        }
        if (!StringUtils.hasText(message)) {
            return original;
        }
        return original + "; " + message;
    }

    private void validateConfig() {
        requireText(alipayProperties.getAppId(), "支付宝 appId 未配置");
        requireText(alipayProperties.getMerchantPrivateKey(), "支付宝商户私钥未配置");
        requireText(alipayProperties.getAlipayPublicKey(), "支付宝公钥未配置");
        requireText(alipayProperties.getGatewayUrl(), "支付宝网关未配置");
    }

    private String requireInternalApiToken() {
        if (!StringUtils.hasText(internalApiToken)) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "内部接口令牌未配置");
        }
        return internalApiToken;
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
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "支付宝退款结果未知，请稍后重试/查询");
        } catch (AlipayApiException | RuntimeException e) {
            Tracer.traceEntry(e, entry);
            throw e;
        } finally {
            if (entry != null) {
                entry.exit();
            }
        }
    }

    private interface AlipayCall<T> {
        T execute() throws AlipayApiException;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, message);
        }
        return value;
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

    private String generateRefundNo() {
        return "RF" + System.currentTimeMillis() + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private String firstText(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
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
