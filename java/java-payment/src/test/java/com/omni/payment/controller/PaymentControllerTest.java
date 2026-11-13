package com.omni.payment.controller;

import com.omni.common.result.Result;
import com.omni.exception.BusinessException;
import com.omni.payment.dto.AlipayCallbackAcceptanceResponse;
import com.omni.payment.client.OrderClient;
import com.omni.payment.dto.OrderInfoResponse;
import com.omni.payment.entity.Payment;
import com.omni.payment.service.MockPaymentService;
import com.omni.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentControllerTest {

    private static final String JWT_SECRET = "payment-controller-test-secret-32-bytes-minimum";

    @Test
    void legacyPayEndpointGuidesUsersToAlipayPagePayOnly() {
        PaymentService paymentService = mock(PaymentService.class);
        MockPaymentService mockPaymentService = mock(MockPaymentService.class);
        PaymentController controller = new PaymentController(paymentService, mockPaymentService);

        Result<Void> result = controller.mockPay(null);

        assertEquals(400, result.getCode());
        assertEquals("请使用支付宝支付页面支付", result.getMessage());
        verify(mockPaymentService, never()).pay(any(), any());
    }

    @Test
    void mockPayForDemoIsDisabledByDefaultBeforeRequestValidation() {
        PaymentService paymentService = mock(PaymentService.class);
        MockPaymentService mockPaymentService = mock(MockPaymentService.class);
        PaymentController controller = new PaymentController(paymentService, mockPaymentService);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller.mockPayForDemo(null, null)
        );

        assertEquals("当前环境未启用本地支付确认", exception.getMessage());
        verify(mockPaymentService, never()).pay(any(), any());
    }

    @Test
    void mockPayForDemoUsesExistingValidationWhenExplicitlyEnabled() {
        PaymentService paymentService = mock(PaymentService.class);
        MockPaymentService mockPaymentService = mock(MockPaymentService.class);
        PaymentController controller = new PaymentController(paymentService, mockPaymentService);
        ReflectionTestUtils.setField(controller, "mockPaymentEnabled", true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller.mockPayForDemo(null, null)
        );

        assertEquals("支付参数不能为空", exception.getMessage());
        verify(mockPaymentService, never()).pay(any(), any());
    }

    @Test
    void paymentRecordRejectsUnauthenticatedRequestBeforeReadingPayment() {
        PaymentService paymentService = mock(PaymentService.class);
        PaymentController controller = new PaymentController(paymentService, mock(MockPaymentService.class));

        Result<?> result = controller.getPaymentRecord(88L, null);

        assertEquals(401, result.getCode());
        verify(paymentService, never()).getPaymentByOrderId(any());
    }

    @Test
    void paymentRecordOnlyReturnsSafeFieldsToOrderOwner() throws Exception {
        System.setProperty("JWT_SECRET", JWT_SECRET);
        PaymentService paymentService = mock(PaymentService.class);
        OrderClient orderClient = mock(OrderClient.class);
        PaymentController controller = new PaymentController(
                paymentService, mock(MockPaymentService.class), orderClient, "internal-token", JWT_SECRET);
        OrderInfoResponse order = new OrderInfoResponse();
        order.setId(88L);
        order.setUserId(2004L);
        when(orderClient.getOrder(88L, "internal-token")).thenReturn(Result.success(order));
        Payment payment = new Payment();
        payment.setOrderId(88L);
        payment.setStatus(PaymentService.STATUS_SUCCESS);
        payment.setRawNotify("private callback payload");
        when(paymentService.getPaymentByOrderId(88L)).thenReturn(payment);

        Result<?> result = controller.getPaymentRecord(88L, bearer(2004L));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(result.getData());

        assertEquals(200, result.getCode());
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("private callback payload"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("rawNotify"));
        verify(paymentService).getPaymentByOrderId(88L);
    }

    @Test
    void paymentRecordRejectsAnotherUsersOrder() {
        System.setProperty("JWT_SECRET", JWT_SECRET);
        PaymentService paymentService = mock(PaymentService.class);
        OrderClient orderClient = mock(OrderClient.class);
        PaymentController controller = new PaymentController(
                paymentService, mock(MockPaymentService.class), orderClient, "internal-token", JWT_SECRET);
        OrderInfoResponse order = new OrderInfoResponse();
        order.setId(88L);
        order.setUserId(9999L);
        when(orderClient.getOrder(88L, "internal-token")).thenReturn(Result.success(order));

        Result<?> result = controller.getPaymentRecord(88L, bearer(2004L));

        assertEquals(403, result.getCode());
        verify(paymentService, never()).getPaymentByOrderId(any());
    }

    @Test
    void alipayAcceptanceStatusRequiresInternalToken() {
        PaymentService paymentService = mock(PaymentService.class);
        PaymentController controller = new PaymentController(paymentService, mock(MockPaymentService.class));

        Result<AlipayCallbackAcceptanceResponse> result = controller.getAlipayNotifyStatus(88L, "wrong-token");

        assertEquals(403, result.getCode());
        verify(paymentService, never()).getPaymentByOrderId(any());
    }

    @Test
    void alipayAcceptanceStatusReportsSignedNotificationWithoutRawPayload() throws Exception {
        PaymentService paymentService = mock(PaymentService.class);
        PaymentController controller = new PaymentController(
                paymentService, mock(MockPaymentService.class), null, "internal-token", JWT_SECRET);
        Payment payment = new Payment();
        payment.setOrderId(88L);
        payment.setStatus(PaymentService.STATUS_SUCCESS);
        payment.setTradeNo("trade-123");
        payment.setNotifyTime(java.time.LocalDateTime.parse("2026-11-12T12:00:00"));
        payment.setRawNotify("{\"notify_id\":\"notify-1\",\"sign\":\"signature\"}");
        when(paymentService.getPaymentByOrderId(88L)).thenReturn(payment);

        Result<AlipayCallbackAcceptanceResponse> result = controller.getAlipayNotifyStatus(88L, "internal-token");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(result.getData());

        assertEquals(200, result.getCode());
        assertEquals(true, result.getData().isSignedNotificationReceived());
        assertEquals("trade-123", result.getData().getTradeNo());
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("signature"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("rawNotify"));
    }

    private static String bearer(Long userId) {
        Key key = Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
        return "Bearer " + Jwts.builder()
                .claim("userId", userId)
                .setSubject(String.valueOf(userId))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }
}
