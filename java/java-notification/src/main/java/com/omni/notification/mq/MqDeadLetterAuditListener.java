package com.omni.notification.mq;

import com.omni.common.mq.MqConstants;
import com.omni.notification.service.MqDeadLetterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列审计消费者：消费 notification 域死信并落库，供 internal API 查询与重放。
 */
@Component
public class MqDeadLetterAuditListener {

    private static final Logger log = LoggerFactory.getLogger(MqDeadLetterAuditListener.class);

    private final MqDeadLetterService mqDeadLetterService;

    public MqDeadLetterAuditListener(MqDeadLetterService mqDeadLetterService) {
        this.mqDeadLetterService = mqDeadLetterService;
    }

    @RabbitListener(queues = MqConstants.Q_NOTIFICATION_SEND_DLQ)
    public void onSendDeadLetter(Message rawMessage) {
        audit(MqConstants.Q_NOTIFICATION_SEND, MqConstants.Q_NOTIFICATION_SEND_DLQ, rawMessage);
    }

    @RabbitListener(queues = MqConstants.Q_NOTIFICATION_EVENT_DLQ)
    public void onEventDeadLetter(Message rawMessage) {
        audit(MqConstants.Q_NOTIFICATION_EVENT, MqConstants.Q_NOTIFICATION_EVENT_DLQ, rawMessage);
    }

    private void audit(String sourceQueue, String deadLetterQueue, Message rawMessage) {
        try {
            mqDeadLetterService.record(sourceQueue, deadLetterQueue, rawMessage);
        } catch (Exception e) {
            log.error("死信审计落库失败: sourceQueue={}, deadLetterQueue={}", sourceQueue, deadLetterQueue, e);
        }
    }
}
