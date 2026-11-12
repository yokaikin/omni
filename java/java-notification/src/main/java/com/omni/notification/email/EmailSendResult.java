package com.omni.notification.email;

public class EmailSendResult {

    private String status;
    private String providerMessageId;
    private String failureReason;

    public EmailSendResult() {
    }

    public EmailSendResult(String status, String providerMessageId, String failureReason) {
        this.status = status;
        this.providerMessageId = providerMessageId;
        this.failureReason = failureReason;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
}
