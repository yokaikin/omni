package com.omni.notification.email;

import com.omni.common.util.LogMaskUtil;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * SMTP 邮件发送实现（基于 spring-boot-starter-mail）。
 * 邮件内容按纯文本 + UTF-8 发送。
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        if (mailSender == null) {
            throw new IllegalStateException("SMTP JavaMailSender 未初始化，请检查 spring.mail.* 配置");
        }
        if (!StringUtils.hasText(from)) {
            throw new IllegalStateException("SMTP 发件人未配置，请检查 omni.notification.email.smtp.from");
        }
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public EmailSendResult send(EmailSendRequest request) {
        if (request == null || !StringUtils.hasText(request.getRecipientEmail())) {
            return new EmailSendResult("FAILED", null, "邮件收件人地址缺失");
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(request.getRecipientEmail());
            helper.setSubject(StringUtils.hasText(request.getSubject()) ? request.getSubject() : "Omni 通知");
            helper.setText(request.getContent() == null ? "" : request.getContent(), false);
            mailSender.send(message);
            log.info("SMTP 邮件发送成功: to={}, subject={}", LogMaskUtil.maskEmail(request.getRecipientEmail()), message.getSubject());
            return new EmailSendResult("SENT", message.getMessageID(), null);
        } catch (MessagingException | MailException e) {
            log.warn("SMTP 邮件发送失败: to={}, error={}", LogMaskUtil.maskEmail(request.getRecipientEmail()), e.getMessage());
            return new EmailSendResult("FAILED", null, truncate(e.getMessage()));
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
