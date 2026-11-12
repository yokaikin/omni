package com.omni.notification.dto;

import com.omni.notification.entity.Notification;

/**
 * 直发渠道结果：同时携带已写入的通知记录与外部渠道发送结果。
 */
public class DirectChannelSendResult {
    private Notification notification;
    /** 外部渠道发送状态: SENT / FAILED / SKIPPED */
    private String deliveryStatus;
    private String failureReason;

    public DirectChannelSendResult() {
    }

    public DirectChannelSendResult(Notification notification, String deliveryStatus, String failureReason) {
        this.notification = notification;
        this.deliveryStatus = deliveryStatus;
        this.failureReason = failureReason;
    }

    public Notification getNotification() { return notification; }
    public void setNotification(Notification notification) { this.notification = notification; }
    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
