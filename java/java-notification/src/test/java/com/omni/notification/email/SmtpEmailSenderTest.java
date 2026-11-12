package com.omni.notification.email;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SmtpEmailSenderTest {

    private static MimeMessage mimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void constructorRejectsMissingMailSender() {
        assertThrows(IllegalStateException.class, () -> new SmtpEmailSender(null, "noreply@omni.cn"));
    }

    @Test
    void constructorRejectsMissingFrom() {
        assertThrows(IllegalStateException.class, () -> new SmtpEmailSender(mock(JavaMailSender.class), ""));
    }

    @Test
    void sendFailsWhenRecipientMissing() {
        SmtpEmailSender sender = new SmtpEmailSender(mock(JavaMailSender.class), "noreply@omni.cn");
        EmailSendRequest request = new EmailSendRequest();
        request.setContent("内容");

        EmailSendResult result = sender.send(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("邮件收件人地址缺失", result.getFailureReason());
    }

    @Test
    void sendReturnsSentOnSuccessfulDelivery() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage());
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@omni.cn");
        EmailSendRequest request = new EmailSendRequest();
        request.setRecipientEmail("user@example.com");
        request.setSubject("Omni 通知");
        request.setContent("邮件内容");

        EmailSendResult result = sender.send(request);

        assertEquals("SENT", result.getStatus());
    }

    @Test
    void sendReturnsFailedWhenSmtpThrows() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage());
        doThrow(new MailSendException("SMTP 连接失败")).when(mailSender).send(any(MimeMessage.class));
        SmtpEmailSender sender = new SmtpEmailSender(mailSender, "noreply@omni.cn");
        EmailSendRequest request = new EmailSendRequest();
        request.setRecipientEmail("user@example.com");
        request.setContent("邮件内容");

        EmailSendResult result = sender.send(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals(true, result.getFailureReason().contains("SMTP"));
    }

    @Test
    void springContextCreatesDisabledSenderByDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(EmailSenderConfig.class);
            context.registerBean(JavaMailSender.class, () -> mock(JavaMailSender.class));

            context.refresh();

            assertInstanceOf(DisabledEmailSender.class, context.getBean(EmailSender.class));
        }
    }

    @Test
    void springContextCreatesSmtpSenderWhenProviderConfigured() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(EmailSenderConfig.class);
            context.registerBean(JavaMailSender.class, () -> mock(JavaMailSender.class));
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "omni.notification.email.provider=smtp",
                    "omni.notification.email.smtp.from=noreply@omni.cn");
            context.refresh();

            assertInstanceOf(SmtpEmailSender.class, context.getBean(EmailSender.class));
        }
    }
}
