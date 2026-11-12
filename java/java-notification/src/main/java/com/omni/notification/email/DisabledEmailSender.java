package com.omni.notification.email;

public class DisabledEmailSender implements EmailSender {

    @Override
    public EmailSendResult send(EmailSendRequest request) {
        return new EmailSendResult("SKIPPED", null, "邮件渠道未配置");
    }
}
