package com.omni.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.notification.dto.DirectChannelSendResult;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.dto.NotificationSummaryResponse;
import com.omni.notification.email.EmailSendRequest;
import com.omni.notification.email.EmailSendResult;
import com.omni.notification.entity.Notification;
import com.omni.notification.mapper.NotificationMapper;
import com.omni.notification.sms.SmsSendRequest;
import com.omni.notification.sms.SmsSendResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationServiceTest {

    private final NotificationMapper notificationMapper = mock(NotificationMapper.class);
    private final NotificationChannelService notificationChannelService = mock(NotificationChannelService.class);
    private final NotificationService service = new NotificationService(notificationMapper, notificationChannelService);

    @Test
    void createInternalMessagePersistsActionAndAggregateMetadata() {
        InternalNotificationRequest request = new InternalNotificationRequest();
        request.setUserId(10L);
        request.setType("SUPPORT_REPLY");
        request.setContent("人工客服回复了你的咨询，请查看客服会话。");
        request.setActionHref("/help");
        request.setActionLabel("查看客服会话");
        request.setAggregateKey("SUPPORT_REPLY:99");

        service.createInternalMessage(request);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        Notification saved = captor.getValue();
        assertEquals("/help", saved.getActionHref());
        assertEquals("查看客服会话", saved.getActionLabel());
        assertEquals("SUPPORT_REPLY:99", saved.getAggregateKey());
        assertNull(saved.getReadTime());
        assertNull(saved.getDeletedTime());
    }

    @Test
    void createInternalMessageDefaultsOrderActionWhenMetadataMissing() {
        InternalNotificationRequest request = new InternalNotificationRequest();
        request.setUserId(10L);
        request.setOrderId(9001L);
        request.setType("WAITLIST_OFFERED");
        request.setContent("候补订单待支付");

        service.createInternalMessage(request);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        Notification saved = captor.getValue();
        assertEquals("/orders/9001", saved.getActionHref());
        assertEquals("查看相关订单", saved.getActionLabel());
        assertEquals("ORDER:9001", saved.getAggregateKey());
    }

    @Test
    void createInternalMessageReturnsExistingVisibleAggregateMessageWithoutInsert() {
        InternalNotificationRequest request = new InternalNotificationRequest();
        request.setUserId(10L);
        request.setType("SUPPORT_REPLY");
        request.setContent("人工客服回复了你的咨询，请查看客服会话。");
        request.setAggregateKey("SUPPORT_REPLY:99");
        Notification existing = notification(99L, 10L, null, null);
        existing.setAggregateKey("SUPPORT_REPLY:99");
        when(notificationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        Notification result = service.createInternalMessage(request);

        assertSame(existing, result);
        verify(notificationMapper, never()).insert(any());
    }

    @Test
    void createInternalMessageReturnsExistingOrderAggregateMessageWithoutInsert() {
        InternalNotificationRequest request = new InternalNotificationRequest();
        request.setUserId(10L);
        request.setOrderId(9001L);
        request.setType("WAITLIST_OFFERED");
        request.setContent("候补订单待支付");
        Notification existing = notification(100L, 10L, null, null);
        existing.setOrderId(9001L);
        existing.setAggregateKey("ORDER:9001");
        when(notificationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        Notification result = service.createInternalMessage(request);

        assertSame(existing, result);
        verify(notificationMapper, never()).insert(any());
    }

    @Test
    void listNotificationsOnlyReturnsVisibleUserNotifications() {
        Notification visible = notification(1L, 10L, null, null);
        Notification deleted = notification(2L, 10L, null, LocalDateTime.now());
        when(notificationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(visible, deleted));

        List<Notification> result = service.listNotifications(10L);

        assertEquals(List.of(visible), result);
    }

    @Test
    void markAllReadMarksOnlyUnreadVisibleNotificationsAndReturnsSummary() {
        Notification unread = notification(1L, 10L, null, null);
        Notification alreadyRead = notification(2L, 10L, LocalDateTime.now().minusMinutes(5), null);
        when(notificationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(unread, alreadyRead));

        NotificationSummaryResponse summary = service.markAllRead(10L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(captor.capture());
        Notification updated = captor.getValue();
        assertEquals(1L, updated.getId());
        assertNotNull(updated.getReadTime());
        assertEquals(0, summary.getUnreadCount());
        assertEquals(2, summary.getVisibleCount());
        assertEquals(2, summary.getReadCount());
    }

    @Test
    void deleteReadHidesOnlyReadVisibleNotificationsAndReturnsSummary() {
        Notification read = notification(1L, 10L, LocalDateTime.now().minusMinutes(5), null);
        Notification unread = notification(2L, 10L, null, null);
        when(notificationMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(read, unread));

        NotificationSummaryResponse summary = service.deleteRead(10L);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).updateById(captor.capture());
        Notification updated = captor.getValue();
        assertEquals(1L, updated.getId());
        assertNotNull(updated.getDeletedTime());
        assertEquals(1, summary.getUnreadCount());
        assertEquals(1, summary.getVisibleCount());
        assertEquals(0, summary.getReadCount());
    }

    @Test
    void markOneReadIgnoresOtherUsersNotifications() {
        Notification otherUserNotification = notification(1L, 99L, null, null);
        when(notificationMapper.selectById(1L)).thenReturn(otherUserNotification);

        NotificationSummaryResponse summary = service.markRead(10L, 1L);

        assertEquals(0, summary.getVisibleCount());
    }

    // ===== 直发状态机: 已写入通知记录 与 外部发送成功 拆分 =====

    @Test
    void sendSmsWritesPendingRecordThenMarksSentOnChannelSuccess() {
        int[] insertTimeStatus = new int[1];
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            Notification inserted = inv.getArgument(0, Notification.class);
            insertTimeStatus[0] = inserted.getStatus();
            inserted.setId(501L);
            return 1;
        });
        when(notificationChannelService.sendSms(any())).thenReturn(new SmsSendResult("SENT", "biz-1", null));

        DirectChannelSendResult result = service.sendSms(2004L, 9001L, "订单已出票");

        assertEquals(NotificationService.STATUS_PENDING_SEND, insertTimeStatus[0]); // 写入时: 待发送
        verify(notificationMapper).updateById(any(Notification.class));
        assertEquals(NotificationService.STATUS_SENT, result.getNotification().getStatus()); // 渠道确认后: 已发送
        assertEquals("SENT", result.getDeliveryStatus());
    }

    @Test
    void sendSmsMarksRecordFailedWhenChannelFails() {
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(502L);
            return 1;
        });
        when(notificationChannelService.sendSms(any())).thenReturn(new SmsSendResult("FAILED", null, "阿里云限流"));

        DirectChannelSendResult result = service.sendSms(2004L, 9001L, "订单已出票");

        assertEquals(NotificationService.STATUS_SEND_FAILED, result.getNotification().getStatus());
        assertEquals("FAILED", result.getDeliveryStatus());
        assertEquals("阿里云限流", result.getFailureReason());
    }

    @Test
    void sendSmsKeepsRecordPendingWhenChannelSkipped() {
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(503L);
            return 1;
        });
        when(notificationChannelService.sendSms(any())).thenReturn(new SmsSendResult("SKIPPED", null, "短信渠道未配置"));

        DirectChannelSendResult result = service.sendSms(2004L, 9001L, "订单已出票");

        assertEquals(NotificationService.STATUS_PENDING_SEND, result.getNotification().getStatus());
        assertEquals("SKIPPED", result.getDeliveryStatus());
    }

    @Test
    void sendEmailWritesPendingRecordThenMarksSentOnChannelSuccess() {
        int[] insertTimeStatus = new int[1];
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            Notification inserted = inv.getArgument(0, Notification.class);
            insertTimeStatus[0] = inserted.getStatus();
            inserted.setId(504L);
            return 1;
        });
        when(notificationChannelService.sendEmail(any())).thenReturn(new EmailSendResult("SENT", "msg-1", null));

        DirectChannelSendResult result = service.sendEmail(2004L, 9001L, "电子票已发送");

        assertEquals(NotificationService.STATUS_PENDING_SEND, insertTimeStatus[0]);
        assertEquals(NotificationService.STATUS_SENT, result.getNotification().getStatus());
        assertEquals("SENT", result.getDeliveryStatus());
    }

    @Test
    void sendEmailMarksRecordFailedWhenChannelFails() {
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(505L);
            return 1;
        });
        when(notificationChannelService.sendEmail(any())).thenReturn(new EmailSendResult("FAILED", null, "SMTP 连接失败"));

        DirectChannelSendResult result = service.sendEmail(2004L, 9001L, "电子票已发送");

        assertEquals(NotificationService.STATUS_SEND_FAILED, result.getNotification().getStatus());
        assertEquals("FAILED", result.getDeliveryStatus());
        assertEquals("SMTP 连接失败", result.getFailureReason());
    }

    @Test
    void sendSmsPassesRecipientResolutionToChannelService() {
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(506L);
            return 1;
        });
        when(notificationChannelService.sendSms(any())).thenReturn(new SmsSendResult("SENT", "biz-2", null));

        service.sendSms(2004L, null, "通知内容");

        ArgumentCaptor<SmsSendRequest> requestCaptor = ArgumentCaptor.forClass(SmsSendRequest.class);
        verify(notificationChannelService).sendSms(requestCaptor.capture());
        assertEquals(2004L, requestCaptor.getValue().getUserId());
        assertEquals("通知内容", requestCaptor.getValue().getContent());
        assertEquals("DIRECT_SMS", requestCaptor.getValue().getEventType());
    }

    @Test
    void sendEmailPassesContentToChannelService() {
        when(notificationMapper.insert(any())).thenAnswer(inv -> {
            inv.getArgument(0, Notification.class).setId(507L);
            return 1;
        });
        when(notificationChannelService.sendEmail(any())).thenReturn(new EmailSendResult("SENT", "msg-2", null));

        service.sendEmail(2004L, null, "通知内容");

        ArgumentCaptor<EmailSendRequest> requestCaptor = ArgumentCaptor.forClass(EmailSendRequest.class);
        verify(notificationChannelService).sendEmail(requestCaptor.capture());
        assertEquals(2004L, requestCaptor.getValue().getUserId());
        assertEquals("通知内容", requestCaptor.getValue().getContent());
    }

    @Test
    void createInternalMessageMarksInAppRecordSentImmediately() {
        InternalNotificationRequest request = new InternalNotificationRequest();
        request.setUserId(10L);
        request.setContent("站内通知");

        service.createInternalMessage(request);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertEquals(NotificationService.STATUS_SENT, captor.getValue().getStatus());
        verify(notificationChannelService, never()).sendSms(any());
        verify(notificationChannelService, times(0)).sendEmail(any());
    }

    private static Notification notification(Long id, Long userId, LocalDateTime readTime, LocalDateTime deletedTime) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setUserId(userId);
        notification.setType("SUPPORT_REPLY");
        notification.setContent("客服消息");
        notification.setStatus(1);
        notification.setCreateTime(LocalDateTime.now());
        notification.setReadTime(readTime);
        notification.setDeletedTime(deletedTime);
        return notification;
    }
}
