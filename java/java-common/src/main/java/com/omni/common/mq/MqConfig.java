package com.omni.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ exchange, queue, binding declarations and JSON converter.
 */
@Configuration
@ConditionalOnProperty(prefix = "omni.mq", name = "enabled", havingValue = "true")
public class MqConfig {

    private final MqProperties mqProperties;

    public MqConfig(MqProperties mqProperties) {
        this.mqProperties = mqProperties;
    }

    // JSON serialization

    @Bean
    public MessageConverter jackson2JsonMessageConverter() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    // Notification

    @Bean
    public TopicExchange notificationExchange() {
        return new TopicExchange(MqConstants.NOTIFICATION_EXCHANGE);
    }

    @Bean
    public TopicExchange notificationRetryExchange() {
        return new TopicExchange(MqConstants.NOTIFICATION_RETRY_EXCHANGE);
    }

    @Bean
    public TopicExchange notificationDeadLetterExchange() {
        return new TopicExchange(MqConstants.NOTIFICATION_DLX);
    }

    @Bean
    public Queue notificationSendQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFICATION_SEND)
                .withArgument("x-dead-letter-exchange", MqConstants.NOTIFICATION_RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_NOTIFICATION_SEND_RETRY)
                .build();
    }

    @Bean
    public Binding notificationSendBinding() {
        return BindingBuilder.bind(notificationSendQueue())
                .to(notificationExchange())
                .with(MqConstants.RK_NOTIFICATION_SEND);
    }

    @Bean
    public Queue notificationSendRetryQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFICATION_SEND_RETRY)
                .withArgument("x-message-ttl", mqProperties.getRetryTtlMillis())
                .withArgument("x-dead-letter-exchange", MqConstants.NOTIFICATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_NOTIFICATION_SEND)
                .build();
    }

    @Bean
    public Binding notificationSendRetryBinding() {
        return BindingBuilder.bind(notificationSendRetryQueue())
                .to(notificationRetryExchange())
                .with(MqConstants.RK_NOTIFICATION_SEND_RETRY);
    }

    @Bean
    public Queue notificationSendDeadLetterQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFICATION_SEND_DLQ).build();
    }

    @Bean
    public Binding notificationSendDeadLetterBinding() {
        return BindingBuilder.bind(notificationSendDeadLetterQueue())
                .to(notificationDeadLetterExchange())
                .with(MqConstants.RK_NOTIFICATION_SEND_DLQ);
    }

    @Bean
    public Queue notificationEventQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFICATION_EVENT)
                .withArgument("x-dead-letter-exchange", MqConstants.NOTIFICATION_RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_NOTIFICATION_EVENT_RETRY)
                .build();
    }

    @Bean
    public Binding notificationEventBinding() {
        return BindingBuilder.bind(notificationEventQueue())
                .to(notificationExchange())
                .with(MqConstants.RK_NOTIFICATION_EVENT);
    }

    @Bean
    public Queue notificationEventRetryQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFICATION_EVENT_RETRY)
                .withArgument("x-message-ttl", mqProperties.getRetryTtlMillis())
                .withArgument("x-dead-letter-exchange", MqConstants.NOTIFICATION_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_NOTIFICATION_EVENT)
                .build();
    }

    @Bean
    public Binding notificationEventRetryBinding() {
        return BindingBuilder.bind(notificationEventRetryQueue())
                .to(notificationRetryExchange())
                .with(MqConstants.RK_NOTIFICATION_EVENT_RETRY);
    }

    @Bean
    public Queue notificationEventDeadLetterQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFICATION_EVENT_DLQ).build();
    }

    @Bean
    public Binding notificationEventDeadLetterBinding() {
        return BindingBuilder.bind(notificationEventDeadLetterQueue())
                .to(notificationDeadLetterExchange())
                .with(MqConstants.RK_NOTIFICATION_EVENT_DLQ);
    }

    // Waitlist

    @Bean
    public TopicExchange waitlistExchange() {
        return new TopicExchange(MqConstants.WAITLIST_EXCHANGE);
    }

    @Bean
    public TopicExchange waitlistRetryExchange() {
        return new TopicExchange(MqConstants.WAITLIST_RETRY_EXCHANGE);
    }

    @Bean
    public TopicExchange waitlistDeadLetterExchange() {
        return new TopicExchange(MqConstants.WAITLIST_DLX);
    }

    @Bean
    public Queue waitlistReleasedQueue() {
        return QueueBuilder.durable(MqConstants.Q_WAITLIST_RELEASED)
                .withArgument("x-dead-letter-exchange", MqConstants.WAITLIST_RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_WAITLIST_RELEASED_RETRY)
                .build();
    }

    @Bean
    public Binding waitlistReleasedBinding() {
        return BindingBuilder.bind(waitlistReleasedQueue())
                .to(waitlistExchange())
                .with(MqConstants.RK_WAITLIST_RELEASED);
    }

    @Bean
    public Queue waitlistReleasedRetryQueue() {
        return QueueBuilder.durable(MqConstants.Q_WAITLIST_RELEASED_RETRY)
                .withArgument("x-message-ttl", mqProperties.getRetryTtlMillis())
                .withArgument("x-dead-letter-exchange", MqConstants.WAITLIST_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_WAITLIST_RELEASED)
                .build();
    }

    @Bean
    public Binding waitlistReleasedRetryBinding() {
        return BindingBuilder.bind(waitlistReleasedRetryQueue())
                .to(waitlistRetryExchange())
                .with(MqConstants.RK_WAITLIST_RELEASED_RETRY);
    }

    @Bean
    public Queue waitlistReleasedDeadLetterQueue() {
        return QueueBuilder.durable(MqConstants.Q_WAITLIST_RELEASED_DLQ).build();
    }

    @Bean
    public Binding waitlistReleasedDeadLetterBinding() {
        return BindingBuilder.bind(waitlistReleasedDeadLetterQueue())
                .to(waitlistDeadLetterExchange())
                .with(MqConstants.RK_WAITLIST_RELEASED_DLQ);
    }

    @Bean
    public Queue waitlistOrderPaidQueue() {
        return QueueBuilder.durable(MqConstants.Q_WAITLIST_ORDER_PAID)
                .withArgument("x-dead-letter-exchange", MqConstants.WAITLIST_RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_WAITLIST_ORDER_PAID_RETRY)
                .build();
    }

    @Bean
    public Binding waitlistOrderPaidBinding() {
        return BindingBuilder.bind(waitlistOrderPaidQueue())
                .to(waitlistExchange())
                .with(MqConstants.RK_WAITLIST_ORDER_PAID);
    }

    @Bean
    public Queue waitlistOrderPaidRetryQueue() {
        return QueueBuilder.durable(MqConstants.Q_WAITLIST_ORDER_PAID_RETRY)
                .withArgument("x-message-ttl", mqProperties.getRetryTtlMillis())
                .withArgument("x-dead-letter-exchange", MqConstants.WAITLIST_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_WAITLIST_ORDER_PAID)
                .build();
    }

    @Bean
    public Binding waitlistOrderPaidRetryBinding() {
        return BindingBuilder.bind(waitlistOrderPaidRetryQueue())
                .to(waitlistRetryExchange())
                .with(MqConstants.RK_WAITLIST_ORDER_PAID_RETRY);
    }

    @Bean
    public Queue waitlistOrderPaidDeadLetterQueue() {
        return QueueBuilder.durable(MqConstants.Q_WAITLIST_ORDER_PAID_DLQ).build();
    }

    @Bean
    public Binding waitlistOrderPaidDeadLetterBinding() {
        return BindingBuilder.bind(waitlistOrderPaidDeadLetterQueue())
                .to(waitlistDeadLetterExchange())
                .with(MqConstants.RK_WAITLIST_ORDER_PAID_DLQ);
    }

    // Search index

    @Bean
    public TopicExchange searchIndexExchange() {
        return new TopicExchange(MqConstants.SEARCH_INDEX_EXCHANGE);
    }

    @Bean
    public TopicExchange searchIndexRetryExchange() {
        return new TopicExchange(MqConstants.SEARCH_INDEX_RETRY_EXCHANGE);
    }

    @Bean
    public TopicExchange searchIndexDeadLetterExchange() {
        return new TopicExchange(MqConstants.SEARCH_INDEX_DLX);
    }

    @Bean
    public Queue searchActivityChangedQueue() {
        return QueueBuilder.durable(MqConstants.Q_SEARCH_ACTIVITY_CHANGED)
                .withArgument("x-dead-letter-exchange", MqConstants.SEARCH_INDEX_RETRY_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_SEARCH_ACTIVITY_CHANGED_RETRY)
                .build();
    }

    @Bean
    public Binding searchActivityChangedBinding() {
        return BindingBuilder.bind(searchActivityChangedQueue())
                .to(searchIndexExchange())
                .with(MqConstants.RK_SEARCH_ACTIVITY_CHANGED);
    }

    @Bean
    public Queue searchActivityChangedRetryQueue() {
        return QueueBuilder.durable(MqConstants.Q_SEARCH_ACTIVITY_CHANGED_RETRY)
                .withArgument("x-message-ttl", mqProperties.getRetryTtlMillis())
                .withArgument("x-dead-letter-exchange", MqConstants.SEARCH_INDEX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.RK_SEARCH_ACTIVITY_CHANGED)
                .build();
    }

    @Bean
    public Binding searchActivityChangedRetryBinding() {
        return BindingBuilder.bind(searchActivityChangedRetryQueue())
                .to(searchIndexRetryExchange())
                .with(MqConstants.RK_SEARCH_ACTIVITY_CHANGED_RETRY);
    }

    @Bean
    public Queue searchActivityChangedDeadLetterQueue() {
        return QueueBuilder.durable(MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ).build();
    }

    @Bean
    public Binding searchActivityChangedDeadLetterBinding() {
        return BindingBuilder.bind(searchActivityChangedDeadLetterQueue())
                .to(searchIndexDeadLetterExchange())
                .with(MqConstants.RK_SEARCH_ACTIVITY_CHANGED_DLQ);
    }
}
