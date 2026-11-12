package com.omni.user.dto;

/**
 * 内部联系方式查询响应（供 java-notification 解析短信/邮件收件人）。
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
