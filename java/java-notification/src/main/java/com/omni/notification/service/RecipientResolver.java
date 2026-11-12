package com.omni.notification.service;

import com.omni.common.result.Result;
import com.omni.notification.client.UserInternalClient;
import com.omni.notification.dto.InternalUserContactResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 收件人解析：通过 java-user internal API 获取用户手机号/邮箱。
 * 解析失败返回 null（对应渠道投递记为 FAILED，可被重试任务重新处理）。
 */
@Service
public class RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipientResolver.class);

    private final UserInternalClient userInternalClient;
    private final String internalApiToken;

    public RecipientResolver(UserInternalClient userInternalClient,
                             @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this.userInternalClient = userInternalClient;
        this.internalApiToken = internalApiToken;
    }

    /**
     * 解析用户联系方式；用户不存在或调用失败时返回 null。
     */
    public InternalUserContactResponse resolveContact(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            Result<InternalUserContactResponse> result = userInternalClient.getUserContact(userId, internalApiToken);
            if (result != null && result.getCode() == 200 && result.getData() != null) {
                return result.getData();
            }
            log.warn("解析用户联系方式失败: userId={}, code={}", userId, result == null ? null : result.getCode());
            return null;
        } catch (Exception e) {
            log.warn("解析用户联系方式异常: userId={}, error={}", userId, e.getMessage());
            return null;
        }
    }

    public String resolvePhone(Long userId) {
        InternalUserContactResponse contact = resolveContact(userId);
        return contact == null ? null : contact.getPhone();
    }

    public String resolveEmail(Long userId) {
        InternalUserContactResponse contact = resolveContact(userId);
        return contact == null ? null : contact.getEmail();
    }
}
