package com.omni.order.entity;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("order_snapshot")
@KeySequence(value = "order_snapshot_id_seq", dbType = DbType.POSTGRE_SQL)
public class OrderSnapshot {

    @TableId(type = IdType.INPUT)
    private Long id;
    private Long orderId;
    private Long activityId;
    private String activityName;
    private String activityPoster;
    private Long tourId;
    private Long stationId;
    private Long sessionId;
    private LocalDateTime sessionTime;
    private String venueName;
    private Long ticketTypeId;
    private String ticketName;
    private BigDecimal unitPrice;
    private Integer quantity;
    private String seatLabels;
    private String grabRequestId;
    private Long requestedTicketTypeId;
    private Long matchedTicketTypeId;
    private Boolean autoDowngraded;
    private Long teamId;
    private String teamGrabRequestId;
    private Boolean teamOrder;
    private String seatSelectionMode;
    private Boolean ticketTransferAllowed;
    private Integer refundFullDaysBefore;
    private Integer refundPartialDaysBefore;
    private Integer refundPartialRate;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }
    public String getActivityPoster() { return activityPoster; }
    public void setActivityPoster(String activityPoster) { this.activityPoster = activityPoster; }
    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public LocalDateTime getSessionTime() { return sessionTime; }
    public void setSessionTime(LocalDateTime sessionTime) { this.sessionTime = sessionTime; }
    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }
    public Long getTicketTypeId() { return ticketTypeId; }
    public void setTicketTypeId(Long ticketTypeId) { this.ticketTypeId = ticketTypeId; }
    public String getTicketName() { return ticketName; }
    public void setTicketName(String ticketName) { this.ticketName = ticketName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getSeatLabels() { return seatLabels; }
    public void setSeatLabels(String seatLabels) { this.seatLabels = seatLabels; }
    public String getGrabRequestId() { return grabRequestId; }
    public void setGrabRequestId(String grabRequestId) { this.grabRequestId = grabRequestId; }
    public Long getRequestedTicketTypeId() { return requestedTicketTypeId; }
    public void setRequestedTicketTypeId(Long requestedTicketTypeId) { this.requestedTicketTypeId = requestedTicketTypeId; }
    public Long getMatchedTicketTypeId() { return matchedTicketTypeId; }
    public void setMatchedTicketTypeId(Long matchedTicketTypeId) { this.matchedTicketTypeId = matchedTicketTypeId; }
    public Boolean getAutoDowngraded() { return autoDowngraded; }
    public void setAutoDowngraded(Boolean autoDowngraded) { this.autoDowngraded = autoDowngraded; }
    public Long getTeamId() { return teamId; }
    public void setTeamId(Long teamId) { this.teamId = teamId; }
    public String getTeamGrabRequestId() { return teamGrabRequestId; }
    public void setTeamGrabRequestId(String teamGrabRequestId) { this.teamGrabRequestId = teamGrabRequestId; }
    public Boolean getTeamOrder() { return teamOrder; }
    public void setTeamOrder(Boolean teamOrder) { this.teamOrder = teamOrder; }
    public String getSeatSelectionMode() { return seatSelectionMode; }
    public void setSeatSelectionMode(String seatSelectionMode) { this.seatSelectionMode = seatSelectionMode; }
    public Boolean getTicketTransferAllowed() { return ticketTransferAllowed; }
    public void setTicketTransferAllowed(Boolean ticketTransferAllowed) { this.ticketTransferAllowed = ticketTransferAllowed; }
    public Integer getRefundFullDaysBefore() { return refundFullDaysBefore; }
    public void setRefundFullDaysBefore(Integer value) { this.refundFullDaysBefore = value; }
    public Integer getRefundPartialDaysBefore() { return refundPartialDaysBefore; }
    public void setRefundPartialDaysBefore(Integer value) { this.refundPartialDaysBefore = value; }
    public Integer getRefundPartialRate() { return refundPartialRate; }
    public void setRefundPartialRate(Integer value) { this.refundPartialRate = value; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
