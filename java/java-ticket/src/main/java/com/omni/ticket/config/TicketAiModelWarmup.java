package com.omni.ticket.config;

import com.omni.ai.client.AiModelClient;
import com.omni.ai.dto.AiMessage;
import com.omni.ai.dto.AiRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 启动后异步预热 AI 模型，避免 AI Finder 首次调用承受模型冷加载延迟。
 * 预热失败只记录告警，不影响服务启动与运行。
 */
@Component
public class TicketAiModelWarmup implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger log = LoggerFactory.getLogger(TicketAiModelWarmup.class);

    private final AiModelClient modelClient;
    private final boolean warmupEnabled;
    private final long warmupDelayMillis;

    public TicketAiModelWarmup(
            AiModelClient modelClient,
            @Value("${omni.ticket.ai.warmup-enabled:${OMNI_TICKET_AI_WARMUP_ENABLED:true}}") boolean warmupEnabled,
            @Value("${omni.ticket.ai.warmup-delay-ms:${OMNI_TICKET_AI_WARMUP_DELAY_MS:2000}}") long warmupDelayMillis) {
        this.modelClient = modelClient;
        this.warmupEnabled = warmupEnabled;
        this.warmupDelayMillis = warmupDelayMillis;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!warmupEnabled) {
            return;
        }
        Thread warmupThread = new Thread(this::warmUp, "ticket-ai-model-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    private void warmUp() {
        try {
            Thread.sleep(Math.max(0, warmupDelayMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            long started = System.currentTimeMillis();
            AiRequest request = new AiRequest(
                    "ai-warmup-" + started,
                    null,
                    "你是票务助手，回答保持简洁。",
                    List.of(new AiMessage("user", "预热")),
                    null,
                    1,
                    null);
            modelClient.generate(request, null);
            log.info("AI 模型预热完成，耗时 {}ms", System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("AI 模型预热失败（不影响服务启动）: {}", e.getMessage());
        }
    }
}
