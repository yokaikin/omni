package com.omni.payment.service;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradePrecreateRequest;
import com.alipay.api.response.AlipayTradePrecreateResponse;
import com.omni.common.result.Result;
import com.omni.exception.BusinessException;
import com.omni.payment.client.OrderClient;
import com.omni.payment.config.AlipayProperties;
import com.omni.payment.config.PaymentSentinelConfig;
import com.omni.payment.dto.OrderInfoResponse;
import com.omni.payment.dto.QrPayResponse;
import com.omni.payment.entity.Payment;
import com.omni.payment.mapper.PaymentMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlipayServiceTest {

    private AlipayProperties properties;
    private OrderClient orderClient;
    private PaymentMapper paymentMapper;
    private AlipayClient alipayClient;
    private AlipayService service;

    @BeforeEach
    void setUp() {
        properties = new AlipayProperties();
        properties.setGatewayUrl("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        properties.setAppId("app-id");
        properties.setMerchantPrivateKey("merchant-private-key");
        properties.setAlipayPublicKey("alipay-public-key");
        properties.setReturnUrl("http://localhost/payment/result");
        orderClient = mock(OrderClient.class);
        paymentMapper = mock(PaymentMapper.class);
        alipayClient = mock(AlipayClient.class);
        service = new AlipayService(properties, orderClient, paymentMapper, "internal-token", () -> alipayClient);
    }

    @Test
    void createQrPayCreatesPendingPaymentAndReturnsQrCode() throws Exception {
        OrderInfoResponse order = order(10L, "DM1001", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(10L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        AlipayTradePrecreateResponse response = mock(AlipayTradePrecreateResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getQrCode()).thenReturn("https://qr.alipay.com/test");
        when(alipayClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(response);

        QrPayResponse result = service.createQrPay(10L);

        assertEquals(10L, result.getOrderId());
        assertEquals("DM1001", result.getOrderNo());
        assertEquals(new BigDecimal("280.00"), result.getAmount());
        assertEquals("万象票务订单 DM1001", result.getSubject());
        assertEquals("https://qr.alipay.com/test", result.getQrCode());

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).insert(paymentCaptor.capture());
        Payment payment = paymentCaptor.getValue();
        assertEquals(10L, payment.getOrderId());
        assertEquals("DM1001", payment.getOutTradeNo());
        assertEquals("ALIPAY", payment.getPaymentMethod());
        assertEquals(new BigDecimal("280.00"), payment.getAmount());
        assertEquals(PaymentService.STATUS_PENDING, payment.getStatus());
        assertNotNull(payment.getPaymentNo());
    }

    @Test
    void createQrPayWaitsUntilPrecreatedTradeIsQueryable() throws Exception {
        OrderInfoResponse order = order(14L, "DM1005", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(14L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        AlipayTradePrecreateResponse precreate = mock(AlipayTradePrecreateResponse.class);
        when(precreate.isSuccess()).thenReturn(true);
        when(precreate.getQrCode()).thenReturn("https://qr.alipay.com/wait-test");
        com.alipay.api.response.AlipayTradeQueryResponse notVisible = mock(com.alipay.api.response.AlipayTradeQueryResponse.class);
        when(notVisible.isSuccess()).thenReturn(false);
        when(notVisible.getSubCode()).thenReturn("ACQ.TRADE_NOT_EXIST");
        com.alipay.api.response.AlipayTradeQueryResponse visible = mock(com.alipay.api.response.AlipayTradeQueryResponse.class);
        when(visible.isSuccess()).thenReturn(true);
        when(alipayClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(precreate);
        when(alipayClient.execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class)))
                .thenReturn(notVisible)
                .thenReturn(visible);

        QrPayResponse result = service.createQrPay(14L);

        assertEquals("https://qr.alipay.com/wait-test", result.getQrCode());
        verify(alipayClient, org.mockito.Mockito.times(2)).execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class));
    }

    @Test
    void createQrPayIgnoresTradeQueryFailureAfterQrCodeGenerated() throws Exception {
        OrderInfoResponse order = order(15L, "DM1007", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(15L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        AlipayTradePrecreateResponse precreate = mock(AlipayTradePrecreateResponse.class);
        when(precreate.isSuccess()).thenReturn(true);
        when(precreate.getQrCode()).thenReturn("https://qr.alipay.com/fail-open");
        when(alipayClient.execute(any(AlipayTradePrecreateRequest.class))).thenReturn(precreate);
        when(alipayClient.execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class))).thenThrow(new RuntimeException("timeout"));

        QrPayResponse result = service.createQrPay(15L);

        assertEquals("https://qr.alipay.com/fail-open", result.getQrCode());
    }

    @Test
    void createQrPayMapsRuntimeExceptionFromAlipayPrecreate() throws Exception {
        OrderInfoResponse order = order(16L, "DM1008", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(16L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);
        when(alipayClient.execute(any(AlipayTradePrecreateRequest.class))).thenThrow(new RuntimeException("connection reset"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(16L));

        assertEquals("生成支付宝支付二维码失败", error.getMessage());
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).updateById(paymentCaptor.capture());
        assertEquals(PaymentService.STATUS_FAILED, paymentCaptor.getValue().getStatus());
    }

    @Test
    void createPagePayMapsRuntimeExceptionFromAlipayPageExecute() throws Exception {
        OrderInfoResponse order = order(23L, "DM1011", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(23L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);
        when(alipayClient.pageExecute(any(AlipayTradePagePayRequest.class))).thenThrow(new RuntimeException("invalid gateway"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createPagePay(23L));

        assertEquals("生成支付宝支付表单失败", error.getMessage());
        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentMapper).updateById(paymentCaptor.capture());
        assertEquals(PaymentService.STATUS_FAILED, paymentCaptor.getValue().getStatus());
    }

    @Test
    void createPagePayRejectsUnresolvedAlipayPlaceholderBeforePaymentInsert() {
        properties.setAppId("${ALIPAY_APP_ID}");

        BusinessException error = assertThrows(BusinessException.class, () -> service.createPagePay(24L));

        assertEquals("支付宝 appId 未配置", error.getMessage());
        verify(orderClient, never()).getOrder(anyLong(), anyString());
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void createQrPayRetriesTransientRuntimeExceptionFromAlipayPrecreate() throws Exception {
        OrderInfoResponse order = order(17L, "DM1009", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(17L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        AlipayTradePrecreateResponse response = mock(AlipayTradePrecreateResponse.class);
        when(response.isSuccess()).thenReturn(true);
        when(response.getQrCode()).thenReturn("https://qr.alipay.com/retry-success");
        when(alipayClient.execute(any(AlipayTradePrecreateRequest.class)))
                .thenThrow(new RuntimeException("connection reset"))
                .thenReturn(response);

        QrPayResponse result = service.createQrPay(17L);

        assertEquals("https://qr.alipay.com/retry-success", result.getQrCode());
        verify(alipayClient, org.mockito.Mockito.times(2)).execute(any(AlipayTradePrecreateRequest.class));
    }

    @Test
    void createQrPayOpensAlipayChannelCircuitAfterRuntimeFailures() throws Exception {
        OrderInfoResponse order = order(18L, "DM1010", new BigDecimal("280.00"), 1);
        when(orderClient.getOrder(18L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);
        when(alipayClient.execute(any(AlipayTradePrecreateRequest.class))).thenThrow(new RuntimeException("connection reset"));
        DegradeRule rule = new DegradeRule();
        rule.setResource(PaymentSentinelConfig.ALIPAY_CHANNEL);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(0.5);
        rule.setMinRequestAmount(1);
        rule.setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(rule));

        assertThrows(BusinessException.class, () -> service.createQrPay(18L));

        verify(alipayClient, org.mockito.Mockito.times(1)).execute(any(AlipayTradePrecreateRequest.class));
        BusinessException blocked = assertThrows(BusinessException.class, () -> service.createQrPay(18L));
        assertEquals("支付渠道暂不可用，请稍后重试", blocked.getMessage());
    }

    @Test
    void createQrPayOpensOrderClientCircuitAfterRuntimeFailures() {
        when(orderClient.getOrder(19L, "internal-token")).thenThrow(new RuntimeException("timeout"));
        DegradeRule rule = new DegradeRule();
        rule.setResource(PaymentSentinelConfig.ORDER_CLIENT);
        rule.setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        rule.setCount(0.5);
        rule.setMinRequestAmount(1);
        rule.setTimeWindow(10);
        DegradeRuleManager.loadRules(List.of(rule));

        assertThrows(BusinessException.class, () -> service.createQrPay(19L));

        BusinessException blocked = assertThrows(BusinessException.class, () -> service.createQrPay(19L));
        assertEquals("订单服务暂不可用，请稍后重试", blocked.getMessage());
    }

    @Test
    void createQrPayRejectsPaidOrder() {
        OrderInfoResponse order = order(11L, "DM1002", new BigDecimal("380.00"), 2);
        when(orderClient.getOrder(11L, "internal-token")).thenReturn(Result.success(order));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(11L));

        assertEquals("订单已支付", error.getMessage());
        verify(paymentMapper, never()).insert(any());
    }

    @Test
    void syncDecisionBlocksCancelWhenPaymentStatusIsUnknown() throws Exception {
        OrderInfoResponse order = order(12L, "DM1003", new BigDecimal("180.00"), 1);
        when(orderClient.getOrder(12L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        com.alipay.api.response.AlipayTradeQueryResponse response = mock(com.alipay.api.response.AlipayTradeQueryResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(alipayClient.execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class))).thenReturn(response);

        var result = service.syncDecisionForCancel(12L);

        assertEquals(false, result.getPaid());
        assertEquals(false, result.getSafeToCancel());
        assertEquals("支付结果确认中", result.getMessage());
    }

    @Test
    void syncDecisionAllowsCancelWhenTradeDoesNotExist() throws Exception {
        OrderInfoResponse order = order(13L, "DM1004", new BigDecimal("180.00"), 1);
        when(orderClient.getOrder(13L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        com.alipay.api.response.AlipayTradeQueryResponse response = mock(com.alipay.api.response.AlipayTradeQueryResponse.class);
        when(response.isSuccess()).thenReturn(false);
        when(response.getSubCode()).thenReturn("ACQ.TRADE_NOT_EXIST");
        when(alipayClient.execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class))).thenReturn(response);

        var result = service.syncDecisionForCancel(13L);

        assertEquals(false, result.getPaid());
        assertEquals(true, result.getSafeToCancel());
        assertEquals("支付宝未查询到支付交易", result.getMessage());
    }

    @Test
    void syncDecisionTreatsAlipayWrappedRuntimeExceptionAsQueryPending() throws Exception {
        OrderInfoResponse order = order(33L, "DM1012", new BigDecimal("180.00"), 1);
        when(orderClient.getOrder(33L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);

        RuntimeException wrapped = new RuntimeException(
                new AlipayApiException("RSA2签名遭遇异常 content=alipay_sdk=x&biz_content={\"out_trade_no\":\"DM1012\"}"));
        when(alipayClient.execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class))).thenThrow(wrapped);

        var result = service.syncDecisionForCancel(33L);

        assertEquals(false, result.getPaid());
        assertEquals(false, result.getSafeToCancel());
        assertEquals("支付结果确认中", result.getMessage());
    }

    @Test
    void syncByOrderIdPropagatesNonAlipayRuntimeException() throws Exception {
        OrderInfoResponse order = order(34L, "DM1013", new BigDecimal("180.00"), 1);
        when(orderClient.getOrder(34L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);
        when(alipayClient.execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class))).thenThrow(new RuntimeException("db down"));

        RuntimeException error = assertThrows(RuntimeException.class, () -> service.syncByOrderId(34L));

        assertEquals("db down", error.getMessage());
    }

    @Test
    void createQrPayRejectsMissingOrderBeforePaymentInsert() throws Exception {
        when(orderClient.getOrder(20L, "internal-token")).thenReturn(Result.fail(404, "订单不存在"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(20L));
        assertEquals("订单不存在", error.getMessage());
        verify(paymentMapper, never()).insert(any());
        verify(alipayClient, never()).execute(any(AlipayTradePrecreateRequest.class));
    }

    @Test
    void createQrPayRejectsOrderServiceFailureBeforePaymentInsert() throws Exception {
        when(orderClient.getOrder(21L, "internal-token")).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(21L));
        assertEquals("订单不存在", error.getMessage());
        verify(paymentMapper, never()).insert(any());
        verify(alipayClient, never()).execute(any(AlipayTradePrecreateRequest.class));
    }

    @Test
    void createQrPayRejectsNonPendingOrderBeforePaymentInsert() throws Exception {
        OrderInfoResponse order = order(22L, "DM1006", new BigDecimal("180.00"), 3);
        when(orderClient.getOrder(22L, "internal-token")).thenReturn(Result.success(order));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(22L));
        assertEquals("当前订单状态不允许支付", error.getMessage());
        verify(paymentMapper, never()).insert(any());
        verify(alipayClient, never()).execute(any(AlipayTradePrecreateRequest.class));
    }

    @AfterEach
    void tearDown() {
        FlowRuleManager.loadRules(List.of());
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void createQrPayDoesNotCallOrderClientWhenOrderClientResourceBlocked() throws Exception {
        blockResource(PaymentSentinelConfig.ORDER_CLIENT);

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(31L));

        assertEquals("订单服务暂不可用，请稍后重试", error.getMessage());
        verify(orderClient, never()).getOrder(anyLong(), anyString());
        verify(paymentMapper, never()).insert(any());
        verify(alipayClient, never()).execute(any(AlipayTradePrecreateRequest.class));
    }

    @Test
    void syncDecisionReturnsUnknownAndDoesNotCallAlipayWhenChannelResourceBlocked() throws Exception {
        OrderInfoResponse order = order(32L, "DM1010", new BigDecimal("180.00"), 1);
        when(orderClient.getOrder(32L, "internal-token")).thenReturn(Result.success(order));
        when(paymentMapper.selectOne(any())).thenReturn(null);
        blockResource(PaymentSentinelConfig.ALIPAY_CHANNEL);

        var result = service.syncDecisionForCancel(32L);

        assertEquals(false, result.getPaid());
        assertEquals(false, result.getSafeToCancel());
        assertEquals("支付结果确认中", result.getMessage());
        verify(alipayClient, never()).execute(any(com.alipay.api.request.AlipayTradeQueryRequest.class));
    }

    @Test
    void createQrPayMapsOrderServiceExceptionBeforePaymentInsert() throws Exception {
        when(orderClient.getOrder(30L, "internal-token")).thenThrow(new RuntimeException("timeout"));

        BusinessException error = assertThrows(BusinessException.class, () -> service.createQrPay(30L));
        assertEquals("订单服务无响应", error.getMessage());
        verify(paymentMapper, never()).insert(any());
        verify(alipayClient, never()).execute(any(AlipayTradePrecreateRequest.class));
    }

    private void blockResource(String resource) {
        FlowRule rule = new FlowRule(resource);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(0);
        FlowRuleManager.loadRules(List.of(rule));
    }

    private OrderInfoResponse order(Long id, String orderNo, BigDecimal amount, Integer status) {
        OrderInfoResponse order = new OrderInfoResponse();
        order.setId(id);
        order.setOrderNo(orderNo);
        order.setAmount(amount);
        order.setStatus(status);
        return order;
    }
}
