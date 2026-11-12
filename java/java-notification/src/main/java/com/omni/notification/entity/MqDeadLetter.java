package com.omni.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * MQ 死信审计记录：消费重试超限后落库，供查询与重放。
 */
@TableName("mq_dead_letter")
public class MqDeadLetter {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_REPLAYED = "REPLAYED";

    @TableId(type = IdType.AUTO)
    private Long id;
    /** 死信来源主队列 */
    private String sourceQueue;
    /** 死信队列 */
    private String deadLetterQueue;
    /** 原始业务消息 JSON */
    private String payload;
    /** 死信原因 */
    private String failureMessage;
    /** PENDING / REPLAYED */
    private String status;
    private Integer replayCount;
    private LocalDateTime replayedAt;
    private LocalDateTime createdTime;
    private LocalDateTime updatedTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceQueue() { return sourceQueue; }
    public void setSourceQueue(String sourceQueue) { this.sourceQueue = sourceQueue; }
    public String getDeadLetterQueue() { return deadLetterQueue; }
    public void setDeadLetterQueue(String deadLetterQueue) { this.deadLetterQueue = deadLetterQueue; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getFailureMessage() { return failureMessage; }
    public void setFailureMessage(String failureMessage) { this.failureMessage = failureMessage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getReplayCount() { return replayCount; }
    public void setReplayCount(Integer replayCount) { this.replayCount = replayCount; }
    public LocalDateTime getReplayedAt() { return replayedAt; }
    public void setReplayedAt(LocalDateTime replayedAt) { this.replayedAt = replayedAt; }
    public LocalDateTime getCreatedTime() { return createdTime; }
    public void setCreatedTime(LocalDateTime createdTime) { this.createdTime = createdTime; }
    public LocalDateTime getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(LocalDateTime updatedTime) { this.updatedTime = updatedTime; }
}
