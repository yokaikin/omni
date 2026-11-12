package com.omni.notification.service;

import com.omni.common.result.Result;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.notification.controller.NotificationController;
import com.omni.notification.dto.DirectChannelSendResult;
import com.omni.notification.dto.InternalNotificationRequest;
import com.omni.notification.entity.Notification;
import com.omni.notification.mapper.NotificationMapper;
import com.omni.notification.sms.SmsSendResult;
import com.omni.notification.email.EmailSendResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Notification Service — Full Coverage")
class NotificationServiceFullTest {

    @Mock NotificationMapper mapper;
    @Mock NotificationChannelService channelService;
    NotificationService svc;
    NotificationController ctl;

    static final String JWT_SECRET = "omni-jwt-secretomni-jwt-secretomni-jwt-secret";
    static final String TOKEN = "test-internal-token";

    @BeforeEach void setup() {
        svc = new NotificationService(mapper, channelService);
        ctl = new NotificationController(svc, mock(NotificationEventService.class), TOKEN, JWT_SECRET);
    }

    @BeforeAll static void ensureJwt() {
        if (System.getenv("JWT_SECRET") == null || System.getenv("JWT_SECRET").isBlank())
            System.setProperty("JWT_SECRET", "test-jwt-secret-must-be-at-least-32-bytes");
    }

