package com.omni.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class OrderListItemResponse {
    private Long id;
    private String orderNo;
    private Long userId;
    private Long sessionId;
    private Long ticketTypeId;
    private Integer quantity;
    private BigDecimal amount;
    private Integer status;
    private Boolean userHidden;
    private LocalDateTime userDeletedAt;
    private LocalDateTime userDeleteExpiresAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Long activityId;
    private String activityName;
    private String activityPoster;
    private String venueName;
    private LocalDateTime sessionTime;
    private String ticketName;
    private BigDecimal unitPrice;
    private String seatLabels;
    private String grabRequestId;
    private Long requestedTicketTypeId;
    private Long matchedTicketTypeId;
    private Boolean autoDowngraded;
    private Long teamId;
    private String teamGrabRequestId;
    private Boolean teamOrder;
    private String seatSelectionMode;
    private List<OrderAttendeeResponse> attendees;
    private Integer refundFullDaysBefore;
    private Integer refundPartialDaysBefore;
    private Integer refundPartialRate;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getTicketTypeId() { return ticketTypeId; }
    public void setTicketTypeId(Long ticketTypeId) { this.ticketTypeId = ticketTypeId; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public Boolean getUserHidden() { return userHidden; }
    public void setUserHidden(Boolean userHidden) { this.userHidden = userHidden; }
    public LocalDateTime getUserDeletedAt() { return userDeletedAt; }
    public void setUserDeletedAt(LocalDateTime userDeletedAt) { this.userDeletedAt = userDeletedAt; }
    public LocalDateTime getUserDeleteExpiresAt() { return userDeleteExpiresAt; }
    public void setUserDeleteExpiresAt(LocalDateTime userDeleteExpiresAt) { this.userDeleteExpiresAt = userDeleteExpiresAt; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public Long getActivityId() { return activityId; }
    public void setActivityId(Long activityId) { this.activityId = activityId; }
    public String getActivityName() { return activityName; }
    public void setActivityName(String activityName) { this.activityName = activityName; }
    public String getActivityPoster() { return activityPoster; }
    public void setActivityPoster(String activityPoster) { this.activityPoster = activityPoster; }
    public String getVenueName() { return venueName; }
    public void setVenueName(String venueName) { this.venueName = venueName; }
    public LocalDateTime getSessionTime() { return sessionTime; }
    public void setSessionTime(LocalDateTime sessionTime) { this.sessionTime = sessionTime; }
    public String getTicketName() { return ticketName; }
    public void setTicketName(String ticketName) { this.ticketName = ticketName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
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
    public List<OrderAttendeeResponse> getAttendees() { return attendees; }
    public void setAttendees(List<OrderAttendeeResponse> attendees) { this.attendees = attendees; }
    public Integer getRefundFullDaysBefore() { return refundFullDaysBefore; }
    public void setRefundFullDaysBefore(Integer value) { this.refundFullDaysBefore = value; }
    public Integer getRefundPartialDaysBefore() { return refundPartialDaysBefore; }
    public void setRefundPartialDaysBefore(Integer value) { this.refundPartialDaysBefore = value; }
    public Integer getRefundPartialRate() { return refundPartialRate; }
    public void setRefundPartialRate(Integer value) { this.refundPartialRate = value; }
}
