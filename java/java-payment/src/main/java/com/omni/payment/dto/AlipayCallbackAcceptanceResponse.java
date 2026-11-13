package com.omni.payment.dto;

import java.time.LocalDateTime;

/** 供 internal staging 验收使用的脱敏回调状态。 */
public class AlipayCallbackAcceptanceResponse {
    private Long orderId;
    private Integer paymentStatus;
    private String tradeNo;
    private LocalDateTime notifyTime;
    private boolean signedNotificationReceived;

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public Integer getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(Integer paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getTradeNo() { return tradeNo; }
    public void setTradeNo(String tradeNo) { this.tradeNo = tradeNo; }
    public LocalDateTime getNotifyTime() { return notifyTime; }
    public void setNotifyTime(LocalDateTime notifyTime) { this.notifyTime = notifyTime; }
    public boolean isSignedNotificationReceived() { return signedNotificationReceived; }
    public void setSignedNotificationReceived(boolean signedNotificationReceived) {
        this.signedNotificationReceived = signedNotificationReceived;
    }
}
