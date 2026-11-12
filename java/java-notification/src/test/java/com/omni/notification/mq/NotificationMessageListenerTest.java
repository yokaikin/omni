package com.omni.notification.mq;

import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.common.mq.MqProperties;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.notification.service.NotificationEventService;
import com.omni.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationMessageListenerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventService notificationEventService = mock(NotificationEventService.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MqProperties mqProperties = new MqProperties();
    private final MqMetricsRecorder metricsRecorder = mock(MqMetricsRecorder.class);
    private final NotificationMessageListener listener =
            new NotificationMessageListener(notificationService, notificationEventService, rabbitTemplate, mqProperties, metricsRecorder);

    @Test
    void onNotificationEventDelegatesToEventService() {
        NotificationEventMessage message = eventMessage();

        listener.onNotificationEvent(message, null);

        verify(notificationEventService).processEvent(message);
    }

    @Test
    void onNotificationEventRejectsToRetryQueueBeforeRetryLimit() {
        NotificationEventMessage message = eventMessage();
        doThrow(new RuntimeException("db down")).when(notificationEventService).processEvent(message);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> listener.onNotificationEvent(message, rawMessageWithRetryCount(1)));
    }

    @Test
    void onNotificationEventMovesToDeadLetterQueueAfterRetryLimit() {
        NotificationEventMessage message = eventMessage();
        doThrow(new RuntimeException("db down")).when(notificationEventService).processEvent(message);

        assertDoesNotThrow(() -> listener.onNotificationEvent(message, rawMessageWithRetryCount(3)));

        verify(rabbitTemplate).convertAndSend(
                eq(MqConstants.NOTIFICATION_DLX),
                eq(MqConstants.RK_NOTIFICATION_EVENT_DLQ),
                eq(message));
    }

    private static NotificationEventMessage eventMessage() {
        NotificationEventMessage message = new NotificationEventMessage();
        message.setEventId("support-reply:99:1");
        message.setEventType("SUPPORT_REPLY");
        message.setUserId(2004L);
        message.setContent("人工客服回复了你的咨询，请查看客服会话。");
        return message;
    }

    private static Message rawMessageWithRetryCount(long count) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-death", List.of(Map.of(
                "queue", MqConstants.Q_NOTIFICATION_EVENT_RETRY,
                "count", count)));
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