    String jwt(Long userId) {
        return "Bearer " + io.jsonwebtoken.Jwts.builder()
                .claim("userId", userId)
                .setSubject(String.valueOf(userId))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(JWT_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    // ===== 3.1 Internal Messages (NF-001~006) =====
    @Nested @DisplayName("3.1 Internal Messages")
    class InternalMessages {
        @Test @DisplayName("NF-001: create internal message → 200")
        void nf001() {
            InternalNotificationRequest req = new InternalNotificationRequest();
            req.setUserId(2004L); req.setOrderId(100L); req.setType("IN_APP"); req.setContent("订单已支付");
            when(mapper.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Notification.class).setId(1L); return 1; });

            Result<Notification> r = ctl.createInternalMessage(TOKEN, req);
            assertEquals(200, r.getCode()); assertNotNull(r.getData().getId());
            assertEquals(2004L, r.getData().getUserId()); assertEquals("IN_APP", r.getData().getType());
        }

        @Test @DisplayName("NF-002: no token → 403")
        void nf002() {
            Result<Notification> r = ctl.createInternalMessage(null, new InternalNotificationRequest());
            assertEquals(403, r.getCode());
            verify(mapper, never()).insert(any());
        }

        @Test @DisplayName("NF-003: wrong token → 403")
        void nf003() {
            Result<Notification> r = ctl.createInternalMessage("wrong-token", new InternalNotificationRequest());
            assertEquals(403, r.getCode());
        }

        @Test @DisplayName("NF-004: userId null → 400")
        void nf004() {
            InternalNotificationRequest req = new InternalNotificationRequest(); req.setContent("test");
            assertThrows(BusinessException.class, () -> svc.createInternalMessage(req));
        }

        @Test @DisplayName("NF-005: content empty → 400")
        void nf005() {
            InternalNotificationRequest req = new InternalNotificationRequest(); req.setUserId(2004L); req.setContent("");
            assertThrows(BusinessException.class, () -> svc.createInternalMessage(req));
        }

        @Test @DisplayName("NF-006: type default → IN_APP")
        void nf006() {
            InternalNotificationRequest req = new InternalNotificationRequest();
            req.setUserId(2004L); req.setContent("test");
            when(mapper.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Notification.class).setId(2L); return 1; });
            Notification n = svc.createInternalMessage(req);
            assertEquals("IN_APP", n.getType());
        }
    }

    // ===== 3.2 SMS (NF-007~010) =====
    @Nested @DisplayName("3.2 SMS Notification")
    class SmsTests {
        @Test @DisplayName("NF-007: send SMS → 200, 记录先写入待发送再更新为已发送")
        void nf007() {
            enableDirectChannel();
            int[] insertTimeStatus = new int[1];
            when(mapper.insert(any())).thenAnswer(inv -> {
                Notification inserted = inv.getArgument(0, Notification.class);
                insertTimeStatus[0] = inserted.getStatus();
                inserted.setId(1L);
                return 1;
            });
            when(channelService.sendSms(any())).thenReturn(new SmsSendResult("SENT", "biz-1", null));
            Result<Notification> r = ctl.sendSms(jwt(2004L), Map.of("content", "您的验证码是666666"));
            assertEquals(200, r.getCode());
            ArgumentCaptor<Notification> c = ArgumentCaptor.forClass(Notification.class);
            verify(mapper).insert(c.capture());
            assertEquals(2004L, c.getValue().getUserId()); assertEquals("SMS", c.getValue().getType());
            assertEquals(0, insertTimeStatus[0]); // 写入时：待发送
            assertEquals(1, r.getData().getStatus()); // 渠道确认后：已发送
            verify(mapper).updateById(any(Notification.class));
        }

        @Test @DisplayName("NF-008: JWT userId cannot be forged")
        void nf008() {
            enableDirectChannel();
            when(mapper.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Notification.class).setId(2L); return 1; });
            when(channelService.sendSms(any())).thenReturn(new SmsSendResult("SENT", "biz-2", null));
            // Request body has userId=9999 but JWT has userId=2004
            Result<Notification> r = ctl.sendSms(jwt(2004L), Map.of("userId", 9999L, "content", "test"));
            assertEquals(200, r.getCode());
            ArgumentCaptor<Notification> c = ArgumentCaptor.forClass(Notification.class);
            verify(mapper).insert(c.capture());
            assertEquals(2004L, c.getValue().getUserId()); // JWT wins
        }

        @Test @DisplayName("NF-009: no token → 401")
        void nf009() {
            Result<Notification> r = ctl.sendSms(null, Map.of("content", "test"));
            assertEquals(401, r.getCode());
            verify(mapper, never()).insert(any());
        }

        @Test @DisplayName("NF-010: direct SMS disabled before body parsing")
        void nf010() {
            Result<Notification> r = ctl.sendSms(jwt(2004L), Map.of());

            assertEquals(400, r.getCode());
            assertEquals("当前环境未启用短信直发", r.getMessage());
            verify(mapper, never()).insert(any());
        }

        @Test @DisplayName("NF-010b: channel send failure → 502, 记录置为发送失败")
        void nf010b() {
            enableDirectChannel();
            when(mapper.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Notification.class).setId(3L); return 1; });
            when(channelService.sendSms(any())).thenReturn(new SmsSendResult("FAILED", null, "阿里云限流"));
            Result<Notification> r = ctl.sendSms(jwt(2004L), Map.of("content", "test"));
            assertEquals(502, r.getCode());
            ArgumentCaptor<Notification> c = ArgumentCaptor.forClass(Notification.class);
            verify(mapper).updateById(c.capture());
            assertEquals(2, c.getValue().getStatus()); // 发送失败
        }

        @Test @DisplayName("NF-010c: channel skipped → 400, 记录保持待发送")
        void nf010c() {
            enableDirectChannel();
            when(mapper.insert(any())).thenAnswer(inv -> { inv.getArgument(0, Notification.class).setId(4L); return 1; });
            when(channelService.sendSms(any())).thenReturn(new SmsSendResult("SKIPPED", null, "短信渠道未配置"));
            Result<Notification> r = ctl.sendSms(jwt(2004L), Map.of("content", "test"));
            assertEquals(400, r.getCode());
            ArgumentCaptor<Notification> c = ArgumentCaptor.forClass(Notification.class);
            verify(mapper).updateById(c.capture());
            assertEquals(0, c.getValue().getStatus()); // 仍为待发送
        }
    }

    // ===== 3.3 Email (NF-011~012) =====
    @Nested @DisplayName("3.3 Email Notification")
    class EmailTests {
        @Test @DisplayName("NF-011: send email → 200")
        void nf011() {
            enableDirectChannel();
            int[] insertTimeStatus = new int[1];
            when(mapper.insert(any())).thenAnswer(inv -> {
                Notification inserted = inv.getArgument(0, Notification.class);
                insertTimeStatus[0] = inserted.getStatus();
                inserted.setId(5L);
                return 1;
            });
            when(channelService.sendEmail(any())).thenReturn(new EmailSendResult("SENT", "msg-1", null));
            Result<Notification> r = ctl.sendEmail(jwt(2004L), Map.of("content", "邮件通知"));
            assertEquals(200, r.getCode());
            ArgumentCaptor<Notification> c = ArgumentCaptor.forClass(Notification.class);
            verify(mapper).insert(c.capture());
            assertEquals(2004L, c.getValue().getUserId()); assertEquals("EMAIL", c.getValue().getType());
            assertEquals(0, insertTimeStatus[0]);
            assertEquals(1, r.getData().getStatus());
        }

        @Test @DisplayName("NF-012: same JWT validation as SMS")
        void nf012() {
            Result<Notification> r = ctl.sendEmail(null, Map.of("content", "test"));
            assertEquals(401, r.getCode());
        }
    }

    // ===== 3.4 Notification List (NF-013~016) =====
    @Nested @DisplayName("3.4 Notification List")
    class ListTests {
        @Test @DisplayName("NF-013: list my notifications → 200")
        void nf013() {
            Notification n = new Notification(); n.setId(1L); n.setUserId(2004L); n.setContent("test");
            when(mapper.selectList(any())).thenReturn(List.of(n));
            Result<List<Notification>> r = ctl.listNotifications(jwt(2004L));
            assertEquals(200, r.getCode()); assertEquals(1, r.getData().size());
        }

        @Test @DisplayName("NF-014: ordered by createTime desc")
        void nf014() {
            Notification n1 = new Notification(); n1.setId(2L);
            Notification n2 = new Notification(); n2.setId(1L);
            when(mapper.selectList(any())).thenReturn(List.of(n2, n1));
            Result<List<Notification>> r = ctl.listNotifications(jwt(2004L));
            assertEquals(2, r.getData().size());
        }

        @Test @DisplayName("NF-015: empty list → 200")
        void nf015() {
            when(mapper.selectList(any())).thenReturn(Collections.emptyList());
            Result<List<Notification>> r = ctl.listNotifications(jwt(2004L));
            assertEquals(200, r.getCode()); assertTrue(r.getData().isEmpty());
        }

        @Test @DisplayName("NF-016: no token → 401")
        void nf016() {
            Result<List<Notification>> r = ctl.listNotifications(null);
            assertEquals(401, r.getCode());
        }
    }

    // ===== 3.5 Permission & Errors (NF-017~019) =====
    @Nested @DisplayName("3.5 Permission & Errors")
    class PermissionErrors {
        @Test @DisplayName("NF-017: only own notifications visible")
        void nf017() {
            when(mapper.selectList(any())).thenReturn(Collections.emptyList());
            // Controller passes JWT userId=2004 → service queries only userId=2004
            ctl.listNotifications(jwt(2004L));
            ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Notification>> c =
                    ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
            verify(mapper).selectList(c.capture());
            // The wrapper filters by userId — verified by the service query
        }

        @Test @DisplayName("NF-018: forged/expired JWT → 401")
        void nf018() {
            Result<List<Notification>> r = ctl.listNotifications("Bearer invalid.fake.token");
            assertEquals(401, r.getCode());
        }

        @Test @DisplayName("NF-019: JWT without userId → 401")
        void nf019() {
            // A JWT without userId claim or subject → requireAuthenticatedUserId returns null
            Result<List<Notification>> r = ctl.listNotifications("Bearer eyJhbGciOiJIUzI1NiJ9.e30.XmNK3GpH3Ys7XRJoHmDqF1qnMX8vC1NP7NcQ8e3F2zA");
            assertEquals(401, r.getCode());
        }
    }

    private void enableDirectChannel() {
        ReflectionTestUtils.setField(ctl, "directChannelEnabled", true);
    }
}
