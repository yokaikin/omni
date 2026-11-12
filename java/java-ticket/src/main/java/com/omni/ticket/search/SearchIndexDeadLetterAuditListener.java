package com.omni.ticket.search;

import com.omni.common.mq.MqConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 搜索索引死信队列审计消费者：消费 search 域死信并落库，供 internal API 查询与重放。
 */
@Component
@ConditionalOnProperty(prefix = "omni.search", name = "provider", havingValue = "elasticsearch")
public class SearchIndexDeadLetterAuditListener {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexDeadLetterAuditListener.class);

    private final SearchIndexDeadLetterService deadLetterService;

    public SearchIndexDeadLetterAuditListener(SearchIndexDeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @RabbitListener(queues = MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ)
    public void onActivityChangedDeadLetter(Message rawMessage) {
        try {
            deadLetterService.record(MqConstants.Q_SEARCH_ACTIVITY_CHANGED,
                    MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ, rawMessage);
        } catch (Exception e) {
            log.error("搜索索引死信审计落库失败: deadLetterQueue={}",
                    MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ, e);
        }
    }
}
