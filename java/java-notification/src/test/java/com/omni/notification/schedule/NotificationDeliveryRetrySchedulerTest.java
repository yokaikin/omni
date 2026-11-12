package com.omni.notification.schedule;

import com.omni.notification.email.EmailSendResult;
import com.omni.notification.entity.NotificationDelivery;
import com.omni.notification.mapper.NotificationDeliveryMapper;
import com.omni.notification.service.NotificationChannelService;
import com.omni.notification.service.NotificationEventService;
import com.omni.notification.sms.SmsSendResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDeliveryRetrySchedulerTest {

    private final NotificationDeliveryMapper deliveryMapper = mock(NotificationDeliveryMapper.class);
    private final NotificationChannelService channelService = mock(NotificationChannelService.class);

    @Test
    void doesNothingWhenNoRetryableDeliveries() {
        when(deliveryMapper.selectList(any())).thenReturn(List.of());
        NotificationDeliveryRetryScheduler scheduler =
                new NotificationDeliveryRetryScheduler(deliveryMapper, channelService, 3);

        scheduler.retryFailedDeliveries();

        verify(deliveryMapper, never()).updateById(any(NotificationDelivery.class));
    }

    @Test
    void skipsScanWhenMaxAttemptsDisabled() {
        NotificationDeliveryRetryScheduler scheduler =
                new NotificationDeliveryRetryScheduler(deliveryMapper, channelService, 0);

        scheduler.retryFailedDeliveries();

        verify(deliveryMapper, never()).selectList(any());
    }

    @Test
    void retriesPendingSmsDeliveryUntilSent() {
        NotificationDelivery delivery = delivery("support-reply:99:1", "SMS", NotificationEventService.STATUS_PENDING, 0);
        delivery.setTemplateCode("support-reply");
        delivery.setPayloadJson("{\"conversationId\":99}");
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(channelService.sendSms(any())).thenReturn(new SmsSendResult("SENT", "biz-1", null));
        NotificationDeliveryRetryScheduler scheduler =
                new NotificationDeliveryRetryScheduler(deliveryMapper, channelService, 3);

        scheduler.retryFailedDeliveries();

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).updateById(captor.capture());
        NotificationDelivery updated = captor.getValue();
        assertEquals(NotificationEventService.STATUS_SENT, updated.getStatus());
        assertEquals(1, updated.getRetryCount());
        assertNotNull(updated.getSentTime());

        ArgumentCaptor<com.omni.notification.sms.SmsSendRequest> requestCaptor =
                ArgumentCaptor.forClass(com.omni.notification.sms.SmsSendRequest.class);
        verify(channelService).sendSms(requestCaptor.capture());
        assertEquals("support-reply", requestCaptor.getValue().getTemplateCode());
        assertEquals(Map.of("conversationId", 99), requestCaptor.getValue().getPayload());
    }

    @Test
    void retriesFailedEmailDeliveryAndIncrementsRetryCount() {
        NotificationDelivery delivery = delivery("refund-approved:9001", "EMAIL", NotificationEventService.STATUS_FAILED, 1);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(channelService.sendEmail(any())).thenReturn(new EmailSendResult("FAILED", null, "SMTP 连接失败"));
        NotificationDeliveryRetryScheduler scheduler =
                new NotificationDeliveryRetryScheduler(deliveryMapper, channelService, 3);

        scheduler.retryFailedDeliveries();

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).updateById(captor.capture());
        NotificationDelivery updated = captor.getValue();
        assertEquals(NotificationEventService.STATUS_FAILED, updated.getStatus());
        assertEquals(2, updated.getRetryCount());
        assertEquals("SMTP 连接失败", updated.getFailureReason());
    }

    @Test
    void marksSkippedWhenChannelNotConfigured() {
        NotificationDelivery delivery = delivery("legacy-event:1", "EMAIL", NotificationEventService.STATUS_PENDING, 0);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(channelService.sendEmail(any())).thenReturn(new EmailSendResult("SKIPPED", null, "邮件渠道未配置"));
        NotificationDeliveryRetryScheduler scheduler =
                new NotificationDeliveryRetryScheduler(deliveryMapper, channelService, 3);

        scheduler.retryFailedDeliveries();

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).updateById(captor.capture());
        assertEquals(NotificationEventService.STATUS_SKIPPED, captor.getValue().getStatus());
        assertEquals("邮件渠道未配置", captor.getValue().getFailureReason());
    }

    @Test
    void recordsRetryExceptionSoItCannotLoopForever() {
        NotificationDelivery delivery = delivery("bad-event:1", "SMS", NotificationEventService.STATUS_FAILED, 0);
        when(deliveryMapper.selectList(any())).thenReturn(List.of(delivery));
        when(channelService.sendSms(any())).thenThrow(new RuntimeException("db down"));
        NotificationDeliveryRetryScheduler scheduler =
                new NotificationDeliveryRetryScheduler(deliveryMapper, channelService, 3);

        scheduler.retryFailedDeliveries();

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryMapper).updateById(captor.capture());
        assertEquals(NotificationEventService.STATUS_FAILED, captor.getValue().getStatus());
        assertEquals(1, captor.getValue().getRetryCount());
        assertEquals("db down", captor.getValue().getFailureReason());
    }

    private static NotificationDelivery delivery(String eventId, String channel, String status, int retryCount) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setId(1L);
        delivery.setEventId(eventId);
        delivery.setEventType("SUPPORT_REPLY");
        delivery.setUserId(2004L);
        delivery.setChannel(channel);
        delivery.setStatus(status);
        delivery.setRetryCount(retryCount);
        delivery.setContentSnapshot("人工客服回复了你的咨询，请查看客服会话。");
        return delivery;
    }
}
