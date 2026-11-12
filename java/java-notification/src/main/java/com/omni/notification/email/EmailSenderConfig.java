package com.omni.notification.email;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * 邮件发送器装配：
 * - omni.notification.email.provider=smtp 时装配 SMTP 实现（spring.mail.* + 发件人必须配置）；
 * - 其余情况装配 DisabledEmailSender 降级（投递记录状态为 SKIPPED）。
 */
@Configuration
public class EmailSenderConfig {

    @Bean
    @ConditionalOnProperty(name = "omni.notification.email.provider", havingValue = "smtp")
    public EmailSender smtpEmailSender(JavaMailSender mailSender,
                                       @Value("${omni.notification.email.smtp.from:}") String from) {
        return new SmtpEmailSender(mailSender, from);
    }

    @Bean
    @ConditionalOnProperty(name = "omni.notification.email.provider", havingValue = "disabled", matchIfMissing = true)
    public EmailSender disabledEmailSender() {
        return new DisabledEmailSender();
    }
}
