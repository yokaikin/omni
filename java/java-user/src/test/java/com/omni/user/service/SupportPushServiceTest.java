package com.omni.user.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SupportPushServiceTest {

    private final SupportPushService service = new SupportPushService();

    @Test
    void subscribesWithValidTicketAndConsumesIt() {
        Long userId = 10L;
        String ticket = service.issueTicket(userId, SupportPushService.CHANNEL_USER);
        assertNotNull(ticket);

        SseEmitter first = service.subscribe(ticket);
        assertEquals(1, service.activeEmitterCount());

        // ticket 一次性：重复使用不能再次注册
        SseEmitter second = service.subscribe(ticket);
        assertEquals(1, service.activeEmitterCount());
        assertNotEquals(first, second);
    }

    @Test
    void rejectsInvalidTicket() {
        SseEmitter emitter = service.subscribe("not-a-ticket");
        assertNotNull(emitter);
        assertEquals(0, service.activeEmitterCount());
    }

    @Test
    void routesChannelsByTicketChannel() {
        // 无 servlet 容器时 emitter.onCompletion 不会触发，此处验证通道注册归属
        String userTicket = service.issueTicket(11L, SupportPushService.CHANNEL_USER);
        service.subscribe(userTicket);
        String agentTicket = service.issueTicket(12L, SupportPushService.CHANNEL_AGENT);
        service.subscribe(agentTicket);

        assertEquals(List.of(11L), service.userChannelIds());
        assertEquals(2, service.activeEmitterCount());
    }

    @Test
    void publishReachesRegisteredEmittersWithoutError() {
        String ticket = service.issueTicket(12L, SupportPushService.CHANNEL_AGENT);
        service.subscribe(ticket);
        assertEquals(1, service.activeEmitterCount());

        service.publish(12L, 99L, "message");
        service.publish(null, 99L, "conversation");
        assertEquals(1, service.activeEmitterCount());
    }

    @Test
    void heartbeatKeepsConnectionAlive() {
        String ticket = service.issueTicket(13L, SupportPushService.CHANNEL_USER);
        service.subscribe(ticket);
        assertEquals(1, service.activeEmitterCount());

        service.heartbeat();

        assertEquals(1, service.activeEmitterCount());
    }

    @Test
    void issueTicketNormalizesUnknownChannelToUser() {
        String ticket = service.issueTicket(14L, "whatever");
        service.subscribe(ticket);
        assertEquals(1, service.activeEmitterCount());
        assertEquals(1, service.userChannelIds().size());
    }
}
