package com.omni.notification.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信发送器装配：
 * - omni.notification.sms.provider=aliyun 时装配阿里云实现（凭据缺失启动即报错）；
 * - 其余情况装配 DisabledSmsSender 降级（投递记录状态为 SKIPPED）。
 */
@Configuration
public class SmsSenderConfig {

    @Bean
    @ConditionalOnProperty(name = "omni.notification.sms.provider", havingValue = "aliyun")
    public SmsSender aliyunSmsSender(
            @Value("${omni.notification.sms.aliyun.endpoint:dysmsapi.aliyuncs.com}") String endpoint,
            @Value("${omni.notification.sms.aliyun.access-key-id:}") String accessKeyId,
            @Value("${omni.notification.sms.aliyun.access-key-secret:}") String accessKeySecret,
            @Value("${omni.notification.sms.aliyun.sign-name:}") String signName) {
        return new AliyunSmsSender(endpoint, accessKeyId, accessKeySecret, signName);
    }

    @Bean
    @ConditionalOnProperty(name = "omni.notification.sms.provider", havingValue = "disabled", matchIfMissing = true)
    public SmsSender disabledSmsSender() {
        return new DisabledSmsSender();
    }
}
