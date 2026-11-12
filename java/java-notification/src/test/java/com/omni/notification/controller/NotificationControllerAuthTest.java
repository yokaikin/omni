package com.omni.notification.controller;

import com.omni.common.result.Result;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.notification.dto.ChannelStatusResponse;
import com.omni.notification.entity.Notification;
import com.omni.notification.service.NotificationEventService;
import com.omni.notification.service.NotificationService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationControllerAuthTest {

    private static final String SECRET = "omni-jwt-secretomni-jwt-secretomni-jwt-secret";

    @Test
    void listNotificationsUsesJwtUserId() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        controller.listNotifications(bearer(2004L));

        verify(service).listNotifications(2004L);
    }

    @Test
    void listNotificationsRejectsMissingJwt() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<List<Notification>> result = controller.listNotifications(null);

        assertEquals(401, result.getCode());
        verify(service, never()).listNotifications(any());
    }

    @Test
    void markAllReadUsesJwtUserId() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        controller.markAllRead(bearer(2004L));

        verify(service).markAllRead(2004L);
    }

    @Test
    void deleteReadRejectsMissingJwt() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<?> result = controller.deleteRead(null);

        assertEquals(401, result.getCode());
        verify(service, never()).deleteRead(any());
    }

    @Test
    void markSingleReadUsesJwtUserIdAndPathId() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        controller.markRead(99L, bearer(2004L));

        verify(service).markRead(2004L, 99L);
    }

    @Test
    void sendSmsIsDisabledByDefaultBeforeCallingService() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<Notification> result = controller.sendSms(bearer(2004L), Map.of(
                "userId", 9999L,
                "orderId", 10L,
                "content", "hi"
        ));

        assertEquals(400, result.getCode());
        assertEquals("当前环境未启用短信直发", result.getMessage());
        verify(service, never()).sendSms(any(), any(), any());
    }

    @Test
    void sendSmsUsesJwtUserIdWhenDirectChannelEnabled() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);
        ReflectionTestUtils.setField(controller, "directChannelEnabled", true);

        controller.sendSms(bearer(2004L), Map.of(
                "userId", 9999L,
                "orderId", 10L,
                "content", "hi"
        ));

        verify(service).sendSms(2004L, 10L, "hi");
        verify(service, never()).sendSms(9999L, 10L, "hi");
    }

    @Test
    void sendSmsRejectsMissingJwt() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<Notification> result = controller.sendSms(null, Map.of(
                "userId", 9999L,
                "orderId", 10L,
                "content", "hi"
        ));

        assertEquals(401, result.getCode());
        verify(service, never()).sendSms(any(), any(), any());
    }

    @Test
    void sendEmailIsDisabledByDefaultBeforeCallingService() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<Notification> result = controller.sendEmail(bearer(2004L), Map.of(
                "userId", 9999L,
                "orderId", 10L,
                "content", "hi"
        ));

        assertEquals(400, result.getCode());
        assertEquals("当前环境未启用邮件直发", result.getMessage());
        verify(service, never()).sendEmail(any(), any(), any());
    }

    @Test
    void sendEmailUsesJwtUserIdWhenDirectChannelEnabled() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);
        ReflectionTestUtils.setField(controller, "directChannelEnabled", true);

        controller.sendEmail(bearer(2004L), Map.of(
                "userId", 9999L,
                "orderId", 10L,
                "content", "hi"
        ));

        verify(service).sendEmail(2004L, 10L, "hi");
        verify(service, never()).sendEmail(9999L, 10L, "hi");
    }

    @Test
    void sendEmailRejectsMissingJwt() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<Notification> result = controller.sendEmail(null, Map.of(
                "userId", 9999L,
                "orderId", 10L,
                "content", "hi"
        ));

        assertEquals(401, result.getCode());
        verify(service, never()).sendEmail(any(), any(), any());
    }

    @Test
    void createInternalEventUsesInternalToken() {
        NotificationService service = mock(NotificationService.class);
        NotificationEventService eventService = mock(NotificationEventService.class);
        NotificationController controller = new NotificationController(service, eventService, "internal-token", SECRET);
        NotificationEventMessage message = notificationEvent();

        Result<Void> result = controller.createInternalEvent("internal-token", message);

        assertEquals(200, result.getCode());
        verify(eventService).processEvent(message);
    }

    @Test
    void createInternalEventRejectsInvalidToken() {
        NotificationService service = mock(NotificationService.class);
        NotificationEventService eventService = mock(NotificationEventService.class);
        NotificationController controller = new NotificationController(service, eventService, "internal-token", SECRET);

        Result<Void> result = controller.createInternalEvent("wrong-token", notificationEvent());

        assertEquals(403, result.getCode());
        verify(eventService, never()).processEvent(any());
    }

    @Test
    void listInternalUserNotificationsRejectsMissingToken() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<List<Notification>> result = controller.listInternalUserNotifications(2004L, 5, null);

        assertEquals(403, result.getCode());
        verify(service, never()).listNotifications(any());
    }

    @Test
    void listInternalUserNotificationsReturnsLimitedNotifications() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);
        Notification first = new Notification();
        first.setId(701L);
        Notification second = new Notification();
        second.setId(702L);
        when(service.listNotifications(2004L)).thenReturn(List.of(first, second));

        Result<List<Notification>> result = controller.listInternalUserNotifications(2004L, 1, "internal-token");

        assertEquals(200, result.getCode());
        assertEquals(List.of(first), result.getData());
        verify(service).listNotifications(2004L);
    }

    @Test
    void channelStatusRequiresInternalToken() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);

        Result<ChannelStatusResponse> result = controller.getChannelStatus(null);

        assertEquals(403, result.getCode());
    }

    @Test
    void channelStatusReturnsProviderConfiguration() {
        NotificationService service = mock(NotificationService.class);
        NotificationController controller = controller(service);
        ReflectionTestUtils.setField(controller, "smsProvider", "aliyun");
        ReflectionTestUtils.setField(controller, "emailProvider", "smtp");
        ReflectionTestUtils.setField(controller, "objectStorageProvider", "minio");
        ReflectionTestUtils.setField(controller, "directChannelEnabled", true);
        ReflectionTestUtils.setField(controller, "deliveryRetryMaxAttempts", 5);

        Result<ChannelStatusResponse> result = controller.getChannelStatus("internal-token");

        assertEquals(200, result.getCode());
        assertEquals("aliyun", result.getData().getSmsProvider());
        assertEquals("smtp", result.getData().getEmailProvider());
        assertEquals("minio", result.getData().getObjectStorageProvider());
        assertEquals(true, result.getData().isDirectChannelEnabled());
        assertEquals(5, result.getData().getDeliveryRetryMaxAttempts());
    }

    private static NotificationController controller(NotificationService service) {
        return new NotificationController(service, mock(NotificationEventService.class), "internal-token", SECRET);
    }

    private static NotificationEventMessage notificationEvent() {
        NotificationEventMessage message = new NotificationEventMessage();
        message.setEventId("grab-success:2004:9001");
        message.setEventType("GRAB_SUCCESS");
        message.setUserId(2004L);
        message.setOrderId(9001L);
        message.setContent("抢票成功，请尽快支付。");
        return message;
    }

    private static String bearer(Long userId) {
        Key key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .claim("userId", userId)
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
        return "Bearer " + token;
    }
}
