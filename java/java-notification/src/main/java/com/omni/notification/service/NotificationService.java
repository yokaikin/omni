package com.omni.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.notification.dto.DirectChannelSendResult;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.dto.NotificationSummaryResponse;
import com.omni.notification.email.EmailSendRequest;
import com.omni.notification.email.EmailSendResult;
import com.omni.notification.entity.Notification;
import com.omni.notification.mapper.NotificationMapper;
import com.omni.notification.sms.SmsSendRequest;
import com.omni.notification.sms.SmsSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知服务。
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 通知记录状态：已写入记录，待发送 */
    public static final int STATUS_PENDING_SEND = 0;
    /** 通知记录状态：外部渠道确认发送成功 */
    public static final int STATUS_SENT = 1;
    /** 通知记录状态：外部渠道发送失败 */
    public static final int STATUS_SEND_FAILED = 2;

    private final NotificationMapper notificationMapper;
    private final NotificationChannelService notificationChannelService;

    public NotificationService(NotificationMapper notificationMapper,
                               NotificationChannelService notificationChannelService) {
        this.notificationMapper = notificationMapper;
        this.notificationChannelService = notificationChannelService;
    }

    /**
     * 发送短信通知：先写入记录（status=0 待发送），外部渠道发送成功后更新为已发送。
     */
    public DirectChannelSendResult sendSms(Long userId, Long orderId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setOrderId(orderId);
        notification.setType("SMS");
        notification.setContent(content);
        applyOrderAction(notification);
        notification.setStatus(STATUS_PENDING_SEND);

        notificationMapper.insert(notification);

        SmsSendRequest request = new SmsSendRequest();
        request.setUserId(userId);
        request.setOrderId(orderId);
        request.setEventId("DIRECT-SMS-" + notification.getId());
        request.setEventType("DIRECT_SMS");
        request.setContent(content);
        SmsSendResult result = notificationChannelService.sendSms(request);
        return finalizeDirectSend(notification, result == null ? null : result.getStatus(),
                result == null ? "短信投递结果为空" : result.getFailureReason());
    }

    /**
     * 发送邮件通知：先写入记录（status=0 待发送），外部渠道发送成功后更新为已发送。
     */
    public DirectChannelSendResult sendEmail(Long userId, Long orderId, String content) {
        Notification notification = new Notification();
        notification.setUserId(userId);
        notification.setOrderId(orderId);
        notification.setType("EMAIL");
        notification.setContent(content);
        applyOrderAction(notification);
        notification.setStatus(STATUS_PENDING_SEND);

        notificationMapper.insert(notification);

        EmailSendRequest request = new EmailSendRequest();
        request.setUserId(userId);
        request.setOrderId(orderId);
        request.setEventId("DIRECT-EMAIL-" + notification.getId());
        request.setEventType("DIRECT_EMAIL");
        request.setSubject("Omni 通知");
        request.setContent(content);
        EmailSendResult result = notificationChannelService.sendEmail(request);
        return finalizeDirectSend(notification, result == null ? null : result.getStatus(),
                result == null ? "邮件投递结果为空" : result.getFailureReason());
    }

    private DirectChannelSendResult finalizeDirectSend(Notification notification, String deliveryStatus,
                                                       String failureReason) {
        int recordStatus = STATUS_SEND_FAILED;
        if (NotificationChannelService.STATUS_SENT.equals(deliveryStatus)) {
            recordStatus = STATUS_SENT;
        } else if (NotificationChannelService.STATUS_SKIPPED.equals(deliveryStatus)) {
            recordStatus = STATUS_PENDING_SEND;
        }
        notification.setStatus(recordStatus);
        notification.setUpdateTime(LocalDateTime.now());
        notificationMapper.updateById(notification);
        log.info("直发通知记录已更新: id={}, type={}, deliveryStatus={}, recordStatus={}",
                notification.getId(), notification.getType(), deliveryStatus, recordStatus);
        return new DirectChannelSendResult(notification, deliveryStatus, failureReason);
    }

    public Notification createInternalMessage(InternalNotificationRequest request) {
        if (request == null || request.getUserId() == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知参数不能为空");
        }
        if (!StringUtils.hasText(request.getContent())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知内容不能为空");
        }
        String actionHref = trimToNull(request.getActionHref());
        String actionLabel = trimToNull(request.getActionLabel());
        String aggregateKey = trimToNull(request.getAggregateKey());
        if (actionHref == null && actionLabel == null && request.getOrderId() != null) {
            actionHref = "/orders/" + request.getOrderId();
            actionLabel = "查看相关订单";
            if (aggregateKey == null) {
                aggregateKey = "ORDER:" + request.getOrderId();
            }
        }
        if (aggregateKey != null) {
            Notification existing = notificationMapper.selectOne(new LambdaQueryWrapper<Notification>()
                    .eq(Notification::getUserId, request.getUserId())
                    .eq(Notification::getAggregateKey, aggregateKey)
                    .isNull(Notification::getDeletedTime)
                    .orderByDesc(Notification::getCreateTime)
                    .last("LIMIT 1"));
            if (existing != null) {
                return existing;
            }
        }
        Notification notification = new Notification();
        notification.setUserId(request.getUserId());
        notification.setOrderId(request.getOrderId());
        notification.setType(StringUtils.hasText(request.getType()) ? request.getType().trim() : "IN_APP");
        notification.setContent(request.getContent().trim());
        notification.setStatus(1);
        notification.setActionHref(actionHref);
        notification.setActionLabel(actionLabel);
        notification.setAggregateKey(aggregateKey);
        notificationMapper.insert(notification);
        return notification;
    }

    /**
     * 通知列表
     */
    public List<Notification> listNotifications(Long userId) {
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Notification::getUserId, userId)
               .isNull(Notification::getDeletedTime)
               .orderByDesc(Notification::getCreateTime);
        return visibleNotifications(notificationMapper.selectList(wrapper));
    }

    public NotificationSummaryResponse getSummary(Long userId) {
        return buildSummary(listNotifications(userId));
    }

    @Transactional
    public NotificationSummaryResponse markAllRead(Long userId) {
        List<Notification> notifications = listNotifications(userId);
        LocalDateTime now = LocalDateTime.now();
        for (Notification notification : notifications) {
            if (notification.getReadTime() == null) {
                notification.setReadTime(now);
                notification.setUpdateTime(now);
                notificationMapper.updateById(notification);
            }
        }
        return buildSummary(notifications);
    }

    @Transactional
    public NotificationSummaryResponse markRead(Long userId, Long notificationId) {
        if (notificationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知ID不能为空");
        }
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification != null
                && userId != null
                && userId.equals(notification.getUserId())
                && notification.getDeletedTime() == null
                && notification.getReadTime() == null) {
            LocalDateTime now = LocalDateTime.now();
            notification.setReadTime(now);
            notification.setUpdateTime(now);
            notificationMapper.updateById(notification);
        }
        return getSummary(userId);
    }

    @Transactional
    public NotificationSummaryResponse deleteRead(Long userId) {
        List<Notification> notifications = listNotifications(userId);
        LocalDateTime now = LocalDateTime.now();
        for (Notification notification : notifications) {
            if (notification.getReadTime() != null && notification.getDeletedTime() == null) {
                notification.setDeletedTime(now);
                notification.setUpdateTime(now);
                notificationMapper.updateById(notification);
            }
        }
        return buildSummary(visibleNotifications(notifications));
    }

    @Transactional
    public NotificationSummaryResponse deleteOne(Long userId, Long notificationId) {
        if (notificationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "通知ID不能为空");
        }
        Notification notification = notificationMapper.selectById(notificationId);
        if (notification != null
                && userId != null
                && userId.equals(notification.getUserId())
                && notification.getDeletedTime() == null) {
            LocalDateTime now = LocalDateTime.now();
            notification.setDeletedTime(now);
            notification.setUpdateTime(now);
            notificationMapper.updateById(notification);
        }
        return getSummary(userId);
    }

    private List<Notification> visibleNotifications(List<Notification> notifications) {
        if (notifications == null) {
            return Collections.emptyList();
        }
        return notifications.stream()
                .filter(notification -> notification.getDeletedTime() == null)
                .collect(Collectors.toList());
    }

    private NotificationSummaryResponse buildSummary(List<Notification> notifications) {
        List<Notification> visible = visibleNotifications(notifications);
        int unreadCount = 0;
        int readCount = 0;
        Map<String, Integer> typeCounts = new LinkedHashMap<>();
        for (Notification notification : visible) {
            if (notification.getReadTime() == null) {
                unreadCount++;
            } else {
                readCount++;
            }
            String type = StringUtils.hasText(notification.getType()) ? notification.getType() : "IN_APP";
            typeCounts.put(type, typeCounts.getOrDefault(type, 0) + 1);
        }
        return new NotificationSummaryResponse(unreadCount, visible.size(), readCount, typeCounts);
    }

    private void applyOrderAction(Notification notification) {
        if (notification.getOrderId() == null) {
            return;
        }
        notification.setActionHref("/orders/" + notification.getOrderId());
        notification.setActionLabel("查看相关订单");
        notification.setAggregateKey("ORDER:" + notification.getOrderId());
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
