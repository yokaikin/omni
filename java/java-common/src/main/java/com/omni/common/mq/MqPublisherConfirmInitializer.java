package com.omni.common.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

/**
 * 生产端可靠性兜底：注册 broker confirm 与不可路由退回回调。
 * 需配合 spring.rabbitmq.publisher-confirm-type=correlated、publisher-returns=true、template.mandatory=true。
 */
@Component
@ConditionalOnProperty(prefix = "omni.mq", name = "enabled", havingValue = "true")
public class MqPublisherConfirmInitializer {

    private static final Logger log = LoggerFactory.getLogger(MqPublisherConfirmInitializer.class);

    private final RabbitTemplate rabbitTemplate;

    public MqPublisherConfirmInitializer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("MQ 消息 broker 确认失败: correlationData={}, cause={}", correlationData, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> log.error(
                "MQ 消息不可路由已被退回: exchange={}, routingKey={}, replyText={}, message={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyText(),
                returned.getMessage() != null ? new String(returned.getMessage().getBody()) : null));
    }
}
