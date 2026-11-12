package com.omni.notification.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.notification.email.EmailSendRequest;
import com.omni.notification.email.EmailSendResult;
import com.omni.notification.entity.NotificationDelivery;
import com.omni.notification.mapper.NotificationDeliveryMapper;
import com.omni.notification.service.NotificationChannelService;
import com.omni.notification.service.NotificationEventService;
import com.omni.notification.sms.SmsSendRequest;
import com.omni.notification.sms.SmsSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 渠道投递重试调度：
 * 扫描 SMS/EMAIL 渠道中 PENDING / FAILED 且未超过最大重试次数的投递记录，重新投递。
 */
@Component
public class NotificationDeliveryRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryRetryScheduler.class);

    private final NotificationDeliveryMapper deliveryMapper;
    private final NotificationChannelService notificationChannelService;
    private final ObjectMapper objectMapper;
    private final int maxRetryAttempts;

    public NotificationDeliveryRetryScheduler(NotificationDeliveryMapper deliveryMapper,
                                              NotificationChannelService notificationChannelService,
                                              @Value("${omni.notification.delivery.retry.max-attempts:3}") int maxRetryAttempts) {
        this(deliveryMapper, notificationChannelService, new ObjectMapper(), maxRetryAttempts);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationDeliveryRetryScheduler(NotificationDeliveryMapper deliveryMapper,
                                              NotificationChannelService notificationChannelService,
                                              ObjectMapper objectMapper,
                                              @Value("${omni.notification.delivery.retry.max-attempts:3}") int maxRetryAttempts) {
        this.deliveryMapper = deliveryMapper;
        this.notificationChannelService = notificationChannelService;
        this.objectMapper = objectMapper;
        this.maxRetryAttempts = Math.max(0, maxRetryAttempts);
    }

    @Scheduled(fixedDelayString = "${omni.notification.delivery.retry.scan-interval-ms:60000}")
    public void retryFailedDeliveries() {
        if (maxRetryAttempts <= 0) {
            return;
        }
        List<NotificationDelivery> candidates = deliveryMapper.selectList(new LambdaQueryWrapper<NotificationDelivery>()
                .in(NotificationDelivery::getStatus, NotificationEventService.STATUS_PENDING, NotificationEventService.STATUS_FAILED)
                .in(NotificationDelivery::getChannel, NotificationEventService.CHANNEL_SMS, NotificationEventService.CHANNEL_EMAIL)
                .lt(NotificationDelivery::getRetryCount, maxRetryAttempts)
                .orderByAsc(NotificationDelivery::getId)
                .last("LIMIT 100"));
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        log.info("开始重试渠道投递: count={}", candidates.size());
        for (NotificationDelivery delivery : candidates) {
            retryOne(delivery);
        }
    }

    private void retryOne(NotificationDelivery delivery) {
        try {
            String status;
            String providerMessageId = null;
            String failureReason = null;
            if (NotificationEventService.CHANNEL_SMS.equals(delivery.getChannel())) {
                SmsSendRequest request = new SmsSendRequest();
                request.setEventId(delivery.getEventId());
                request.setEventType(delivery.getEventType());
                request.setUserId(delivery.getUserId());
                request.setOrderId(delivery.getOrderId());
                request.setActivityId(delivery.getActivityId());
                request.setTemplateCode(delivery.getTemplateCode());
                request.setContent(delivery.getContentSnapshot());
                request.setPayload(readPayload(delivery.getPayloadJson()));
                SmsSendResult result = notificationChannelService.sendSms(request);
                status = result == null ? null : result.getStatus();
                providerMessageId = result == null ? null : result.getProviderMessageId();
                failureReason = result == null ? "短信投递结果为空" : result.getFailureReason();
            } else {
                EmailSendRequest request = new EmailSendRequest();
                request.setEventId(delivery.getEventId());
                request.setEventType(delivery.getEventType());
                request.setUserId(delivery.getUserId());
                request.setOrderId(delivery.getOrderId());
                request.setActivityId(delivery.getActivityId());
                request.setSubject("Omni 通知");
                request.setContent(delivery.getContentSnapshot());
                request.setPayload(readPayload(delivery.getPayloadJson()));
                EmailSendResult result = notificationChannelService.sendEmail(request);
                status = result == null ? null : result.getStatus();
                providerMessageId = result == null ? null : result.getProviderMessageId();
                failureReason = result == null ? "邮件投递结果为空" : result.getFailureReason();
            }
            LocalDateTime now = LocalDateTime.now();
            delivery.setRetryCount((delivery.getRetryCount() == null ? 0 : delivery.getRetryCount()) + 1);
            delivery.setUpdatedTime(now);
            if (NotificationChannelService.STATUS_SENT.equals(status)) {
                delivery.setStatus(NotificationEventService.STATUS_SENT);
                delivery.setSentTime(now);
                delivery.setFailureReason(null);
                log.info("渠道投递重试成功: id={}, channel={}, retryCount={}",
                        delivery.getId(), delivery.getChannel(), delivery.getRetryCount());
            } else if (NotificationChannelService.STATUS_SKIPPED.equals(status)) {
                delivery.setStatus(NotificationEventService.STATUS_SKIPPED);
                delivery.setFailureReason(StringUtils.hasText(failureReason) ? failureReason : "渠道未配置");
                log.info("渠道投递重试跳过: id={}, channel={}, reason={}",
                        delivery.getId(), delivery.getChannel(), failureReason);
            } else {
                delivery.setStatus(NotificationEventService.STATUS_FAILED);
                delivery.setFailureReason(StringUtils.hasText(failureReason) ? failureReason : "未知失败原因");
                log.warn("渠道投递重试失败: id={}, channel={}, retryCount={}, reason={}",
                        delivery.getId(), delivery.getChannel(), delivery.getRetryCount(), failureReason);
            }
            if (StringUtils.hasText(providerMessageId)) {
                delivery.setProviderMessageId(providerMessageId);
            }
            deliveryMapper.updateById(delivery);
        } catch (Exception e) {
            LocalDateTime now = LocalDateTime.now();
            int retryCount = (delivery.getRetryCount() == null ? 0 : delivery.getRetryCount()) + 1;
            delivery.setRetryCount(retryCount);
            delivery.setStatus(NotificationEventService.STATUS_FAILED);
            delivery.setFailureReason(StringUtils.hasText(e.getMessage()) ? e.getMessage() : "渠道投递异常");
            delivery.setUpdatedTime(now);
            deliveryMapper.updateById(delivery);
            log.warn("渠道投递重试异常: id={}, channel={}, error={}",
                    delivery.getId(), delivery.getChannel(), e.getMessage());
        }
    }

    private Map<String, Object> readPayload(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return null;
        }
        try {
            return objectMapper.readValue(payloadJson, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("通知投递载荷解析失败", e);
        }
    }
}
