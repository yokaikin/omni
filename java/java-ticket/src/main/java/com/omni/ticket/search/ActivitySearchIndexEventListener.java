package com.omni.ticket.search;

import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.common.mq.MqProperties;
import com.omni.common.mq.MqRetrySupport;
import com.omni.common.mq.message.ActivitySearchIndexMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "omni.search", name = "provider", havingValue = "elasticsearch")
public class ActivitySearchIndexEventListener {

    private static final Logger log = LoggerFactory.getLogger(ActivitySearchIndexEventListener.class);

    private final ActivitySearchIndexService indexService;
    private final RabbitTemplate rabbitTemplate;
    private final MqProperties mqProperties;
    private final MqMetricsRecorder metricsRecorder;

    public ActivitySearchIndexEventListener(ActivitySearchIndexService indexService,
                                            RabbitTemplate rabbitTemplate,
                                            MqProperties mqProperties,
                                            MqMetricsRecorder metricsRecorder) {
        this.indexService = indexService;
        this.rabbitTemplate = rabbitTemplate;
        this.mqProperties = mqProperties;
        this.metricsRecorder = metricsRecorder;
    }

    @RabbitListener(queues = MqConstants.Q_SEARCH_ACTIVITY_CHANGED)
    public void onActivityChanged(ActivitySearchIndexMessage message, Message rawMessage) {
        if (message == null || message.getActivityId() == null) {
            log.warn("活动搜索索引消息为空或缺少活动ID");
            return;
        }
        metricsRecorder.recordConsumed(MqConstants.Q_SEARCH_ACTIVITY_CHANGED);
        try {
            if (ActivitySearchIndexMessage.EVENT_TYPE_DELETE.equals(message.getEventType())) {
                indexService.deleteActivity(message.getActivityId());
            } else {
                indexService.upsertActivity(message.getActivityId());
            }
        } catch (Exception e) {
            log.error("活动搜索索引消息处理失败: activityId={}, eventType={}, message={}",
                    message.getActivityId(), message.getEventType(), e.getMessage(), e);
            if (MqRetrySupport.retryExhausted(rawMessage, MqConstants.Q_SEARCH_ACTIVITY_CHANGED_RETRY, mqProperties.getMaxRetryCount())) {
                metricsRecorder.recordDeadLetter(MqConstants.Q_SEARCH_ACTIVITY_CHANGED);
                rabbitTemplate.convertAndSend(MqConstants.SEARCH_INDEX_DLX, MqConstants.RK_SEARCH_ACTIVITY_CHANGED_DLQ, message);
                log.warn("活动搜索索引消息已进入死信队列: activityId={}, eventType={}",
                        message.getActivityId(), message.getEventType());
                return;
            }
            metricsRecorder.recordRetry(MqConstants.Q_SEARCH_ACTIVITY_CHANGED);
            throw new AmqpRejectAndDontRequeueException("活动搜索索引消息处理失败，进入重试队列", e);
        }
    }
}
