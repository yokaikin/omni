package com.omni.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.notification.entity.MqDeadLetter;
import com.omni.notification.mapper.MqDeadLetterMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MQ 死信审计：死信落库、查询与重放。
 */
@Service
public class MqDeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(MqDeadLetterService.class);
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 500;

    /** 死信来源队列 -> 重放目标 [exchange, routingKey] */
    private static final Map<String, String[]> REPLAY_TARGETS;
    static {
        Map<String, String[]> targets = new HashMap<>();
        targets.put(MqConstants.Q_NOTIFICATION_SEND,
                new String[]{MqConstants.NOTIFICATION_EXCHANGE, MqConstants.RK_NOTIFICATION_SEND});
        targets.put(MqConstants.Q_NOTIFICATION_EVENT,
                new String[]{MqConstants.NOTIFICATION_EXCHANGE, MqConstants.RK_NOTIFICATION_EVENT});
        REPLAY_TARGETS = Collections.unmodifiableMap(targets);
    }

    private final MqDeadLetterMapper mqDeadLetterMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MqMetricsRecorder metricsRecorder;

    public MqDeadLetterService(MqDeadLetterMapper mqDeadLetterMapper,
                               RabbitTemplate rabbitTemplate,
                               MqMetricsRecorder metricsRecorder) {
        this.mqDeadLetterMapper = mqDeadLetterMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.metricsRecorder = metricsRecorder;
    }

    /**
     * 从死信队列的原始消息落库审计记录。
     */
    public MqDeadLetter record(String sourceQueue, String deadLetterQueue, org.springframework.amqp.core.Message rawMessage) {
        MqDeadLetter record = new MqDeadLetter();
        record.setSourceQueue(sourceQueue);
        record.setDeadLetterQueue(deadLetterQueue);
        record.setPayload(rawMessage != null ? new String(rawMessage.getBody(), StandardCharsets.UTF_8) : null);
        record.setFailureMessage(truncate("消费重试超限进入死信队列"));
        record.setStatus(MqDeadLetter.STATUS_PENDING);
        record.setReplayCount(0);
        LocalDateTime now = LocalDateTime.now();
        record.setCreatedTime(now);
        record.setUpdatedTime(now);
        mqDeadLetterMapper.insert(record);
        log.warn("死信消息已审计落库: sourceQueue={}, deadLetterQueue={}, recordId={}",
                sourceQueue, deadLetterQueue, record.getId());
        return record;
    }

    public List<MqDeadLetter> list(String status, int limit) {
        LambdaQueryWrapper<MqDeadLetter> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) {
            wrapper.eq(MqDeadLetter::getStatus, status);
        }
        wrapper.orderByDesc(MqDeadLetter::getId)
                .last("LIMIT " + Math.max(1, limit));
        return mqDeadLetterMapper.selectList(wrapper);
    }

    /**
     * 将死信记录重放到原始主队列（不带 x-death 头，从干净状态重新消费）。
     */
    public MqDeadLetter replay(Long id) {
        MqDeadLetter record = mqDeadLetterMapper.selectById(id);
        if (record == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "死信记录不存在");
        }
        String[] target = REPLAY_TARGETS.get(record.getSourceQueue());
        if (target == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "死信来源队列不支持重放");
        }
        if (!StringUtils.hasText(record.getPayload())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "死信记录缺少可重放的消息体");
        }
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbitTemplate.send(target[0], target[1],
                new Message(record.getPayload().getBytes(StandardCharsets.UTF_8), properties));
        record.setStatus(MqDeadLetter.STATUS_REPLAYED);
        record.setReplayCount(record.getReplayCount() != null ? record.getReplayCount() + 1 : 1);
        record.setReplayedAt(LocalDateTime.now());
        record.setUpdatedTime(LocalDateTime.now());
        mqDeadLetterMapper.updateById(record);
        metricsRecorder.recordReplay(record.getSourceQueue());
        log.info("死信记录已重放: recordId={}, sourceQueue={}, replayCount={}",
                record.getId(), record.getSourceQueue(), record.getReplayCount());
        return record;
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= FAILURE_MESSAGE_MAX_LENGTH ? value
                : value.substring(0, FAILURE_MESSAGE_MAX_LENGTH);
    }
}
