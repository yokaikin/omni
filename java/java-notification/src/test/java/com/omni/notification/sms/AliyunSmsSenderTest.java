package com.omni.notification.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.context.support.TestPropertySourceUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AliyunSmsSenderTest {

    @Test
    void constructorRejectsMissingAccessKey() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new AliyunSmsSender("dysmsapi.aliyuncs.com", "", "secret", "Omni"));
        assertEquals(true, exception.getMessage().contains("AccessKey"));
    }

    @Test
    void constructorRejectsMissingSignName() {
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new AliyunSmsSender("dysmsapi.aliyuncs.com", "key", "secret", ""));
        assertEquals(true, exception.getMessage().contains("签名"));
    }

    @Test
    void sendFailsWhenRecipientMissing() {
        AliyunSmsSender sender = new AliyunSmsSender(mock(Client.class), "Omni");
        SmsSendRequest request = new SmsSendRequest();
        request.setTemplateCode("SMS_123");

        SmsSendResult result = sender.send(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("短信收件人手机号缺失", result.getFailureReason());
    }

    @Test
    void sendFailsWhenTemplateCodeMissing() {
        AliyunSmsSender sender = new AliyunSmsSender(mock(Client.class), "Omni");
        SmsSendRequest request = new SmsSendRequest();
        request.setRecipientPhone("13800001111");

        SmsSendResult result = sender.send(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals("未配置短信模板，无法通过阿里云发送", result.getFailureReason());
    }

    @Test
    void sendReturnsSentWhenAliyunRespondsOk() throws Exception {
        Client client = mock(Client.class);
        SendSmsResponseBody body = new SendSmsResponseBody()
                .setCode("OK")
                .setBizId("biz-123")
                .setMessage("OK");
        when(client.sendSms(any())).thenReturn(new SendSmsResponse().setBody(body));
        AliyunSmsSender sender = new AliyunSmsSender(client, "Omni");
        SmsSendRequest request = new SmsSendRequest();
        request.setRecipientPhone("13800001111");
        request.setTemplateCode("SMS_123");
        request.setPayload(Map.of("code", "666666"));

        SmsSendResult result = sender.send(request);

        assertEquals("SENT", result.getStatus());
        assertEquals("biz-123", result.getProviderMessageId());
    }

    @Test
    void sendReturnsFailedWhenAliyunRespondsError() throws Exception {
        Client client = mock(Client.class);
        SendSmsResponseBody body = new SendSmsResponseBody()
                .setCode("isv.BUSINESS_LIMIT_CONTROL")
                .setMessage("触发分钟级流控");
        when(client.sendSms(any())).thenReturn(new SendSmsResponse().setBody(body));
        AliyunSmsSender sender = new AliyunSmsSender(client, "Omni");
        SmsSendRequest request = new SmsSendRequest();
        request.setRecipientPhone("13800001111");
        request.setTemplateCode("SMS_123");

        SmsSendResult result = sender.send(request);

        assertEquals("FAILED", result.getStatus());
        assertEquals(true, result.getFailureReason().contains("isv.BUSINESS_LIMIT_CONTROL"));
    }

    @Test
    void springContextCreatesAliyunSenderWhenProviderConfigured() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(SmsSenderConfig.class);
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "omni.notification.sms.provider=aliyun",
                    "omni.notification.sms.aliyun.access-key-id=test-key",
                    "omni.notification.sms.aliyun.access-key-secret=test-secret",
                    "omni.notification.sms.aliyun.sign-name=Omni");
            context.refresh();

            assertInstanceOf(AliyunSmsSender.class, context.getBean(SmsSender.class));
        }
    }
}
