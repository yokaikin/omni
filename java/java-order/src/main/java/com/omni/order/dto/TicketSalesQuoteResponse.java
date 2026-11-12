package com.omni.order.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TicketSalesQuoteResponse {
    private Long sessionId;
    private Long ticketTypeId;
    private BigDecimal unitPrice;
    private String ticketName;
    private Integer quantity;
    private Boolean seatBased;
    private Long activityId;
    private String activityName;
    private String activityPoster;
    private String venueName;
    private LocalDateTime sessionTime;
    private Long tourId;
    private Long stationId;
    private String seatLabels;
    private Boolean realNameRequired;
    private Boolean ticketTransferAllowed;
    private Integer refundFullDaysBefore;
    private Integer refundPartialDaysBefore;
    private Integer refundPartialRate;

    public Long getSessionId() { return sessionId; }
    public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
    public Long getTicketTypeId() { return ticketTypeId; }
    public void setTicketTypeId(Long ticketTypeId) { this.ticketTypeId = ticketTypeId; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public String getTicketName() { return ticketName; }
    public void setTicketName(String ticketName) { this.ticketName = ticketName; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public Boolean getSeatBased() { return seatBased; }
    public void setSeatBased(Boolean seatBased) { this.seatBased = seatBased; }
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
    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public String getSeatLabels() { return seatLabels; }
    public void setSeatLabels(String seatLabels) { this.seatLabels = seatLabels; }
    public Boolean getRealNameRequired() { return realNameRequired; }
    public void setRealNameRequired(Boolean realNameRequired) { this.realNameRequired = realNameRequired; }
    public Boolean getTicketTransferAllowed() { return ticketTransferAllowed; }
    public void setTicketTransferAllowed(Boolean ticketTransferAllowed) { this.ticketTransferAllowed = ticketTransferAllowed; }
    public Integer getRefundFullDaysBefore() { return refundFullDaysBefore; }
    public void setRefundFullDaysBefore(Integer value) { this.refundFullDaysBefore = value; }
    public Integer getRefundPartialDaysBefore() { return refundPartialDaysBefore; }
    public void setRefundPartialDaysBefore(Integer value) { this.refundPartialDaysBefore = value; }
    public Integer getRefundPartialRate() { return refundPartialRate; }
    public void setRefundPartialRate(Integer value) { this.refundPartialRate = value; }
}
