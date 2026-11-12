package com.omni.user.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.omni.common.result.Result;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.user.controller.SupportController;
import com.omni.user.dto.*;
import com.omni.user.entity.SupportConversation;
import com.omni.user.entity.SupportConversationAudit;
import com.omni.user.entity.SupportConversationNote;
import com.omni.user.entity.SupportConversationTag;
import com.omni.user.entity.SupportMessage;
import com.omni.user.entity.User;
import com.omni.user.mapper.SupportConversationAuditMapper;
import com.omni.user.mapper.SupportConversationMapper;
import com.omni.user.mapper.SupportConversationNoteMapper;
import com.omni.user.mapper.SupportConversationTagMapper;
import com.omni.user.mapper.SupportMessageMapper;
import com.omni.user.mapper.SupportQuickReplyMapper;
import com.omni.user.mapper.UserMapper;
import com.omni.user.mq.NotificationMqProducer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Customer Support System")
class CustomerSupportFullTest {

    @Mock SupportConversationMapper conversationMapper;
    @Mock SupportMessageMapper messageMapper;
    @Mock SupportConversationNoteMapper noteMapper;
    @Mock SupportConversationTagMapper tagMapper;
    @Mock SupportConversationAuditMapper auditMapper;
    @Mock SupportQuickReplyMapper quickReplyMapper;
    @Mock UserMapper userMapper;
    @Mock NotificationMqProducer notificationProducer;
    @Mock CustomerSupportService customerSupportService;
    @Mock HelpCenterService helpCenterService;
    @Mock SupportAccountService supportAccountService;
    @Mock SupportContextService supportContextService;

    private CustomerSupportService service;
    private SupportController controller;

