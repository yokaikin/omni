package com.omni.payment.schedule;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.payment.config.AlipayProperties;
import com.omni.payment.dto.PaymentStatusResponse;
import com.omni.payment.entity.Payment;
import com.omni.payment.mapper.PaymentMapper;
import com.omni.payment.service.AlipayService;
import com.omni.payment.service.PaymentService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentPendingSyncSchedulerTest {

    private PaymentMapper paymentMapper;
    private AlipayService alipayService;
    private AlipayProperties alipayProperties;
    private MqMetricsRecorder metricsRecorder;
    private PaymentPendingSyncScheduler scheduler;

    @BeforeAll
    static void mybatis() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Payment.class);
    }

    @BeforeEach
    void setUp() {
        paymentMapper = mock(PaymentMapper.class);
        alipayService = mock(AlipayService.class);
        alipayProperties = mock(AlipayProperties.class);
        metricsRecorder = mock(MqMetricsRecorder.class);
        scheduler = new PaymentPendingSyncScheduler(paymentMapper, alipayService, alipayProperties,
                metricsRecorder, 5, 20);
    }

    @Test
    void skipsScanWhenAlipayGatewayMissing() {
        when(alipayProperties.getGatewayUrl()).thenReturn("");

        scheduler.syncTimedOutPendingPayments();

        verify(paymentMapper, never()).selectList(any());
    }

    @Test
    void skipsScanWhenAlipayGatewayIsUnresolvedPlaceholder() {
        when(alipayProperties.getGatewayUrl()).thenReturn("${ALIPAY_GATEWAY_URL:}");

        scheduler.syncTimedOutPendingPayments();

        verify(paymentMapper, never()).selectList(any());
    }

    @Test
    void syncsTimedOutPendingPaymentToAlipay() {
        when(alipayProperties.getGatewayUrl()).thenReturn("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        Payment pending = pendingPayment(3001L);
        when(paymentMapper.selectList(any())).thenReturn(List.of(pending));
        PaymentStatusResponse response = new PaymentStatusResponse();
        response.setPaymentStatus(PaymentService.STATUS_SUCCESS);
        when(alipayService.syncByOrderId(3001L)).thenReturn(response);

        assertDoesNotThrow(() -> scheduler.syncTimedOutPendingPayments());

        verify(alipayService).syncByOrderId(3001L);
    }

    @Test
    void doesNotFailWhenSyncThrows() {
        when(alipayProperties.getGatewayUrl()).thenReturn("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        when(paymentMapper.selectList(any())).thenReturn(List.of(pendingPayment(3002L)));
        when(alipayService.syncByOrderId(3002L)).thenThrow(new RuntimeException("alipay down"));

        assertDoesNotThrow(() -> scheduler.syncTimedOutPendingPayments());
    }

    @Test
    void findTimedOutPendingPaymentsOnlyQueriesAlipayPendingRows() {
        when(alipayProperties.getGatewayUrl()).thenReturn("https://openapi-sandbox.dl.alipaydev.com/gateway.do");
        when(paymentMapper.selectList(any())).thenReturn(List.of());

        List<Payment> result = scheduler.findTimedOutPendingPayments();

        assertTrue(result.isEmpty());
        verify(paymentMapper).selectList(any());
        verify(alipayService, never()).syncByOrderId(anyLong());
    }

    private Payment pendingPayment(Long orderId) {
        Payment payment = new Payment();
        payment.setId(orderId * 10);
        payment.setOrderId(orderId);
        payment.setPaymentMethod("ALIPAY");
        payment.setStatus(PaymentService.STATUS_PENDING);
        payment.setCreateTime(LocalDateTime.now().minusMinutes(10));
        return payment;
    }
}
