package com.omni.ticket.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.ai.client.AiModelClient;
import com.omni.ai.client.OllamaAiModelClient;
import com.omni.ticket.ai.TicketIntentParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TicketAiModelConfig {

    @Bean
    public AiModelClient ticketAiModelClient(
            @Value("${omni.ticket.ai.enabled:${OMNI_TICKET_AI_ENABLED:true}}") boolean enabled,
            @Value("${omni.ticket.ai.endpoint:${OMNI_TICKET_AI_ENDPOINT:http://localhost:11434/api/chat}}") String endpoint,
            @Value("${omni.ticket.ai.model:${OMNI_TICKET_AI_MODEL:Qwen2.5:7b}}") String model,
            @Value("${omni.ticket.ai.timeout-ms:${OMNI_TICKET_AI_TIMEOUT_MS:30000}}") int timeoutMillis,
            @Value("${omni.ticket.ai.context-window:${OMNI_TICKET_AI_CONTEXT_WINDOW:2048}}") int contextWindow,
            @Value("${omni.ticket.ai.api-key:${OMNI_TICKET_AI_API_KEY:}}") String apiKey,
            @Value("${omni.ticket.ai.keep-alive:${OMNI_TICKET_AI_KEEP_ALIVE:30m}}") String keepAlive,
            ObjectMapper objectMapper) {
        return new OllamaAiModelClient(enabled, endpoint, model, timeoutMillis, contextWindow, apiKey, keepAlive, objectMapper);
    }

    @Bean
    public TicketIntentParser ticketIntentParser(
            AiModelClient modelClient,
            ObjectMapper objectMapper,
            @Value("${omni.ticket.ai.model:${OMNI_TICKET_AI_MODEL:Qwen2.5:7b}}") String model) {
        return new TicketIntentParser(modelClient, objectMapper, Clock.systemDefaultZone(), model);
    }
}
