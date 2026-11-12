package com.omni.ticket.search;

import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.common.mq.MqProperties;
import com.omni.common.mq.message.ActivitySearchIndexMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ActivitySearchIndexEventListenerTest {

    private final ActivitySearchIndexService indexService = mock(ActivitySearchIndexService.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final MqProperties mqProperties = new MqProperties();
    private final MqMetricsRecorder metricsRecorder = mock(MqMetricsRecorder.class);
    private final ActivitySearchIndexEventListener listener =
            new ActivitySearchIndexEventListener(indexService, rabbitTemplate, mqProperties, metricsRecorder);

    @Test
    void upsertMessageCallsIndexService() {
        ActivitySearchIndexMessage message = ActivitySearchIndexMessage.upsert(900001L);

        listener.onActivityChanged(message, new Message(new byte[0], new MessageProperties()));

        verify(indexService).upsertActivity(900001L);
    }

    @Test
    void deleteMessageCallsIndexService() {
        ActivitySearchIndexMessage message = ActivitySearchIndexMessage.delete(900001L);

        listener.onActivityChanged(message, new Message(new byte[0], new MessageProperties()));

        verify(indexService).deleteActivity(900001L);
    }

    @Test
    void processingFailureRejectsForRetry() {
        ActivitySearchIndexMessage message = ActivitySearchIndexMessage.upsert(900001L);
        doThrow(new RuntimeException("es down")).when(indexService).upsertActivity(900001L);

        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> listener.onActivityChanged(message, new Message(new byte[0], new MessageProperties())));
    }

    @Test
    void processingFailureAfterRetryLimitMovesToDeadLetterQueue() {
        ActivitySearchIndexMessage message = ActivitySearchIndexMessage.upsert(900001L);
        doThrow(new RuntimeException("es down")).when(indexService).upsertActivity(900001L);
        Message rawMessage = retryLimitReachedMessage();

        assertDoesNotThrow(() -> listener.onActivityChanged(message, rawMessage));

        verify(rabbitTemplate).convertAndSend(
                eq(MqConstants.SEARCH_INDEX_DLX),
                eq(MqConstants.RK_SEARCH_ACTIVITY_CHANGED_DLQ),
                eq(message));
    }

    private Message retryLimitReachedMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setHeader("x-death", List.of(Map.of(
                "queue", MqConstants.Q_SEARCH_ACTIVITY_CHANGED_RETRY,
                "count", 3L
        )));
        return new Message(new byte[0], properties);
    }
}
