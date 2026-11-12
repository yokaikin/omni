package com.omni.notification.dto;

/**
 * java-user internal 联系方式响应（本地副本，服务边界内不共享实体）。
 */
public class InternalUserContactResponse {
    private Long userId;
    private String phone;
    private String email;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
