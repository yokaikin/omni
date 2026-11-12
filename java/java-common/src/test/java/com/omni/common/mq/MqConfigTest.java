package com.omni.common.mq;

import com.omni.common.mq.message.ActivitySearchIndexMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MqConfigTest {

    private final MqProperties mqProperties = new MqProperties();
    private final MqConfig config = new MqConfig(mqProperties);

    @Test
    void notificationQueueDeadLettersToRetryQueue() {
        Queue queue = config.notificationSendQueue();

        assertEquals(MqConstants.NOTIFICATION_RETRY_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(MqConstants.RK_NOTIFICATION_SEND_RETRY, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void declaresNotificationEventExchangeAndQueue() {
        assertEquals("omni.notification", MqConstants.NOTIFICATION_EXCHANGE);
        assertEquals("notification.event", MqConstants.RK_NOTIFICATION_EVENT);
        assertEquals("notification.event.retry", MqConstants.RK_NOTIFICATION_EVENT_RETRY);
        assertEquals("notification.event.dlq", MqConstants.RK_NOTIFICATION_EVENT_DLQ);
        assertEquals("notification.event.queue", MqConstants.Q_NOTIFICATION_EVENT);
        assertEquals("notification.event.retry.queue", MqConstants.Q_NOTIFICATION_EVENT_RETRY);
        assertEquals("notification.event.dlq", MqConstants.Q_NOTIFICATION_EVENT_DLQ);
    }

    @Test
    void notificationEventQueueDeadLettersToRetryQueue() {
        Queue queue = config.notificationEventQueue();

        assertEquals(MqConstants.Q_NOTIFICATION_EVENT, queue.getName());
        assertEquals(MqConstants.NOTIFICATION_RETRY_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(MqConstants.RK_NOTIFICATION_EVENT_RETRY, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void notificationEventRetryQueueReturnsToMainExchangeAfterDelay() {
        Queue queue = config.notificationEventRetryQueue();

        assertEquals(10000L, queue.getArguments().get("x-message-ttl"));
        assertEquals(MqConstants.NOTIFICATION_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(MqConstants.RK_NOTIFICATION_EVENT, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void notificationEventDeadLetterQueueIsDurable() {
        Queue queue = config.notificationEventDeadLetterQueue();

        assertEquals(MqConstants.Q_NOTIFICATION_EVENT_DLQ, queue.getName());
        assertEquals(true, queue.isDurable());
    }

    @Test
    void waitlistReleasedRetryQueueReturnsToMainExchangeAfterDelay() {
        Queue queue = config.waitlistReleasedRetryQueue();

        assertEquals(10000L, queue.getArguments().get("x-message-ttl"));
        assertEquals(MqConstants.WAITLIST_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(MqConstants.RK_WAITLIST_RELEASED, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void waitlistOrderPaidDeadLetterQueueIsDurable() {
        Queue queue = config.waitlistOrderPaidDeadLetterQueue();

        assertEquals(MqConstants.Q_WAITLIST_ORDER_PAID_DLQ, queue.getName());
        assertEquals(true, queue.isDurable());
    }

    @Test
    void declaresSearchIndexExchangeAndQueue() {
        assertEquals("omni.search-index", MqConstants.SEARCH_INDEX_EXCHANGE);
        assertEquals("omni.search-index.retry", MqConstants.SEARCH_INDEX_RETRY_EXCHANGE);
        assertEquals("omni.search-index.dlx", MqConstants.SEARCH_INDEX_DLX);
        assertEquals("search.activity.changed", MqConstants.RK_SEARCH_ACTIVITY_CHANGED);
        assertEquals("search.activity.changed.retry", MqConstants.RK_SEARCH_ACTIVITY_CHANGED_RETRY);
        assertEquals("search.activity.changed.dlq", MqConstants.RK_SEARCH_ACTIVITY_CHANGED_DLQ);
        assertEquals("search.activity.changed.queue", MqConstants.Q_SEARCH_ACTIVITY_CHANGED);
        assertEquals("search.activity.changed.retry.queue", MqConstants.Q_SEARCH_ACTIVITY_CHANGED_RETRY);
        assertEquals("search.activity.changed.dlq", MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ);
    }

    @Test
    void searchActivityChangedQueueDeadLettersToRetryQueue() {
        Queue queue = config.searchActivityChangedQueue();

        assertEquals(MqConstants.Q_SEARCH_ACTIVITY_CHANGED, queue.getName());
        assertEquals(MqConstants.SEARCH_INDEX_RETRY_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(MqConstants.RK_SEARCH_ACTIVITY_CHANGED_RETRY, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void searchActivityChangedRetryQueueReturnsToMainExchangeAfterDelay() {
        Queue queue = config.searchActivityChangedRetryQueue();

        assertEquals(10000L, queue.getArguments().get("x-message-ttl"));
        assertEquals(MqConstants.SEARCH_INDEX_EXCHANGE, queue.getArguments().get("x-dead-letter-exchange"));
        assertEquals(MqConstants.RK_SEARCH_ACTIVITY_CHANGED, queue.getArguments().get("x-dead-letter-routing-key"));
    }

    @Test
    void searchActivityChangedDeadLetterQueueIsDurable() {
        Queue queue = config.searchActivityChangedDeadLetterQueue();

        assertEquals(MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ, queue.getName());
        assertEquals(true, queue.isDurable());
    }

    @Test
    void messageConverterSerializesActivitySearchIndexJavaTimeMessage() {
        MessageConverter converter = config.jackson2JsonMessageConverter();

        Message message = converter.toMessage(ActivitySearchIndexMessage.upsert(900001L), new MessageProperties());

        assertTrue(message.getBody().length > 0);
    }
}
