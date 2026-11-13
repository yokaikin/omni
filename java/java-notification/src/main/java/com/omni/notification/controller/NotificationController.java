package com.omni.notification.controller;

import com.omni.common.result.Result;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.notification.dto.ChannelStatusResponse;
import com.omni.notification.dto.DirectChannelSendResult;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.dto.NotificationDeliveryStatusResponse;
import com.omni.notification.dto.NotificationSummaryResponse;
import com.omni.notification.entity.Notification;
import com.omni.notification.service.NotificationChannelService;
import com.omni.notification.service.NotificationEventService;
import com.omni.notification.service.NotificationService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 通知接口
 */
@RestController
@RequestMapping("/api/notification")
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationEventService notificationEventService;
    private final String internalApiToken;
    private final String jwtSecret;

    @Value("${omni.notification.direct-channel.enabled:false}")
    private boolean directChannelEnabled;

    @Value("${omni.notification.sms.provider:disabled}")
    private String smsProvider;

    @Value("${omni.notification.email.provider:disabled}")
    private String emailProvider;

    @Value("${omni.object-storage.provider:disabled}")
    private String objectStorageProvider;

    @Value("${omni.notification.delivery.retry.max-attempts:3}")
    private int deliveryRetryMaxAttempts;

    public NotificationController(NotificationService notificationService,
                                  NotificationEventService notificationEventService,
                                  @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken,
                                  @Value("${jwt.secret:${JWT_SECRET:}}") String jwtSecret) {
        this.notificationService = notificationService;
        this.notificationEventService = notificationEventService;
        this.internalApiToken = internalApiToken;
        this.jwtSecret = jwtSecret;
    }

    @PostMapping("/internal/messages")
    public Result<Notification> createInternalMessage(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                      @RequestBody(required = false) InternalNotificationRequest request) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(notificationService.createInternalMessage(request));
    }

    @PostMapping("/internal/events")
    public Result<Void> createInternalEvent(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                            @RequestBody(required = false) NotificationEventMessage message) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        notificationEventService.processEvent(message);
        return Result.success();
    }

    /**
     * 发送短信通知
     */
    @PostMapping("/send-sms")
    public Result<Notification> sendSms(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Map<String, Object> body) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        if (!directChannelEnabled) {
            return Result.fail(400, "当前环境未启用短信直发");
        }
        Long orderId = body.get("orderId") != null ? Long.valueOf(body.get("orderId").toString()) : null;
        String content = body.get("content").toString();
        DirectChannelSendResult result = notificationService.sendSms(userId, orderId, content);
        return toDirectSendResponse(result, "短信");
    }

    /**
     * 发送邮件通知
     */
    @PostMapping("/send-email")
    public Result<Notification> sendEmail(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Map<String, Object> body) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        if (!directChannelEnabled) {
            return Result.fail(400, "当前环境未启用邮件直发");
        }
        Long orderId = body.get("orderId") != null ? Long.valueOf(body.get("orderId").toString()) : null;
        String content = body.get("content").toString();
        DirectChannelSendResult result = notificationService.sendEmail(userId, orderId, content);
        return toDirectSendResponse(result, "邮件");
    }

    /**
     * 渠道配置状态查询（internal）：用于运维确认短信/邮件/对象存储接入情况。
     */
    @GetMapping("/internal/channel-status")
    public Result<ChannelStatusResponse> getChannelStatus(
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        ChannelStatusResponse response = new ChannelStatusResponse();
        response.setSmsProvider(smsProvider);
        response.setEmailProvider(emailProvider);
        response.setObjectStorageProvider(objectStorageProvider);
        response.setDirectChannelEnabled(directChannelEnabled);
        response.setDeliveryRetryMaxAttempts(deliveryRetryMaxAttempts);
        return Result.success(response);
    }

    @GetMapping("/internal/deliveries/status")
    public Result<NotificationDeliveryStatusResponse> getDeliveryStatus(
            @RequestParam String eventId,
            @RequestParam String channel,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        String normalizedEventId = eventId.trim();
        String normalizedChannel = channel.trim().toUpperCase();
        if (!StringUtils.hasText(normalizedEventId)
                || normalizedEventId.length() > 120
                || !(NotificationEventService.CHANNEL_SMS.equals(normalizedChannel)
                || NotificationEventService.CHANNEL_EMAIL.equals(normalizedChannel))) {
            return Result.fail(400, "投递查询参数不正确");
        }
        return Result.success(notificationEventService.getDeliveryStatus(normalizedEventId, normalizedChannel));
    }

    private Result<Notification> toDirectSendResponse(DirectChannelSendResult result, String channelName) {
        if (result == null || result.getNotification() == null) {
            return Result.fail(500, channelName + "发送失败");
        }
        if (NotificationChannelService.STATUS_SENT.equals(result.getDeliveryStatus())) {
            return Result.success(result.getNotification());
        }
        if (NotificationChannelService.STATUS_SKIPPED.equals(result.getDeliveryStatus())) {
            return Result.fail(400, channelName + "渠道未配置: "
                    + (StringUtils.hasText(result.getFailureReason()) ? result.getFailureReason() : "渠道未启用"));
        }
        return Result.fail(502, channelName + "发送失败: "
                + (StringUtils.hasText(result.getFailureReason()) ? result.getFailureReason() : "未知原因"));
    }

    /**
     * 用户通知列表
     */
    @GetMapping("/list")
    public Result<List<Notification>> listNotifications(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        List<Notification> notifications = notificationService.listNotifications(userId);
        return Result.success(notifications);
    }

    @GetMapping("/internal/users/{userId}/notifications")
    public Result<List<Notification>> listInternalUserNotifications(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "5") Integer limit,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(limitList(notificationService.listNotifications(userId), limit));
    }

    @GetMapping("/summary")
    public Result<NotificationSummaryResponse> getSummary(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(notificationService.getSummary(userId));
    }

    @PostMapping("/read-all")
    public Result<NotificationSummaryResponse> markAllRead(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(notificationService.markAllRead(userId));
    }

    @PostMapping("/{id}/read")
    public Result<NotificationSummaryResponse> markRead(
            @PathVariable("id") Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(notificationService.markRead(userId, id));
    }

    @DeleteMapping("/read")
    public Result<NotificationSummaryResponse> deleteRead(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(notificationService.deleteRead(userId));
    }

    @DeleteMapping("/{id}")
    public Result<NotificationSummaryResponse> deleteOne(
            @PathVariable("id") Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = requireAuthenticatedUserId(authorization);
        if (userId == null) {
            return Result.fail(401, "未登录");
        }
        return Result.success(notificationService.deleteOne(userId, id));
    }

    private Long requireAuthenticatedUserId(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        if (!StringUtils.hasText(jwtSecret)) {
            return null;
        }
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                    .build()
                    .parseClaimsJws(authorization.substring("Bearer ".length()))
                    .getBody();
            Object userId = claims.get("userId");
            if (userId == null) {
                userId = claims.getSubject();
            }
            if (userId == null) {
                return null;
            }
            return Long.valueOf(String.valueOf(userId));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> List<T> limitList(List<T> items, Integer limit) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        int size = limit == null ? 5 : Math.max(0, Math.min(limit, 20));
        return items.stream().limit(size).collect(Collectors.toList());
    }
}
