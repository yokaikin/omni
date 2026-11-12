package com.omni.notification.mq;

import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.common.mq.MqProperties;
import com.omni.common.mq.MqRetrySupport;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.common.mq.message.NotificationMessage;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.service.NotificationEventService;
import com.omni.notification.service.NotificationService;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationMessageListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationMessageListener.class);

    private final NotificationService notificationService;
    private final NotificationEventService notificationEventService;
    private final RabbitTemplate rabbitTemplate;
    private final MqProperties mqProperties;
    private final MqMetricsRecorder metricsRecorder;

    public NotificationMessageListener(NotificationService notificationService,
                                       NotificationEventService notificationEventService,
                                       RabbitTemplate rabbitTemplate,
                                       MqProperties mqProperties,
                                       MqMetricsRecorder metricsRecorder) {
        this.notificationService = notificationService;
        this.notificationEventService = notificationEventService;
        this.rabbitTemplate = rabbitTemplate;
        this.mqProperties = mqProperties;
        this.metricsRecorder = metricsRecorder;
    }

    @RabbitListener(queues = MqConstants.Q_NOTIFICATION_SEND)
    public void onNotificationSend(NotificationMessage message, Message rawMessage) {
        metricsRecorder.recordConsumed(MqConstants.Q_NOTIFICATION_SEND);
        log.info("Received notification message for userId: {}, orderId: {}, type: {}",
                message.getUserId(), message.getOrderId(), message.getType());
        try {
            InternalNotificationRequest request = new InternalNotificationRequest();
            request.setUserId(message.getUserId());
            request.setOrderId(message.getOrderId());
            request.setType(message.getType());
            request.setContent(message.getContent());
            request.setActionHref(message.getActionHref());
            request.setActionLabel(message.getActionLabel());
            request.setAggregateKey(message.getAggregateKey());

            notificationService.createInternalMessage(request);
        } catch (Exception e) {
            log.error("Failed to process notification message: {}", e.getMessage(), e);
            if (MqRetrySupport.retryExhausted(rawMessage, MqConstants.Q_NOTIFICATION_SEND_RETRY, mqProperties.getMaxRetryCount())) {
                metricsRecorder.recordDeadLetter(MqConstants.Q_NOTIFICATION_SEND);
                rabbitTemplate.convertAndSend(MqConstants.NOTIFICATION_DLX, MqConstants.RK_NOTIFICATION_SEND_DLQ, message);
                log.warn("通知消息已进入死信队列: userId={}, type={}", message.getUserId(), message.getType());
                return;
            }
            metricsRecorder.recordRetry(MqConstants.Q_NOTIFICATION_SEND);
            throw new AmqpRejectAndDontRequeueException("通知消息处理失败，进入重试队列", e);
        }
    }

    @RabbitListener(queues = MqConstants.Q_NOTIFICATION_EVENT)
    public void onNotificationEvent(NotificationEventMessage message, Message rawMessage) {
        metricsRecorder.recordConsumed(MqConstants.Q_NOTIFICATION_EVENT);
        log.info("收到通知事件: eventId={}, userId={}, eventType={}",
                message.getEventId(), message.getUserId(), message.getEventType());
        try {
            notificationEventService.processEvent(message);
        } catch (Exception e) {
            log.error("通知事件处理失败: eventId={}, userId={}, eventType={}, message={}",
                    message.getEventId(), message.getUserId(), message.getEventType(), e.getMessage(), e);
            if (MqRetrySupport.retryExhausted(rawMessage, MqConstants.Q_NOTIFICATION_EVENT_RETRY, mqProperties.getMaxRetryCount())) {
                metricsRecorder.recordDeadLetter(MqConstants.Q_NOTIFICATION_EVENT);
                rabbitTemplate.convertAndSend(MqConstants.NOTIFICATION_DLX, MqConstants.RK_NOTIFICATION_EVENT_DLQ, message);
                log.warn("通知事件已进入死信队列: eventId={}, userId={}, eventType={}",
                        message.getEventId(), message.getUserId(), message.getEventType());
                return;
            }
            metricsRecorder.recordRetry(MqConstants.Q_NOTIFICATION_EVENT);
            throw new AmqpRejectAndDontRequeueException("通知事件处理失败，进入重试队列", e);
        }
    }
}
