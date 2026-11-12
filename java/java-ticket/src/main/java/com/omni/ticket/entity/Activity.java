package com.omni.ticket.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.omni.ticket.dto.ActivityArtistDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动
 */
@TableName("activity")
public class Activity {

    @TableId(type = IdType.AUTO)
    private Long id;
    @TableField("category_id")
    private Long categoryId;
    private Long artistId;
    private Long organizerId;
    private Long tourId;
    private Long stationId;
    private Long venueApplicationId;
    private String name;
    private String description;
    private String poster;
    private String venueApprovalNo;
    private String venueApprovalFileUrl;
    private String venueApprovalNote;
    private String publishStatus;
    private String seatMapVisibility;
    private Integer refundFullDaysBefore;
    private Integer refundPartialDaysBefore;
    private Integer refundPartialRate;
    private Boolean realNameRequired;
    private Boolean ticketTransferAllowed;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String deleteReason;
    private LocalDateTime deletedAt;
    private Long deletedBy;
    private String riskSuspendedReason;
    private LocalDateTime riskSuspendedAt;
    private LocalDateTime riskRestoredAt;
    @TableField(exist = false)
    private String artistName;
    @TableField(exist = false)
    private String itemType;
    @TableField(exist = false)
    private List<ActivityArtistDto> artists;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Long getArtistId() { return artistId; }
    public void setArtistId(Long artistId) { this.artistId = artistId; }
    public Long getOrganizerId() { return organizerId; }
    public void setOrganizerId(Long organizerId) { this.organizerId = organizerId; }
    public Long getTourId() { return tourId; }
    public void setTourId(Long tourId) { this.tourId = tourId; }
    public Long getStationId() { return stationId; }
    public void setStationId(Long stationId) { this.stationId = stationId; }
    public Long getVenueApplicationId() { return venueApplicationId; }
    public void setVenueApplicationId(Long venueApplicationId) { this.venueApplicationId = venueApplicationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPoster() { return poster; }
    public void setPoster(String poster) { this.poster = poster; }
    public String getVenueApprovalNo() { return venueApprovalNo; }
    public void setVenueApprovalNo(String venueApprovalNo) { this.venueApprovalNo = venueApprovalNo; }
    public String getVenueApprovalFileUrl() { return venueApprovalFileUrl; }
    public void setVenueApprovalFileUrl(String venueApprovalFileUrl) { this.venueApprovalFileUrl = venueApprovalFileUrl; }
    public String getVenueApprovalNote() { return venueApprovalNote; }
    public void setVenueApprovalNote(String venueApprovalNote) { this.venueApprovalNote = venueApprovalNote; }
    public String getPublishStatus() { return publishStatus; }
    public void setPublishStatus(String publishStatus) { this.publishStatus = publishStatus; }
    public String getSeatMapVisibility() { return seatMapVisibility; }
    public void setSeatMapVisibility(String seatMapVisibility) { this.seatMapVisibility = seatMapVisibility; }
    public Integer getRefundFullDaysBefore() { return refundFullDaysBefore; }
    public void setRefundFullDaysBefore(Integer refundFullDaysBefore) { this.refundFullDaysBefore = refundFullDaysBefore; }
    public Integer getRefundPartialDaysBefore() { return refundPartialDaysBefore; }
    public void setRefundPartialDaysBefore(Integer refundPartialDaysBefore) { this.refundPartialDaysBefore = refundPartialDaysBefore; }
    public Integer getRefundPartialRate() { return refundPartialRate; }
    public void setRefundPartialRate(Integer refundPartialRate) { this.refundPartialRate = refundPartialRate; }
    public Boolean getRealNameRequired() { return realNameRequired; }
    public void setRealNameRequired(Boolean realNameRequired) { this.realNameRequired = realNameRequired; }
    public Boolean getTicketTransferAllowed() { return ticketTransferAllowed; }
    public void setTicketTransferAllowed(Boolean ticketTransferAllowed) { this.ticketTransferAllowed = ticketTransferAllowed; }
    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
    public String getDeleteReason() { return deleteReason; }
    public void setDeleteReason(String deleteReason) { this.deleteReason = deleteReason; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public String getRiskSuspendedReason() { return riskSuspendedReason; }
    public void setRiskSuspendedReason(String riskSuspendedReason) { this.riskSuspendedReason = riskSuspendedReason; }
    public LocalDateTime getRiskSuspendedAt() { return riskSuspendedAt; }
    public void setRiskSuspendedAt(LocalDateTime riskSuspendedAt) { this.riskSuspendedAt = riskSuspendedAt; }
    public LocalDateTime getRiskRestoredAt() { return riskRestoredAt; }
    public void setRiskRestoredAt(LocalDateTime riskRestoredAt) { this.riskRestoredAt = riskRestoredAt; }
    public String getArtistName() { return artistName; }
    public void setArtistName(String artistName) { this.artistName = artistName; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public List<ActivityArtistDto> getArtists() { return artists; }
    public void setArtists(List<ActivityArtistDto> artists) { this.artists = artists; }
}
