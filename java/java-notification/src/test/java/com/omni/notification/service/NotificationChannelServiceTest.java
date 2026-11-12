package com.omni.notification.service;

import com.omni.notification.email.EmailSendRequest;
import com.omni.notification.email.EmailSendResult;
import com.omni.notification.email.EmailSender;
import com.omni.notification.sms.SmsSendRequest;
import com.omni.notification.sms.SmsSendResult;
import com.omni.notification.sms.SmsSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationChannelServiceTest {

    private final RecipientResolver recipientResolver = mock(RecipientResolver.class);
    private final SmsSender smsSender = mock(SmsSender.class);
    private final EmailSender emailSender = mock(EmailSender.class);
    private final NotificationChannelService service =
            new NotificationChannelService(recipientResolver, smsSender, emailSender);

    @Test
    void sendSmsUsesProvidedRecipientWithoutResolution() {
        when(smsSender.send(any())).thenReturn(new SmsSendResult("SENT", "biz-1", null));
        SmsSendRequest request = new SmsSendRequest();
        request.setUserId(2004L);
        request.setRecipientPhone("13800001111");

        SmsSendResult result = service.sendSms(request);

        assertEquals("SENT", result.getStatus());
        verify(smsSender).send(request);
    }

    @Test
    void sendSmsResolvesRecipientWhenMissing() {
        when(recipientResolver.resolvePhone(2004L)).thenReturn("13800001111");
        when(smsSender.send(any())).thenReturn(new SmsSendResult("SENT", "biz-2", null));
        SmsSendRequest request = new SmsSendRequest();
        request.setUserId(2004L);

        SmsSendResult result = service.sendSms(request);

        assertEquals("SENT", result.getStatus());
        assertEquals("13800001111", request.getRecipientPhone());
    }

    @Test
    void sendSmsFailsWhenRecipientUnresolvable() {
        when(recipientResolver.resolvePhone(2004L)).thenReturn(null);
        SmsSendRequest request = new SmsSendRequest();
        request.setUserId(2004L);

        SmsSendResult result = service.sendSms(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("无法解析收件人手机号", result.getFailureReason());
    }

    @Test
    void sendSmsFailsWhenRequestIsNull() {
        SmsSendResult result = service.sendSms(null);

        assertEquals("FAILED", result.getStatus());
        assertEquals("短信发送请求不能为空", result.getFailureReason());
    }

    @Test
    void sendSmsNormalizesEmptySenderResult() {
        when(recipientResolver.resolvePhone(2004L)).thenReturn("13800001111");
        when(smsSender.send(any())).thenReturn(null);
        SmsSendRequest request = new SmsSendRequest();
        request.setUserId(2004L);

        SmsSendResult result = service.sendSms(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("短信投递结果为空", result.getFailureReason());
    }

    @Test
    void sendEmailResolvesRecipientWhenMissing() {
        when(recipientResolver.resolveEmail(2004L)).thenReturn("user@example.com");
        when(emailSender.send(any())).thenReturn(new EmailSendResult("SENT", "msg-1", null));
        EmailSendRequest request = new EmailSendRequest();
        request.setUserId(2004L);

        EmailSendResult result = service.sendEmail(request);

        assertEquals("SENT", result.getStatus());
        assertEquals("user@example.com", request.getRecipientEmail());
    }

    @Test
    void sendEmailFailsWhenRecipientUnresolvable() {
        when(recipientResolver.resolveEmail(2004L)).thenReturn(null);
        EmailSendRequest request = new EmailSendRequest();
        request.setUserId(2004L);

        EmailSendResult result = service.sendEmail(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("无法解析收件人邮箱", result.getFailureReason());
    }
}