    @BeforeAll static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""), SupportConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""), SupportMessage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""), SupportConversationNote.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""), SupportConversationTag.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""), SupportConversationAudit.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(),""), User.class);
    }
    @BeforeAll static void ensureJwt() {
        if (System.getenv("JWT_SECRET") == null || System.getenv("JWT_SECRET").isBlank())
            System.setProperty("JWT_SECRET","test-jwt-secret-must-be-at-least-32-bytes");
    }
    @BeforeEach void setUp() {
        service = new CustomerSupportService(conversationMapper, messageMapper, noteMapper, tagMapper, auditMapper,
                quickReplyMapper, userMapper, new SupportAiService((q,k)->Optional.empty()),
                notificationProducer, new SupportPushService(), "test-token", Runnable::run);
        controller = new SupportController(helpCenterService, customerSupportService, supportAccountService, supportContextService, new SupportPushService());
    }

    // ============ 3.1 Start Conversation (CS-001~004) ============
    @Nested @DisplayName("3.1 Start Conversation")
    class StartConversation {
        @Test @DisplayName("CS-001: AI conversation")
        void aiConversation() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            when(conversationMapper.insert(any())).thenAnswer(inv->{inv.getArgument(0,SupportConversation.class).setId(100L);return 1;});
            when(messageMapper.insert(any())).thenReturn(1);
            SupportConversationRequest r = new SupportConversationRequest();
            r.setSubject("Ticket"); r.setInitialMessage("How to get ticket"); r.setPreferHuman(false);
            SupportConversationResponse resp = service.startConversation(2004L, r);
            assertEquals(100L, resp.getId()); assertEquals("OPEN", resp.getStatus());
            assertEquals("AI", resp.getSourceType());
            verify(messageMapper, atLeastOnce()).insert(any());
        }
        @Test @DisplayName("CS-002: Human conversation")
        void humanConversation() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            when(conversationMapper.insert(any())).thenAnswer(inv->{inv.getArgument(0,SupportConversation.class).setId(101L);return 1;});
            when(messageMapper.insert(any())).thenReturn(1);
            SupportConversationRequest r = new SupportConversationRequest();
            r.setSubject("Refund"); r.setInitialMessage("I need refund"); r.setPreferHuman(true);
            SupportConversationResponse resp = service.startConversation(2004L, r);
            assertEquals("WAITING_AGENT", resp.getStatus()); assertEquals("HUMAN", resp.getSourceType());
        }
        @Test @DisplayName("CS-003: Empty subject allowed")
        void emptySubject() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            when(conversationMapper.insert(any())).thenAnswer(inv->{inv.getArgument(0,SupportConversation.class).setId(102L);return 1;});
            SupportConversationRequest r = new SupportConversationRequest(); r.setSubject("");
            SupportConversationResponse resp = service.startConversation(2004L, r);
            assertEquals(102L, resp.getId());
        }
        @Test @DisplayName("CS-004: List my conversations")
        void listMine() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            SupportConversation c = conv(200L,2004L,"OPEN","AI");
            when(conversationMapper.selectList(any())).thenReturn(List.of(c));
            List<SupportConversationResponse> result = service.listMine(2004L);
            assertEquals(1, result.size()); assertEquals(200L, result.get(0).getId());
        }
    }

    // ============ 3.2 Messages (CS-005~008) ============
    @Nested @DisplayName("3.2 Messages")
    class Messages {
        @Test @DisplayName("CS-005: User sends message")
        void userSends() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            SupportConversation c = convOpen(300L,2004L);
            when(conversationMapper.selectById(300L)).thenReturn(c);
            when(messageMapper.insert(any())).thenReturn(1);
            SupportMessageRequest r = new SupportMessageRequest(); r.setContent("Order status?");
            SupportMessageResponse resp = service.sendMessage(2004L,300L, r);
            assertEquals("USER", resp.getSenderType());
        }
        @Test @DisplayName("CS-006: AI auto-reply")
        void aiAutoReply() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            SupportConversation c = convAi(301L,2004L);
            when(conversationMapper.selectById(301L)).thenReturn(c);
            when(messageMapper.insert(any())).thenReturn(1);
            SupportMessageRequest r = new SupportMessageRequest(); r.setContent("refund question");
            service.sendMessage(2004L,301L, r);
            verify(messageMapper, atLeast(2)).insert(any());
        }
        @Test @DisplayName("CS-007: Human mode no auto-reply")
        void humanNoAutoReply() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            SupportConversation c = convHuman(302L,2004L);
            when(conversationMapper.selectById(302L)).thenReturn(c);
            when(messageMapper.insert(any())).thenReturn(1);
            SupportMessageRequest r = new SupportMessageRequest(); r.setContent("help");
            service.sendMessage(2004L,302L, r);
            verify(messageMapper, times(1)).insert(any());
        }
        @Test @DisplayName("CS-008: List messages with senderType")
        void listMessages() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            SupportConversation c = conv(400L,2004L,"OPEN","AI");
            when(conversationMapper.selectById(400L)).thenReturn(c);
            SupportMessage m1 = msg(1L,"USER","Q"); SupportMessage m2 = msg(2L,"AI","A");
            when(messageMapper.selectList(any())).thenReturn(List.of(m1,m2));
            List<SupportMessageResponse> result = service.listMessages(2004L,400L);
            assertEquals(2, result.size());
            assertEquals("USER", result.get(0).getSenderType());
            assertEquals("AI", result.get(1).getSenderType());
        }
    }

    // ============ 3.3 AI Reply (CS-009~014) ============
    @Nested @DisplayName("3.3 AI Reply")
    class AiReply {
        private SupportAiService ai = new SupportAiService((q,k)->Optional.empty());
        @Test @DisplayName("CS-009: Ticket question matches keyword")
        void ticketKeyword() { assertAiContains("电子票在哪里看", "票夹"); }
        @Test @DisplayName("CS-010: Refund question matches keyword")
        void refundKeyword() { assertAiContains("怎么退款退票", "退款"); }
        @Test @DisplayName("CS-011: Transfer question matches keyword")
        void transferKeyword() { assertAiContains("可以转赠给朋友吗", "转赠"); }
        @Test @DisplayName("CS-012: Real-name question matches keyword")
        void realNameKeyword() { assertAiContains("为什么要实名认证", "实名"); }
        @Test @DisplayName("CS-013: Unmatched falls back to generic")
        void unmatchedFallback() {
            String ans = ai.answer("xyz_random_123");
            assertNotNull(ans); assertTrue(ans.length() > 10);
        }
        @Test @DisplayName("CS-014: Works without Ollama (keyword fallback)")
        void ollamaFallback() { String ans = ai.answer("退款流程"); assertNotNull(ans); }
        private void assertAiContains(String q, String kw) {
            String ans = ai.answer(q); assertNotNull(ans);
            assertTrue(ans.contains(kw), "Expected '"+kw+"' in: "+ans);
        }
    }

    // ============ 3.4 Handoff (CS-015~016) ============
    @Nested @DisplayName("3.4 Handoff")
    class Handoff {
        @Test @DisplayName("CS-015: Handoff to human")
        void handoffToHuman() {
            SupportConversation c = conv(500L,2004L,"OPEN","AI");
            when(conversationMapper.selectById(500L)).thenReturn(c);
            when(messageMapper.insert(any())).thenReturn(1);
            SupportConversationResponse r = service.handoff(2004L,500L);
            assertEquals("WAITING_AGENT", r.getStatus()); assertEquals("HUMAN", r.getSourceType());
        }
        @Test @DisplayName("CS-016: Other user handoff rejected")
        void handoffByOtherRejected() {
            SupportConversation c = conv(500L,2004L,"OPEN","AI");
            when(conversationMapper.selectById(500L)).thenReturn(c);
            assertThrows(BusinessException.class, ()->service.handoff(2005L,500L));
        }
    }

    // ============ 3.5 Agent Management (CS-017~021) ============
    @Nested @DisplayName("3.5 Agent Management")
    class Agent {
        @Test @DisplayName("CS-017: Agent lists conversations")
        void agentList() {
            when(userMapper.selectById(8001L)).thenReturn(u(8001L,"support"));
            SupportConversation c = conv(600L,2004L,"WAITING_AGENT","HUMAN");
            when(conversationMapper.selectList(any())).thenReturn(List.of(c));
            List<SupportConversationResponse> r = service.listAgentConversations(8001L, null);
            assertEquals(1, r.size());
        }
        @Test @DisplayName("CS-018: Agent claims conversation")
        void agentClaim() {
            when(userMapper.selectById(8001L)).thenReturn(u(8001L,"support"));
            SupportConversation c = conv(601L,2004L,"WAITING_AGENT","HUMAN");
            when(conversationMapper.selectById(601L)).thenReturn(c);
            when(messageMapper.insert(any())).thenReturn(1);
            SupportConversationResponse r = service.claim(8001L,601L);
            assertEquals("ASSIGNED", r.getStatus()); assertEquals(8001L, r.getAssignedAgentId());
        }
        @Test @DisplayName("CS-019: Agent closes conversation")
        void agentClose() {
            when(userMapper.selectById(8001L)).thenReturn(u(8001L,"support"));
            SupportConversation c = conv(602L,2004L,"ASSIGNED","HUMAN");
            c.setAssignedAgentId(8001L);
            when(conversationMapper.selectById(602L)).thenReturn(c);
            when(messageMapper.insert(any())).thenReturn(1);
            SupportConversationResponse r = service.close(8001L,602L);
            assertEquals("CLOSE_REQUESTED", r.getStatus());
        }
        @Test @DisplayName("CS-020: User cannot claim → 403")
        void userClaimRejected() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            assertThrows(BusinessException.class, ()->service.claim(2004L,600L));
        }
        @Test @DisplayName("CS-021: Agent reply sends notification")
        void agentReplyNotifies() {
            when(userMapper.selectById(8001L)).thenReturn(u(8001L,"support"));
            SupportConversation c = convAi(700L,2004L);
            when(conversationMapper.selectById(700L)).thenReturn(c);
            when(messageMapper.insert(any())).thenAnswer(inv -> {
                inv.getArgument(0, SupportMessage.class).setId(701L);
                return 1;
            });
            SupportMessageRequest r = new SupportMessageRequest(); r.setContent("Refund processed");
            service.sendMessage(8001L,700L, r);
            verify(notificationProducer).sendNotificationEvent(any());
        }
    }

    // ============ 3.6 Support Account (CS-022~025) ============
    @Nested @DisplayName("3.6 Support Account")
    class Account {
        @Test @DisplayName("CS-022: Admin lists accounts")
        void adminList() {
            when(supportAccountService.list(2002L)).thenReturn(Collections.emptyList());
            Result<?> r = controller.listSupportAccounts(adminToken());
            assertEquals(200, r.getCode());
        }
        @Test @DisplayName("CS-023: Admin creates account")
        void adminCreate() {
            SupportAccountResponse resp = new SupportAccountResponse(); resp.setId(9001L); resp.setRole("support");
            when(supportAccountService.create(eq(2002L), any())).thenReturn(resp);
            SupportAccountRequest req = new SupportAccountRequest();
            req.setPhone("13800000099"); req.setNickname("Agent Wang"); req.setPassword("123456");
            Result<SupportAccountResponse> r = controller.createSupportAccount(adminToken(), req);
            assertEquals(200, r.getCode()); assertEquals("support", r.getData().getRole());
        }
        @Test @DisplayName("CS-024: Admin deactivates account")
        void adminDeactivate() {
            SupportAccountResponse resp = new SupportAccountResponse(); resp.setId(9001L); resp.setStatus(0);
            when(supportAccountService.deactivate(2002L, 9001L)).thenReturn(resp);
            Result<SupportAccountResponse> r = controller.deactivateSupportAccount(adminToken(), 9001L);
            assertEquals(200, r.getCode()); assertEquals(0, r.getData().getStatus());
        }
        @Test @DisplayName("CS-025: Organizer cannot manage accounts")
        void organizerRejected() {
            // Controller delegates to service with JWT userId=2003
            // Service-level permission check rejects organizer
            when(supportAccountService.list(2003L))
                    .thenThrow(new BusinessException(403, "No permission"));
            assertThrows(BusinessException.class, () -> controller.listSupportAccounts(organizerToken()));
        }
    }

    // ============ 3.7 Help Center (CS-026) ============
    @Nested @DisplayName("3.7 Help Center")
    class HelpCenter {
        @Test @DisplayName("CS-026: List 6 FAQs")
        void listFaqs() {
            when(helpCenterService.listFaqs()).thenReturn(List.of(
                    faq("Tickets","How to get","Check wallet"),
                    faq("Tickets","Transfer","Initiate in wallet"),
                    faq("Refund","How to refund","Check order page"),
                    faq("Real-name","Why real-name","Security policy"),
                    faq("Grab","How to grab","On event page"),
                    faq("Waitlist","How waitlist works","On waitlist page")));
            Result<List<HelpFaqResponse>> r = controller.listFaqs();
            assertEquals(200, r.getCode()); assertEquals(6, r.getData().size());
        }
    }

    // ============ 3.8 Permission & Errors (CS-027~029) ============
    @Nested @DisplayName("3.8 Permission & Errors")
    class PermissionErrors {
        @Test @DisplayName("CS-027: No token → 401")
        void noToken() {
            Result<?> r = controller.startConversation(null, new SupportConversationRequest());
            assertEquals(401, r.getCode());
        }
        @Test @DisplayName("CS-028: View other's conversation → 403")
        void viewOtherConversation() {
            when(userMapper.selectById(2005L)).thenReturn(u(2005L,"user"));
            SupportConversation c = conv(800L,2004L,"OPEN","AI");
            when(conversationMapper.selectById(800L)).thenReturn(c);
            assertThrows(BusinessException.class, ()->service.listMessages(2005L,800L));
        }
        @Test @DisplayName("CS-029: Non-existent conversation → exception")
        void nonExistent() {
            when(userMapper.selectById(2004L)).thenReturn(u(2004L,"user"));
            when(conversationMapper.selectById(999999L)).thenReturn(null);
            assertThrows(BusinessException.class, ()->service.listMessages(2004L,999999L));
        }
    }

    @Nested @DisplayName("3.7 Support Context")
    class SupportContext {
        @Test @DisplayName("CS-030: agent context is scoped by conversation")
        void contextScopedByConversation() {
            SupportContextResponse response = SupportContextResponse.empty(1001L, 2004L, "普通用户", "139****0001");
            when(supportContextService.getContext(2002L, 1001L)).thenReturn(response);

            Result<SupportContextResponse> result = controller.getContext(adminToken(), 1001L);

            assertEquals(200, result.getCode());
            assertEquals(1001L, result.getData().getConversationId());
            assertEquals(2004L, result.getData().getUser().getUserId());
            verify(supportContextService).getContext(2002L, 1001L);
        }
    }

    // ==================== Helpers ====================
    static String adminToken() { return "Bearer "+JwtUtil.generateToken(2002L,"13800000001","admin"); }
    static String organizerToken() { return "Bearer "+JwtUtil.generateToken(2003L,"13800000002","organizer"); }

    static User u(Long id, String role) { User user = new User(); user.setId(id); user.setRole(role); user.setStatus(1); return user; }
    static SupportConversation conv(Long id, Long userId, String status, String source) {
        SupportConversation c = new SupportConversation(); c.setId(id); c.setUserId(userId); c.setStatus(status); c.setSourceType(source); return c;
    }
    static SupportConversation convOpen(Long id, Long userId) { return conv(id, userId, "OPEN", "AI"); }
    static SupportConversation convAi(Long id, Long userId) { SupportConversation c = conv(id, userId, "OPEN", "AI"); return c; }
    static SupportConversation convHuman(Long id, Long userId) { SupportConversation c = conv(id, userId, "WAITING_AGENT", "HUMAN"); return c; }
    static SupportMessage msg(Long id, String sender, String content) { SupportMessage m = new SupportMessage(); m.setId(id); m.setSenderType(sender); m.setContent(content); return m; }
    static HelpFaqResponse faq(String cat, String q, String a) { HelpFaqResponse f = new HelpFaqResponse(); f.setCategory(cat); f.setQuestion(q); f.setAnswer(a); return f; }
}
