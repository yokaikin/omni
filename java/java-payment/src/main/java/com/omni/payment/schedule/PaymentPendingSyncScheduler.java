package com.omni.payment.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.payment.config.AlipayProperties;
import com.omni.payment.dto.PaymentStatusResponse;
import com.omni.payment.entity.Payment;
import com.omni.payment.mapper.PaymentMapper;
import com.omni.payment.service.AlipayService;
import com.omni.payment.service.PaymentService;
import com.omni.payment.util.AlipayLogSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待支付流水超时主动查单补偿：
 * 定时扫描超过容忍时长仍未收到回调的支付宝待支付流水，主动向支付宝查单补偿确认，
 * 避免"用户已付款但回调丢失"导致的订单长期挂起。
 */
@Component
public class PaymentPendingSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentPendingSyncScheduler.class);
    private static final String PAYMENT_METHOD_ALIPAY = "ALIPAY";
    private static final int MAX_BATCH_SIZE = 100;

    private final PaymentMapper paymentMapper;
    private final AlipayService alipayService;
    private final AlipayProperties alipayProperties;
    private final MqMetricsRecorder metricsRecorder;
    private final long pendingTimeoutMinutes;
    private final int batchSize;

    public PaymentPendingSyncScheduler(PaymentMapper paymentMapper,
                                       AlipayService alipayService,
                                       AlipayProperties alipayProperties,
                                       MqMetricsRecorder metricsRecorder,
                                       @Value("${omni.payment.pending-sync.timeout-minutes:5}") long pendingTimeoutMinutes,
                                       @Value("${omni.payment.pending-sync.batch-size:20}") int batchSize) {
        this.paymentMapper = paymentMapper;
        this.alipayService = alipayService;
        this.alipayProperties = alipayProperties;
        this.metricsRecorder = metricsRecorder;
        this.pendingTimeoutMinutes = pendingTimeoutMinutes;
        this.batchSize = Math.min(Math.max(batchSize, 1), MAX_BATCH_SIZE);
    }

    @Scheduled(fixedDelayString = "${omni.payment.pending-sync.scan-ms:60000}")
    public void syncTimedOutPendingPayments() {
        if (!isAlipayConfigured()) {
            return;
        }
        List<Payment> timedOutPendings = findTimedOutPendingPayments();
        if (timedOutPendings.isEmpty()) {
            return;
        }
        log.info("待支付流水超时主动查单开始: count={}", timedOutPendings.size());
        int compensated = 0;
        for (Payment payment : timedOutPendings) {
            try {
                PaymentStatusResponse response = alipayService.syncByOrderId(payment.getOrderId());
                if (response != null && PaymentService.STATUS_SUCCESS == response.getPaymentStatus()) {
                    compensated++;
                }
            } catch (Exception e) {
                log.warn("待支付流水超时主动查单失败: orderId={}, paymentId={}, message={}",
                        payment.getOrderId(), payment.getId(), AlipayLogSanitizer.safeMessage(e));
            }
        }
        log.info("待支付流水超时主动查单完成: scanned={}, compensated={}", timedOutPendings.size(), compensated);
    }

    List<Payment> findTimedOutPendingPayments() {
        LambdaQueryWrapper<Payment> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Payment::getStatus, PaymentService.STATUS_PENDING)
                .eq(Payment::getPaymentMethod, PAYMENT_METHOD_ALIPAY)
                .lt(Payment::getCreateTime, LocalDateTime.now().minusMinutes(pendingTimeoutMinutes))
                .orderByAsc(Payment::getId)
                .last("LIMIT " + batchSize);
        return paymentMapper.selectList(wrapper);
    }

    private boolean isAlipayConfigured() {
        return StringUtils.hasText(alipayProperties.getGatewayUrl())
                && !isUnresolvedPlaceholder(alipayProperties.getGatewayUrl());
    }

    private boolean isUnresolvedPlaceholder(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("${") && trimmed.endsWith("}");
    }
}
