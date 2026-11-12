package com.omni.common.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 病毒扫描装配：
 * - omni.virus-scanner.provider=clamd 时装配 ClamAV clamd（INSTREAM）实现；
 * - 其余情况装配 DisabledVirusScanner 降级。
 */
@Configuration
public class VirusScannerConfig {

    @Bean
    @ConditionalOnProperty(name = "omni.virus-scanner.provider", havingValue = "clamd")
    public VirusScanner clamdVirusScanner(
            @Value("${omni.virus-scanner.clamd.host:localhost}") String host,
            @Value("${omni.virus-scanner.clamd.port:3310}") int port) {
        return new ClamdVirusScanner(host, port);
    }

    @Bean
    @ConditionalOnProperty(name = "omni.virus-scanner.provider", havingValue = "disabled", matchIfMissing = true)
    public VirusScanner disabledVirusScanner() {
        return new DisabledVirusScanner();
    }
}
