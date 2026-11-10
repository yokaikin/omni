package com.omni.ticket;

import com.omni.common.util.LocalProdSplitDefaults;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "com.omni")
@EnableDiscoveryClient
@EnableFeignClients
@EnableScheduling
public class TicketApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(TicketApplication.class);
        LocalProdSplitDefaults.apply(application, "java-ticket", args);
        application.run(args);
    }
}
