package com.omni.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.entity.NotificationDelivery;
import com.omni.notification.mapper.NotificationDeliveryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class NotificationEventService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String CHANNEL_IN_APP = "IN_APP";
    public static final String CHANNEL_SMS = "SMS";
    public static final String CHANNEL_EMAIL = "EMAIL";

    private final NotificationDeliveryMapper deliveryMapper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @Autowired
    public NotificationEventService(NotificationDeliveryMapper deliveryMapper,
                                    NotificationService notificationService) {
        this(deliveryMapper, notificationService, new ObjectMapper());
    }

    NotificationEventService(NotificationDeliveryMapper deliveryMapper,
                             NotificationService notificationService,
                             ObjectMapper objectMapper) {
        this.deliveryMapper = deliveryMapper;
        this.notificationService = notificationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void processEvent(NotificationEventMessage message) {
        validate(message);
        for (String channel : message.effectiveChannels()) {
            if (findExistingDelivery(message.getEventId(), channel) != null) {
                continue;
            }
            if (CHANNEL_IN_APP.equals(channel)) {
                createInAppNotification(message);
                deliveryMapper.insert(buildDelivery(message, channel, STATUS_SENT, LocalDateTime.now()));
            } else if (CHANNEL_SMS.equals(channel) || CHANNEL_EMAIL.equals(channel)) {
                deliveryMapper.insert(buildDelivery(message, channel, STATUS_PENDING, null));
            } else {
                deliveryMapper.insert(buildDelivery(message, channel, STATUS_PENDING, null));
            }
        }
    }

    private void validate(NotificationEventMessage message) {
        if (message == null
                || !StringUtils.hasText(message.getEventId())
                || !StringUtils.hasText(message.getEventType())
                || message.getUserId() == null
                || !StringUtils.hasText(message.getContent())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知事件参数不能为空");
        }
    }

    private NotificationDelivery findExistingDelivery(String eventId, String channel) {
        return deliveryMapper.selectOne(new LambdaQueryWrapper<NotificationDelivery>()
                .eq(NotificationDelivery::getEventId, eventId)
                .eq(NotificationDelivery::getChannel, channel)
                .last("LIMIT 1"));
    }

    private void createInAppNotification(NotificationEventMessage message) {
        InternalNotificationRequest request = new InternalNotificationRequest();
        request.setUserId(message.getUserId());
        request.setOrderId(message.getOrderId());
        request.setType(message.getEventType());
        request.setContent(message.getContent());
        request.setActionHref(message.getActionHref());
        request.setActionLabel(message.getActionLabel());
        request.setAggregateKey(message.getAggregateKey());
        notificationService.createInternalMessage(request);
    }

    private NotificationDelivery buildDelivery(NotificationEventMessage message,
                                               String channel,
                                               String status,
                                               LocalDateTime sentTime) {
        LocalDateTime now = LocalDateTime.now();
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setEventId(message.getEventId().trim());
        delivery.setEventType(message.getEventType().trim());
        delivery.setUserId(message.getUserId());
        delivery.setOrderId(message.getOrderId());
        delivery.setActivityId(message.getActivityId());
        delivery.setChannel(channel);
        delivery.setStatus(status);
        delivery.setRetryCount(0);
        delivery.setTemplateCode(trimToNull(message.getTemplateCode()));
        delivery.setContentSnapshot(message.getContent().trim());
        delivery.setPayloadJson(toPayloadJson(message.getPayload()));
        delivery.setCreatedTime(now);
        delivery.setUpdatedTime(now);
        delivery.setSentTime(sentTime);
        return delivery;
    }

    private String toPayloadJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知事件参数格式不正确");
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
