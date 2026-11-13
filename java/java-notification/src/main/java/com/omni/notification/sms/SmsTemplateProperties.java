package com.omni.notification.sms;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "omni.notification.sms")
public class SmsTemplateProperties {

    private Map<String, String> templateCodes = new LinkedHashMap<>();

    public Map<String, String> getTemplateCodes() {
        return templateCodes;
    }

    public void setTemplateCodes(Map<String, String> templateCodes) {
        this.templateCodes = templateCodes == null ? new LinkedHashMap<>() : templateCodes;
    }
}
