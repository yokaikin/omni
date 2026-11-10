package com.omni.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class OrderInfoResponse {

    private Long id;
    private String orderNo;
    private Long userId;
    private Long sessionId;
    private Long ticketTypeId;
    private Integer quantity;
    private BigDecimal amount;
    private Integer status;
    private Long activityId;
    private String activityName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime sessionTime;
    private Integer refundFullDaysBefore;
    private Integer refundPartialDaysBefore;
    private Integer refundPartialRate;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public Long getTicketTypeId() {
        return ticketTypeId;
    }

    public void setTicketTypeId(Long ticketTypeId) {
        this.ticketTypeId = ticketTypeId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public String getActivityName() { return activityName; }

    public void setActivityName(String activityName) { this.activityName = activityName; }

    public Long getActivityId() { return activityId; }

    public void setActivityId(Long activityId) { this.activityId = activityId; }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public LocalDateTime getSessionTime() { return sessionTime; }

    public void setSessionTime(LocalDateTime sessionTime) { this.sessionTime = sessionTime; }
    public Integer getRefundFullDaysBefore() { return refundFullDaysBefore; }
    public void setRefundFullDaysBefore(Integer value) { this.refundFullDaysBefore = value; }
    public Integer getRefundPartialDaysBefore() { return refundPartialDaysBefore; }
    public void setRefundPartialDaysBefore(Integer value) { this.refundPartialDaysBefore = value; }
    public Integer getRefundPartialRate() { return refundPartialRate; }
    public void setRefundPartialRate(Integer value) { this.refundPartialRate = value; }
}
