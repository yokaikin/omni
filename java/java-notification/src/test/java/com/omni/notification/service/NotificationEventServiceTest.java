package com.omni.notification.service;

import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.exception.BusinessException;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.dto.NotificationDeliveryStatusResponse;
import com.omni.notification.entity.Notification;
import com.omni.notification.entity.NotificationDelivery;
import com.omni.notification.mapper.NotificationDeliveryMapper;
import com.omni.notification.sms.SmsTemplateProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationEventServiceTest {

    private final NotificationDeliveryMapper deliveryMapper = mock(NotificationDeliveryMapper.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final NotificationEventService service =
            new NotificationEventService(deliveryMapper, notificationService);

    @Test
    void springContextCanCreateNotificationEventService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(NotificationDeliveryMapper.class, () -> deliveryMapper);
            context.registerBean(NotificationService.class, () -> notificationService);
            context.registerBean(SmsTemplateProperties.class, SmsTemplateProperties::new);
            context.register(NotificationEventService.class);

            context.refresh();

            assertNotNull(context.getBean(NotificationEventService.class));
        }
    }

    @Test
    void processEventCreatesInAppNotificationAndSentDeliveryRecord() {
        NotificationEventMessage message = eventMessage();
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(notificationService.createInternalMessage(any())).thenReturn(new Notification());

        service.processEvent(message);

        ArgumentCaptor<InternalNotificationRequest> requestCaptor = ArgumentCaptor.forClass(InternalNotificationRequest.class);
        verify(notificationService).createInternalMessage(requestCaptor.capture());
        InternalNotificationRequest request = requestCaptor.getValue();
        assertEquals(2004L, request.getUserId());
        assertEquals(9001L, request.getOrderId());
        assertEquals("SUPPORT_REPLY", request.getType());
        assertEquals("人工客服回复了你的咨询，请查看客服会话。", request.getContent());
        assertEquals("/help", request.getActionHref());
        assertEquals("查看客服会话", request.getActionLabel());
        assertEquals("SUPPORT_REPLY:99", request.getAggregateKey());

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).insert(deliveryCaptor.capture());
        NotificationDelivery delivery = deliveryCaptor.getValue();
        assertEquals("support-reply:99:1", delivery.getEventId());
        assertEquals("SUPPORT_REPLY", delivery.getEventType());
        assertEquals(2004L, delivery.getUserId());
        assertEquals(9001L, delivery.getOrderId());
        assertEquals(1001L, delivery.getActivityId());
        assertEquals("IN_APP", delivery.getChannel());
        assertEquals("SENT", delivery.getStatus());
        assertEquals(0, delivery.getRetryCount());
        assertEquals("support-reply", delivery.getTemplateCode());
        assertEquals("人工客服回复了你的咨询，请查看客服会话。", delivery.getContentSnapshot());
        assertEquals("{\"conversationId\":99}", delivery.getPayloadJson());
    }

    @Test
    void processEventDoesNotDuplicateExistingEventChannelDelivery() {
        NotificationEventMessage message = eventMessage();
        NotificationDelivery existing = new NotificationDelivery();
        existing.setEventId("support-reply:99:1");
        existing.setChannel("IN_APP");
        existing.setStatus("SENT");
        when(deliveryMapper.selectOne(any())).thenReturn(existing);

        service.processEvent(message);

        verify(notificationService, never()).createInternalMessage(any());
        verify(deliveryMapper, never()).insert(any());
    }

    @Test
    void processEventRecordsPendingSmsAndKeepsInAppDelivery() {
        NotificationEventMessage message = eventMessage();
        message.setChannels(List.of("IN_APP", "SMS"));
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(notificationService.createInternalMessage(any())).thenReturn(new Notification());
        service.processEvent(message);

        verify(notificationService).createInternalMessage(any());

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper, times(2)).insert(deliveryCaptor.capture());
        List<NotificationDelivery> deliveries = deliveryCaptor.getAllValues();

        NotificationDelivery inAppDelivery = deliveries.get(0);
        assertEquals("IN_APP", inAppDelivery.getChannel());
        assertEquals("SENT", inAppDelivery.getStatus());

        NotificationDelivery smsDelivery = deliveries.get(1);
        assertEquals("SMS", smsDelivery.getChannel());
        assertEquals("PENDING", smsDelivery.getStatus());
        assertNull(smsDelivery.getFailureReason());
    }

    @Test
    void processEventRecordsPendingSmsDeliveryForRetry() {
        NotificationEventMessage message = eventMessage();
        message.setChannels(List.of("SMS"));
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        service.processEvent(message);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).insert(deliveryCaptor.capture());
        NotificationDelivery smsDelivery = deliveryCaptor.getValue();
        assertEquals("SMS", smsDelivery.getChannel());
        assertEquals("PENDING", smsDelivery.getStatus());
        assertNull(smsDelivery.getSentTime());
    }

    @Test
    void processEventUsesConfiguredSmsTemplateCodeWhenEventHasNoExplicitCode() {
        SmsTemplateProperties templates = new SmsTemplateProperties();
        templates.getTemplateCodes().put("REFUND_UNKNOWN", "SMS_987654");
        NotificationEventService configuredService = new NotificationEventService(
                deliveryMapper, notificationService, new com.fasterxml.jackson.databind.ObjectMapper(), templates);
        NotificationEventMessage message = eventMessage();
        message.setTemplateCode(null);
        message.setEventType("refund_unknown");
        message.setChannels(List.of("IN_APP", "SMS"));
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        when(notificationService.createInternalMessage(any())).thenReturn(new Notification());

        configuredService.processEvent(message);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper, times(2)).insert(deliveryCaptor.capture());
        List<NotificationDelivery> deliveries = deliveryCaptor.getAllValues();
        assertNull(deliveries.get(0).getTemplateCode());
        assertEquals("SMS_987654", deliveries.get(1).getTemplateCode());
    }

    @Test
    void processEventRecordsPendingEmailDeliveryForRetry() {
        NotificationEventMessage message = eventMessage();
        message.setChannels(List.of("EMAIL"));
        when(deliveryMapper.selectOne(any())).thenReturn(null);
        service.processEvent(message);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).insert(deliveryCaptor.capture());
        NotificationDelivery emailDelivery = deliveryCaptor.getValue();
        assertEquals("EMAIL", emailDelivery.getChannel());
        assertEquals("PENDING", emailDelivery.getStatus());
        assertNull(emailDelivery.getSentTime());
    }

    @Test
    void getDeliveryStatusDoesNotExposeContentOrPayload() throws Exception {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setEventId("evt-1");
        delivery.setChannel("SMS");
        delivery.setStatus("SENT");
        delivery.setProviderMessageId("provider-1");
        delivery.setContentSnapshot("private message");
        delivery.setPayloadJson("{\"phone\":\"private\"}");
        when(deliveryMapper.selectOne(any())).thenReturn(delivery);

        NotificationDeliveryStatusResponse response = service.getDeliveryStatus("evt-1", "SMS");
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response);

        assertEquals("SENT", response.getStatus());
        assertFalse(json.contains("private message"));
        assertFalse(json.contains("private"));
        assertFalse(json.contains("contentSnapshot"));
        assertFalse(json.contains("payloadJson"));
    }

    @Test
    void processEventRejectsMissingRequiredFields() {
        NotificationEventMessage message = new NotificationEventMessage();
        message.setEventId("bad-event");
        message.setEventType("SUPPORT_REPLY");
        message.setUserId(2004L);

        assertThrows(BusinessException.class, () -> service.processEvent(message));
    }

    private static NotificationEventMessage eventMessage() {
        NotificationEventMessage message = new NotificationEventMessage();
        message.setEventId("support-reply:99:1");
        message.setEventType("SUPPORT_REPLY");
        message.setAggregateKey("SUPPORT_REPLY:99");
        message.setUserId(2004L);
        message.setOrderId(9001L);
        message.setActivityId(1001L);
        message.setTemplateCode("support-reply");
        message.setChannels(List.of("IN_APP"));
        message.setContent("人工客服回复了你的咨询，请查看客服会话。");
        message.setActionHref("/help");
        message.setActionLabel("查看客服会话");
        message.setPayload(Map.of("conversationId", 99));
        message.setOccurredAt(LocalDateTime.parse("2026-06-07T03:30:00"));
        return message;
    }
}
