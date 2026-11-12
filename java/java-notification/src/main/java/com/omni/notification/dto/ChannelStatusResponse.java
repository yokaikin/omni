package com.omni.notification.dto;

/**
 * 通知渠道配置状态响应（internal 运维查询）。
 */
public class ChannelStatusResponse {
    /** 短信 provider: disabled / aliyun */
    private String smsProvider;
    /** 邮件 provider: disabled / smtp */
    private String emailProvider;
    /** 对象存储 provider: disabled / minio */
    private String objectStorageProvider;
    private boolean directChannelEnabled;
    private int deliveryRetryMaxAttempts;

    public String getSmsProvider() { return smsProvider; }
    public void setSmsProvider(String smsProvider) { this.smsProvider = smsProvider; }
    public String getEmailProvider() { return emailProvider; }
    public void setEmailProvider(String emailProvider) { this.emailProvider = emailProvider; }
    public String getObjectStorageProvider() { return objectStorageProvider; }
    public void setObjectStorageProvider(String objectStorageProvider) { this.objectStorageProvider = objectStorageProvider; }
    public boolean isDirectChannelEnabled() { return directChannelEnabled; }
    public void setDirectChannelEnabled(boolean directChannelEnabled) { this.directChannelEnabled = directChannelEnabled; }
    public int getDeliveryRetryMaxAttempts() { return deliveryRetryMaxAttempts; }
    public void setDeliveryRetryMaxAttempts(int deliveryRetryMaxAttempts) { this.deliveryRetryMaxAttempts = deliveryRetryMaxAttempts; }
}
