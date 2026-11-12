package com.omni.notification.service;

import com.omni.notification.email.EmailSendRequest;
import com.omni.notification.email.EmailSendResult;
import com.omni.notification.email.EmailSender;
import com.omni.notification.sms.SmsSendRequest;
import com.omni.notification.sms.SmsSendResult;
import com.omni.notification.sms.SmsSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 渠道投递统一编排：补齐收件人后调用对应渠道发送器。
 * 供事件投递（NotificationEventService）、直发（NotificationService）和重试调度共用。
 */
@Service
public class NotificationChannelService {

    private static final Logger log = LoggerFactory.getLogger(NotificationChannelService.class);
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    private final RecipientResolver recipientResolver;
    private final SmsSender smsSender;
    private final EmailSender emailSender;

    public NotificationChannelService(RecipientResolver recipientResolver,
                                      SmsSender smsSender,
                                      EmailSender emailSender) {
        this.recipientResolver = recipientResolver;
        this.smsSender = smsSender;
        this.emailSender = emailSender;
    }

    /**
     * 发送短信：收件人手机号缺失时按 userId 解析。
     */
    public SmsSendResult sendSms(SmsSendRequest request) {
        if (request == null) {
            return new SmsSendResult(STATUS_FAILED, null, "短信发送请求不能为空");
        }
        if (!StringUtils.hasText(request.getRecipientPhone()) && request.getUserId() != null) {
            String phone = recipientResolver.resolvePhone(request.getUserId());
            if (!StringUtils.hasText(phone)) {
                return new SmsSendResult(STATUS_FAILED, null, "无法解析收件人手机号");
            }
            request.setRecipientPhone(phone.trim());
        }
        if (!StringUtils.hasText(request.getRecipientPhone())) {
            return new SmsSendResult(STATUS_FAILED, null, "短信收件人手机号缺失");
        }
        SmsSendResult result = smsSender.send(request);
        if (result == null) {
            return new SmsSendResult(STATUS_FAILED, null, "短信投递结果为空");
        }
        log.info("短信渠道投递: userId={}, status={}", request.getUserId(), result.getStatus());
        return result;
    }

    /**
     * 发送邮件：收件人邮箱缺失时按 userId 解析。
     */
    public EmailSendResult sendEmail(EmailSendRequest request) {
        if (request == null) {
            return new EmailSendResult(STATUS_FAILED, null, "邮件发送请求不能为空");
        }
        if (!StringUtils.hasText(request.getRecipientEmail()) && request.getUserId() != null) {
            String email = recipientResolver.resolveEmail(request.getUserId());
            if (!StringUtils.hasText(email)) {
                return new EmailSendResult(STATUS_FAILED, null, "无法解析收件人邮箱");
            }
            request.setRecipientEmail(email.trim());
        }
        if (!StringUtils.hasText(request.getRecipientEmail())) {
            return new EmailSendResult(STATUS_FAILED, null, "邮件收件人邮箱缺失");
        }
        EmailSendResult result = emailSender.send(request);
        if (result == null) {
            return new EmailSendResult(STATUS_FAILED, null, "邮件投递结果为空");
        }
        log.info("邮件渠道投递: userId={}, status={}", request.getUserId(), result.getStatus());
        return result;
    }
}
