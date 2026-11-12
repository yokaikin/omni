package com.omni.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.common.mq.MqPublishSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 客服会话实时推送（进程内 SSE）：
 * - 一次性 ticket 换取长连接（EventSource 无法携带 Authorization 头，鉴权在换票时完成）；
 * - 用户通道按 userId 定向，坐席通道全局广播（工作台会话列表全员可见）；
 * - 心跳保活 + 断连自动清理，客户端断线后凭新 ticket 重连，失败降级轮询。
 * 当前单实例拓扑下进程内注册表即可满足；未来多实例需引入 Redis pub/sub 跨实例转发。
 */
@Service
public class SupportPushService {

    private static final Logger log = LoggerFactory.getLogger(SupportPushService.class);

    public static final String CHANNEL_USER = "user";
    public static final String CHANNEL_AGENT = "agent";

    private static final long EMITTER_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final long TICKET_TTL_MS = 30_000L;
    private static final long HEARTBEAT_FIXED_DELAY_MS = 20_000L;

    private final Map<Long, List<SseEmitter>> userChannels = new ConcurrentHashMap<>();
    private final List<SseEmitter> agentChannel = new CopyOnWriteArrayList<>();
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String issueTicket(Long userId, String channel) {
        String normalizedChannel = CHANNEL_AGENT.equals(channel) ? CHANNEL_AGENT : CHANNEL_USER;
        String ticket = UUID.randomUUID().toString().replace("-", "");
        tickets.put(ticket, new Ticket(userId, normalizedChannel, System.currentTimeMillis() + TICKET_TTL_MS));
        return ticket;
    }

    public SseEmitter subscribe(String ticket) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        Ticket pending = ticket == null ? null : tickets.remove(ticket);
        if (pending == null || pending.expiresAt < System.currentTimeMillis()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("{\"message\":\"推送票据无效或已过期\"}", MediaType.APPLICATION_JSON));
            } catch (IOException ignored) {
                // 客户端已断开。
            }
            emitter.complete();
            return emitter;
        }
        if (CHANNEL_AGENT.equals(pending.channel)) {
            agentChannel.add(emitter);
        } else {
            userChannels.computeIfAbsent(pending.userId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        }
        emitter.onCompletion(() -> removeEmitter(pending.userId, pending.channel, emitter));
        emitter.onTimeout(() -> removeEmitter(pending.userId, pending.channel, emitter));
        emitter.onError(error -> removeEmitter(pending.userId, pending.channel, emitter));
        sendEvent(emitter, eventPayload("ready", null));
        return emitter;
    }

    /**
     * 会话事件广播：用户通道定向 + 坐席通道全员。
     * 必须在事务提交后调用（数据可见性），事务内调用方统一经 afterCommitOrNow 包装。
     */
    public void publish(Long ownerUserId, Long conversationId, String type) {
        if (ownerUserId == null && conversationId == null) {
            return;
        }
        List<SseEmitter> userChannel = ownerUserId == null ? null : userChannels.get(ownerUserId);
        if (userChannel != null && !userChannel.isEmpty()) {
            for (SseEmitter emitter : userChannel) {
                sendEvent(emitter, eventPayload(type, conversationId));
            }
        }
        if (!agentChannel.isEmpty()) {
            for (SseEmitter emitter : agentChannel) {
                sendEvent(emitter, eventPayload(type, conversationId));
            }
        }
    }

    public void publishAfterCommit(Long ownerUserId, Long conversationId, String type) {
        MqPublishSupport.afterCommitOrNow(() -> publish(ownerUserId, conversationId, type));
    }

    @Scheduled(fixedDelay = HEARTBEAT_FIXED_DELAY_MS)
    public void heartbeat() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Ticket>> ticketIterator = tickets.entrySet().iterator();
        while (ticketIterator.hasNext()) {
            if (ticketIterator.next().getValue().expiresAt < now) {
                ticketIterator.remove();
            }
        }
        for (List<SseEmitter> channel : userChannels.values()) {
            for (SseEmitter emitter : channel) {
                sendEvent(emitter, eventPayload("ping", null));
            }
        }
        for (SseEmitter emitter : agentChannel) {
            sendEvent(emitter, eventPayload("ping", null));
        }
        userChannels.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    int activeEmitterCount() {
        int count = agentChannel.size();
        for (List<SseEmitter> channel : userChannels.values()) {
            count += channel.size();
        }
        return count;
    }

    private void removeEmitter(Long userId, String channel, SseEmitter emitter) {
        if (CHANNEL_AGENT.equals(channel)) {
            agentChannel.remove(emitter);
            return;
        }
        List<SseEmitter> channel2 = userChannels.get(userId);
        if (channel2 != null) {
            channel2.remove(emitter);
        }
    }

    private void sendEvent(SseEmitter emitter, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException | RuntimeException e) {
            removeQuietly(emitter);
        }
    }

    private void removeQuietly(SseEmitter emitter) {
        for (Map.Entry<Long, List<SseEmitter>> entry : userChannels.entrySet()) {
            if (entry.getValue().remove(emitter)) {
                return;
            }
        }
        agentChannel.remove(emitter);
    }

    private Map<String, Object> eventPayload(String type, Long conversationId) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        if (conversationId != null) {
            payload.put("conversationId", conversationId);
        }
        return payload;
    }

    List<Long> userChannelIds() {
        return new ArrayList<>(userChannels.keySet());
    }

    private static final class Ticket {
        private final Long userId;
        private final String channel;
        private final long expiresAt;

        private Ticket(Long userId, String channel, long expiresAt) {
            this.userId = userId;
            this.channel = channel;
            this.expiresAt = expiresAt;
        }
    }
}
