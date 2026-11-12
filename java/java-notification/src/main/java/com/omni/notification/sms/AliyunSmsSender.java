package com.omni.notification.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.dysmsapi20170525.models.SendSmsResponseBody;
import com.aliyun.teaopenapi.models.Config;
import com.omni.common.util.LogMaskUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 阿里云短信发送实现。
 * 依赖环境变量注入 AccessKey/签名/模板；缺少模板时无法发送（阿里云不支持自由内容短信）。
 */
public class AliyunSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(AliyunSmsSender.class);
    private static final String SUCCESS_CODE = "OK";

    private final Client client;
    private final String signName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AliyunSmsSender(String endpoint, String accessKeyId, String accessKeySecret, String signName) {
        if (!StringUtils.hasText(accessKeyId) || !StringUtils.hasText(accessKeySecret)) {
            throw new IllegalStateException("阿里云短信 AccessKey 未配置，请检查 ALIYUN_SMS_ACCESS_KEY_ID / ALIYUN_SMS_ACCESS_KEY_SECRET");
        }
        if (!StringUtils.hasText(signName)) {
            throw new IllegalStateException("阿里云短信签名未配置，请检查 ALIYUN_SMS_SIGN_NAME");
        }
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret)
                    .setEndpoint(StringUtils.hasText(endpoint) ? endpoint : "dysmsapi.aliyuncs.com");
            this.client = new Client(config);
        } catch (Exception e) {
            throw new IllegalStateException("阿里云短信客户端初始化失败: " + e.getMessage(), e);
        }
        this.signName = signName;
    }

    AliyunSmsSender(Client client, String signName) {
        if (!StringUtils.hasText(signName)) {
            throw new IllegalStateException("阿里云短信签名未配置，请检查 ALIYUN_SMS_SIGN_NAME");
        }
        this.client = client;
        this.signName = signName;
    }

    @Override
    public SmsSendResult send(SmsSendRequest request) {
        if (request == null || !StringUtils.hasText(request.getRecipientPhone())) {
            return new SmsSendResult("FAILED", null, "短信收件人手机号缺失");
        }
        if (!StringUtils.hasText(request.getTemplateCode())) {
            return new SmsSendResult("FAILED", null, "未配置短信模板，无法通过阿里云发送");
        }
        try {
            SendSmsRequest sendRequest = new SendSmsRequest()
                    .setPhoneNumbers(request.getRecipientPhone())
                    .setSignName(signName)
                    .setTemplateCode(request.getTemplateCode())
                    .setTemplateParam(buildTemplateParam(request.getPayload()));
            SendSmsResponse response = client.sendSms(sendRequest);
            SendSmsResponseBody body = response == null ? null : response.getBody();
            if (body == null || !SUCCESS_CODE.equals(body.getCode())) {
                String code = body == null ? "EMPTY_RESPONSE" : body.getCode();
                String message = body == null ? "阿里云短信返回空响应" : body.getMessage();
                log.warn("阿里云短信发送失败: phone={}, code={}, message={}",
                        LogMaskUtil.maskPhone(request.getRecipientPhone()), code, message);
                return new SmsSendResult("FAILED", body == null ? null : body.getRequestId(), code + ": " + message);
            }
            log.info("阿里云短信发送成功: phone={}, bizId={}", LogMaskUtil.maskPhone(request.getRecipientPhone()), body.getBizId());
            return new SmsSendResult("SENT", body.getBizId(), null);
        } catch (Exception e) {
            log.warn("阿里云短信发送异常: phone={}, error={}", LogMaskUtil.maskPhone(request.getRecipientPhone()), e.getMessage());
            return new SmsSendResult("FAILED", null, truncate(e.getMessage()));
        }
    }

    private String buildTemplateParam(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String truncate(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() > 400 ? message.substring(0, 400) : message;
    }
}
