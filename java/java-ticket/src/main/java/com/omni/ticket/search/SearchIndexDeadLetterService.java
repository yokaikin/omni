package com.omni.ticket.search;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.ticket.mapper.SearchIndexDeadLetterMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索索引 MQ 死信审计：死信落库、查询与重放。
 */
@Service
public class SearchIndexDeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexDeadLetterService.class);

    private final SearchIndexDeadLetterMapper deadLetterMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MqMetricsRecorder metricsRecorder;

    public SearchIndexDeadLetterService(SearchIndexDeadLetterMapper deadLetterMapper,
                                        RabbitTemplate rabbitTemplate,
                                        MqMetricsRecorder metricsRecorder) {
        this.deadLetterMapper = deadLetterMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * 从搜索索引死信队列的原始消息落库审计记录。
     */
    public SearchIndexDeadLetter record(String sourceQueue, String deadLetterQueue, Message rawMessage) {
        SearchIndexDeadLetter record = new SearchIndexDeadLetter();
        record.setSourceQueue(sourceQueue);
        record.setDeadLetterQueue(deadLetterQueue);
        record.setPayload(rawMessage != null ? new String(rawMessage.getBody(), StandardCharsets.UTF_8) : null);
        record.setFailureMessage("索引消费重试超限进入死信队列");
        record.setStatus(SearchIndexDeadLetter.STATUS_PENDING);
        record.setReplayCount(0);
        LocalDateTime now = LocalDateTime.now();
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        deadLetterMapper.insert(record);
        log.warn("搜索索引死信消息已审计落库: sourceQueue={}, deadLetterQueue={}, recordId={}",
                sourceQueue, deadLetterQueue, record.getId());
        return record;
    }

    public List<SearchIndexDeadLetter> list(String status, int limit) {
        LambdaQueryWrapper<SearchIndexDeadLetter> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(SearchIndexDeadLetter::getStatus, status);
        }
        wrapper.orderByDesc(SearchIndexDeadLetter::getId)
                .last("LIMIT " + Math.max(1, limit));
        return deadLetterMapper.selectList(wrapper);
    }

    /**
     * 将死信记录重放到原始主队列（不带 x-death 头，从干净状态重新消费）。
     */
    public SearchIndexDeadLetter replay(Long id) {
        SearchIndexDeadLetter record = deadLetterMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "死信记录不存在");
        }
        if (!MqConstants.Q_SEARCH_ACTIVITY_CHANGED.equals(record.getSourceQueue())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "死信来源队列不支持重放");
        }
        if (!StringUtils.hasText(record.getPayload())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "死信记录缺少可重放的消息体");
        }
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(MqConstants.SEARCH_INDEX_EXCHANGE, MqConstants.RK_SEARCH_ACTIVITY_CHANGED,
                new Message(record.getPayload().getBytes(StandardCharsets.UTF_8), properties));
        record.setStatus(SearchIndexDeadLetter.STATUS_REPLAYED);
        record.setReplayCount(record.getReplayCount() != null ? record.getReplayCount() + 1 : 1);
        record.setReplayedAt(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        deadLetterMapper.updateById(record);
        metricsRecorder.recordReplay(record.getSourceQueue());
        log.info("搜索索引死信记录已重放: recordId={}, replayCount={}", record.getId(), record.getReplayCount());
        return record;
    }
}
