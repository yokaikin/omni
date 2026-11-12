package com.omni.user.service;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.exception.BusinessException;
import com.omni.user.mq.NotificationMqProducer;
import com.omni.user.dto.SupportAuditResponse;
import com.omni.user.dto.SupportCloseRejectRequest;
import com.omni.user.dto.SupportCloseRequest;
import com.omni.user.dto.SupportConversationRequest;
import com.omni.user.dto.SupportConversationResponse;
import com.omni.user.dto.SupportMessageRequest;
import com.omni.user.dto.SupportMessageResponse;
import com.omni.user.dto.SupportNoteRequest;
import com.omni.user.dto.SupportNoteResponse;
import com.omni.user.dto.SupportTagUpdateRequest;
import com.omni.user.dto.SupportTransferRequest;
import com.omni.user.entity.SupportAccount;
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
import com.omni.user.mapper.SupportAccountMapper;
import com.omni.user.mapper.UserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CustomerSupportServiceTest {

    @BeforeAll
    static void initMybatisPlusMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SupportConversation.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SupportMessage.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SupportConversationNote.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SupportConversationTag.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SupportConversationAudit.class);
    }

    private final SupportConversationMapper conversationMapper = mock(SupportConversationMapper.class);
    private final SupportMessageMapper messageMapper = mock(SupportMessageMapper.class);
    private final SupportConversationNoteMapper noteMapper = mock(SupportConversationNoteMapper.class);
    private final SupportConversationTagMapper tagMapper = mock(SupportConversationTagMapper.class);
    private final SupportConversationAuditMapper auditMapper = mock(SupportConversationAuditMapper.class);
    private final SupportQuickReplyMapper quickReplyMapper = mock(SupportQuickReplyMapper.class);
    private final SupportAccountMapper supportAccountMapper = mock(SupportAccountMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final NotificationMqProducer notificationProducer = mock(NotificationMqProducer.class);
    private final CustomerSupportService service = new CustomerSupportService(
            conversationMapper,
            messageMapper,
            noteMapper,
            tagMapper,
            auditMapper,
            quickReplyMapper,
            supportAccountMapper,
            userMapper,
            new SupportAiService((question, projectKnowledge) -> java.util.Optional.empty()),
            notificationProducer,
            new SupportPushService(),
            "internal-token",
            Runnable::run
        );

    @Test
    void startsAiConversationAndPersistsUserAndAiMessages() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        AtomicReference<SupportConversation> insertedConversation = new AtomicReference<>();
        when(conversationMapper.insert(any())).thenAnswer(invocation -> {
            SupportConversation conversation = invocation.getArgument(0);
            conversation.setId(99L);
            insertedConversation.set(conversation);
            return 1;
        });
        when(conversationMapper.selectById(99L)).thenAnswer(invocation -> insertedConversation.get());

        SupportConversationRequest request = new SupportConversationRequest();
        request.setInitialMessage("电子票二维码在哪里？");

        SupportConversationResponse response = service.startConversation(10L, request);

        assertEquals(99L, response.getId());
        assertEquals("OPEN", response.getStatus());
        assertEquals("AI", response.getSourceType());
        verify(messageMapper, atLeastOnce()).insert(any(SupportMessage.class));
        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "AI".equals(message.getSenderType()) && message.getContent().contains("我的票夹")
        ));
    }

    @Test
    void userCannotReadOtherUsersConversation() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(20L);
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        BusinessException error = assertThrows(BusinessException.class, () -> service.listMessages(10L, 99L));

        assertEquals("无权查看该客服会话", error.getMessage());
    }

    @Test
    void supportAgentClaimsConversationAndCanReply() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        SupportConversationResponse claimed = service.claim(30L, 99L);
        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("您好，我来帮您继续处理。");
        service.sendMessage(30L, 99L, message);

        assertEquals("ASSIGNED", claimed.getStatus());
        assertEquals(30L, claimed.getAssignedAgentId());
        assertTrue("ASSIGNED".equals(conversation.getStatus()));
        assertTrue(conversation.getUpdateTime() != null);
        verify(conversationMapper, atLeastOnce()).updateById(conversation);
    }

    @Test
    void handoffWritesWaitingPromptVisibleToUser() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("OPEN");
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        service.handoff(10L, 99L);

        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "SYSTEM".equals(message.getSenderType())
                        && "人工介入请等待".equals(message.getContent())
        ));
    }

    @Test
    void claimWritesNamedAgentPromptVisibleToUser() {
        User agent = user(30L, "support");
        agent.setNickname("客服一号");
        when(userMapper.selectById(30L)).thenReturn(agent);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        service.claim(30L, 99L);

        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "SYSTEM".equals(message.getSenderType())
                        && "客服一号已介入".equals(message.getContent())
        ));
    }

    @Test
    void supportAgentCanOnlyRequestCloseUntilUserConfirms() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("ASSIGNED");
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        SupportConversationResponse response = service.close(30L, 99L);

        assertEquals("CLOSE_REQUESTED", response.getStatus());
        assertEquals(null, conversation.getClosedAt());
        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "SYSTEM".equals(message.getSenderType())
                        && "人工客服申请结束会话，请确认是否结束。".equals(message.getContent())
        ));
    }

    @Test
    void ownerConfirmsCloseBeforeConversationActuallyEnds() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("CLOSE_REQUESTED");
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        SupportConversationResponse response = service.confirmClose(10L, 99L);

        assertEquals("CLOSED", response.getStatus());
        assertTrue(conversation.getClosedAt() != null);
        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "SYSTEM".equals(message.getSenderType())
                        && "用户已确认结束会话".equals(message.getContent())
        ));
    }

    @Test
    void userReplyKeepsConversationOpenWhenCloseWasRequested() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("CLOSE_REQUESTED");
        conversation.setSourceType("HUMAN");
        conversation.setAssignedAgentId(30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("我还需要继续咨询");

        service.sendMessage(10L, 99L, message);

        assertEquals("ASSIGNED", conversation.getStatus());
        verify(conversationMapper, atLeastOnce()).updateById(conversation);
    }

    @Test
    void conversationOwnerMessageIsUserEvenWhenOwnerHasAdminRole() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "admin"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("OPEN");
        conversation.setSourceType("HUMAN");
        conversation.setAssignedAgentId(30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("我在用户端继续咨询");

        service.sendMessage(10L, 99L, message);

        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                "USER".equals(inserted.getSenderType())
                        && Long.valueOf(10L).equals(inserted.getSenderUserId())
                        && "我在用户端继续咨询".equals(inserted.getContent())
        ));
    }

    @Test
    void streamMessageUsesAnswerDiagnosticsForAiLatencyEvidence() {
        DiagnosticOnlySupportAiService diagnosticAiService = new DiagnosticOnlySupportAiService();
        CustomerSupportService streamingService = new CustomerSupportService(
                conversationMapper,
                messageMapper,
                noteMapper,
                tagMapper,
                auditMapper,
                quickReplyMapper,
                supportAccountMapper,
                userMapper,
                diagnosticAiService,
                notificationProducer,
                new SupportPushService(),
                "internal-token",
                Runnable::run
        );
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("OPEN");
        conversation.setSourceType("AI");
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        stubInsertedMessageId(701L);
        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("我想咨询一个暂未覆盖在常见问题里的特殊场景。");

        streamingService.streamMessage(10L, 99L, message);

        assertEquals(1, diagnosticAiService.getDiagnosticsCalls());
        verify(messageMapper, atLeastOnce()).insert(org.mockito.ArgumentMatchers.argThat(inserted ->
                "AI".equals(inserted.getSenderType())
                        && "诊断回答".equals(inserted.getContent())
        ));
    }

    @Test
    void messageResponseIncludesSenderDisplayName() {
        User customer = user(10L, "user");
        customer.setNickname("小王");
        when(userMapper.selectById(10L)).thenReturn(customer);
        User agent = user(30L, "support");
        agent.setNickname("杨梅");
        when(userMapper.selectById(30L)).thenReturn(agent);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("ASSIGNED");
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportMessage message = new SupportMessage();
        message.setId(1L);
        message.setConversationId(99L);
        message.setSenderUserId(30L);
        message.setSenderType("AGENT");
        message.setContent("您好");
        when(messageMapper.selectList(any())).thenReturn(List.of(message));

        List<SupportMessageResponse> response = service.listMessages(10L, 99L);

        assertEquals("杨梅", response.get(0).getSenderDisplayName());
    }

    @Test
    void autoClosesAssignedHumanConversationWhenUserDoesNotReplyAfterAgentMessage() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 17, 0);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("ASSIGNED");
        conversation.setSourceType("HUMAN");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        SupportMessage lastAgentMessage = new SupportMessage();
        lastAgentMessage.setConversationId(99L);
        lastAgentMessage.setSenderType("AGENT");
        lastAgentMessage.setCreateTime(now.minusMinutes(31));
        when(messageMapper.selectOne(any())).thenAnswer(invocation ->
                wrapperHasParam(invocation.getArgument(0), "AGENT") ? lastAgentMessage : null
        );

        int closedCount = service.closeInactiveAssignedHumanConversations(now);

        assertEquals(1, closedCount);
        assertEquals("CLOSED", conversation.getStatus());
        assertEquals(now, conversation.getClosedAt());
        assertEquals("用户超过30分钟未继续咨询，系统已自动结束会话。", conversation.getLastMessage());
        verify(conversationMapper).updateById(conversation);
        verify(messageMapper).insert(org.mockito.ArgumentMatchers.argThat(message ->
                "SYSTEM".equals(message.getSenderType())
                        && "用户超过30分钟未继续咨询，系统已自动结束会话。".equals(message.getContent())
        ));
    }

    @Test
    void autoClosesAiConversationWhenUserDoesNotReplyAfterAiMessage() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 17, 0);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("OPEN");
        conversation.setSourceType("AI");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        SupportMessage lastAiMessage = new SupportMessage();
        lastAiMessage.setConversationId(99L);
        lastAiMessage.setSenderType("AI");
        lastAiMessage.setCreateTime(now.minusMinutes(31));
        when(messageMapper.selectOne(any())).thenAnswer(invocation ->
                wrapperHasParam(invocation.getArgument(0), "AI") ? lastAiMessage : null
        );

        int closedCount = service.closeInactiveAssignedHumanConversations(now);

        assertEquals(1, closedCount);
        assertEquals("CLOSED", conversation.getStatus());
        assertEquals(now, conversation.getClosedAt());
    }

    @Test
    void doesNotAutoCloseWhenUserRepliedAfterLastServiceMessage() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 17, 0);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("ASSIGNED");
        conversation.setSourceType("HUMAN");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        SupportMessage lastAgentMessage = new SupportMessage();
        lastAgentMessage.setConversationId(99L);
        lastAgentMessage.setSenderType("AGENT");
        lastAgentMessage.setCreateTime(now.minusMinutes(45));
        SupportMessage userReplyAfterAgent = new SupportMessage();
        userReplyAfterAgent.setConversationId(99L);
        userReplyAfterAgent.setSenderType("USER");
        userReplyAfterAgent.setCreateTime(now.minusMinutes(31));
        when(messageMapper.selectOne(any())).thenAnswer(invocation -> {
            Object wrapper = invocation.getArgument(0);
            if (wrapperHasParam(wrapper, "AGENT")) {
                return lastAgentMessage;
            }
            if (wrapperHasParam(wrapper, "USER")) {
                return userReplyAfterAgent;
            }
            return null;
        });

        int closedCount = service.closeInactiveAssignedHumanConversations(now);

        assertEquals(0, closedCount);
        assertEquals("ASSIGNED", conversation.getStatus());
        verify(conversationMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void doesNotAutoCloseWhenLastUserMessageIsWithinThirtyMinutes() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 17, 0);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("ASSIGNED");
        conversation.setSourceType("HUMAN");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        SupportMessage lastAgentMessage = new SupportMessage();
        lastAgentMessage.setConversationId(99L);
        lastAgentMessage.setSenderType("AGENT");
        lastAgentMessage.setCreateTime(now.minusMinutes(29));
        when(messageMapper.selectOne(any())).thenAnswer(invocation ->
                wrapperHasParam(invocation.getArgument(0), "AGENT") ? lastAgentMessage : null
        );

        int closedCount = service.closeInactiveAssignedHumanConversations(now);

        assertEquals(0, closedCount);
        assertEquals("ASSIGNED", conversation.getStatus());
        verify(conversationMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void doesNotAutoCloseAssignedHumanConversationWithoutUserMessage() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 17, 0);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("ASSIGNED");
        conversation.setSourceType("HUMAN");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));
        when(messageMapper.selectOne(any())).thenReturn(null);

        int closedCount = service.closeInactiveAssignedHumanConversations(now);

        assertEquals(0, closedCount);
        assertEquals("ASSIGNED", conversation.getStatus());
        verify(conversationMapper, org.mockito.Mockito.never()).updateById(any());
    }

    @Test
    void supportAgentReplyCreatesUserNotification() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        stubInsertedMessageId(501L);

        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("您好，票夹入口已经为您处理完成。");

        service.sendMessage(30L, 99L, message);

        verify(notificationProducer).sendNotificationEvent(org.mockito.ArgumentMatchers.argThat(messageReq ->
                "support-reply:99:501".equals(messageReq.getEventId())
                        && "SUPPORT_REPLY".equals(messageReq.getEventType())
                        && Long.valueOf(10L).equals(messageReq.getUserId())
                        && messageReq.getOrderId() == null
                        && messageReq.getContent().contains("客服回复")
                        && "/help".equals(messageReq.getActionHref())
                        && "查看客服会话".equals(messageReq.getActionLabel())
                        && "SUPPORT_REPLY:99".equals(messageReq.getAggregateKey())
                        && List.of("IN_APP").equals(messageReq.effectiveChannels())
                        && Long.valueOf(99L).equals(messageReq.getPayload().get("conversationId"))
        ));
    }

    @Test
    void supportAgentReplySkipsNotificationWhenUserIsViewingHelp() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        service.markHelpPresence(10L, LocalDateTime.now());

        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("您好，客服已回复。");

        service.sendMessage(30L, 99L, message);

        verify(notificationProducer, org.mockito.Mockito.never()).sendNotificationEvent(any(NotificationEventMessage.class));
    }

    @Test
    void supportAgentReplySkipsNotificationWhenPersistedMessageIdIsMissing() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);

        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("您好，客服已回复。");

        service.sendMessage(30L, 99L, message);

        verify(notificationProducer, org.mockito.Mockito.never()).sendNotificationEvent(any(NotificationEventMessage.class));
    }

    @Test
    void supportAgentReplyCreatesNotificationWhenHelpPresenceExpired() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        service.markHelpPresence(10L, LocalDateTime.now().minusMinutes(2));
        stubInsertedMessageId(502L);

        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("您好，客服已回复。");

        service.sendMessage(30L, 99L, message);

        verify(notificationProducer).sendNotificationEvent(org.mockito.ArgumentMatchers.argThat(messageReq ->
                Long.valueOf(10L).equals(messageReq.getUserId())
                        && "SUPPORT_REPLY".equals(messageReq.getEventType())
        ));
    }

    @Test
    void supportAgentReplyCreatesNotificationAfterUserLeavesHelpWindow() {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        service.markHelpPresence(10L, LocalDateTime.now());
        service.clearHelpPresence(10L);
        stubInsertedMessageId(503L);

        SupportMessageRequest message = new SupportMessageRequest();
        message.setContent("您好，客服已回复。");

        service.sendMessage(30L, 99L, message);

        verify(notificationProducer).sendNotificationEvent(org.mockito.ArgumentMatchers.argThat(messageReq ->
                Long.valueOf(10L).equals(messageReq.getUserId())
                        && "SUPPORT_REPLY".equals(messageReq.getEventType())
        ));
    }

    @Test
    void supportAgentAddsInternalNoteVisibleOnlyInAgentWorkbench() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        User agent = user(30L, "support");
        agent.setNickname("客服一号");
        when(userMapper.selectById(30L)).thenReturn(agent);
        SupportConversation conversation = supportConversation(99L, 10L, "ASSIGNED", 30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        when(noteMapper.insert(any())).thenAnswer(invocation -> {
            SupportConversationNote note = invocation.getArgument(0);
            note.setId(7L);
            return 1;
        });
        SupportNoteRequest request = new SupportNoteRequest();
        request.setContent("用户反馈支付成功但票夹为空");

        SupportNoteResponse response = service.addNote(30L, 99L, request);

        assertEquals(7L, response.getId());
        assertEquals("用户反馈支付成功但票夹为空", response.getContent());
        assertEquals("客服一号", response.getAuthorDisplayName());
        verify(noteMapper).insert(any(SupportConversationNote.class));
    }

    @Test
    void supportAgentUpdatesTagsAndConversationResponseIncludesTags() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation conversation = supportConversation(99L, 10L, "ASSIGNED", 30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportTagUpdateRequest request = new SupportTagUpdateRequest();
        request.setTags(List.of("REFUND", "TICKET", "REFUND"));

        SupportConversationResponse response = service.updateTags(30L, 99L, request);

        assertEquals(List.of("REFUND", "TICKET"), response.getTags());
        verify(tagMapper).delete(any());
        verify(tagMapper, org.mockito.Mockito.times(2)).insert(any(SupportConversationTag.class));
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(audit ->
                "TAG_UPDATED".equals(audit.getAction())
        ));
    }

    @Test
    void supportAgentTransfersConversationToAnotherAgentAndWritesAudit() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(userMapper.selectById(31L)).thenReturn(user(31L, "support"));
        SupportConversation conversation = supportConversation(99L, 10L, "ASSIGNED", 30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportTransferRequest request = new SupportTransferRequest();
        request.setTargetAgentId(31L);
        request.setReason("需要专员处理退款");

        SupportConversationResponse response = service.transfer(30L, 99L, request);

        assertEquals(31L, response.getAssignedAgentId());
        assertEquals("ASSIGNED", response.getStatus());
        assertEquals("会话已转接给客服 31", conversation.getLastMessage());
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(audit ->
                "TRANSFERRED".equals(audit.getAction()) && audit.getDetail().contains("需要专员处理退款")
        ));
    }

    @Test
    void supportAgentEscalatesConversationForManagerFollowUp() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation conversation = supportConversation(99L, 10L, "ASSIGNED", 30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportCloseRequest request = new SupportCloseRequest();
        request.setReason("疑似异常退款");

        SupportConversationResponse response = service.escalate(30L, 99L, request);

        assertEquals(Boolean.TRUE, response.getEscalatedToAdmin());
        assertEquals("疑似异常退款", response.getEscalationReason());
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(audit ->
                "ESCALATED".equals(audit.getAction())
        ));
    }

    @Test
    void ownerRejectsCloseRequestAndAuditCanBeListed() {
        when(userMapper.selectById(10L)).thenReturn(user(10L, "user"));
        SupportConversation conversation = supportConversation(99L, 10L, "CLOSE_REQUESTED", 30L);
        when(conversationMapper.selectById(99L)).thenReturn(conversation);
        SupportCloseRejectRequest request = new SupportCloseRejectRequest();
        request.setReason("问题还没有解决");

        SupportConversationResponse response = service.rejectClose(10L, 99L, request);

        assertEquals("ASSIGNED", response.getStatus());
        verify(auditMapper).insert(org.mockito.ArgumentMatchers.argThat(audit ->
                "CLOSE_REJECTED".equals(audit.getAction()) && audit.getDetail().contains("问题还没有解决")
        ));

        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversationAudit audit = new SupportConversationAudit();
        audit.setId(8L);
        audit.setConversationId(99L);
        audit.setActorUserId(10L);
        audit.setAction("CLOSE_REJECTED");
        audit.setDetail("问题还没有解决");
        when(auditMapper.selectList(any())).thenReturn(List.of(audit));

        List<SupportAuditResponse> audits = service.listAudits(30L, 99L);

        assertEquals(1, audits.size());
        assertEquals("CLOSE_REJECTED", audits.get(0).getAction());
    }

    @Test
    void listsWaitingConversationsForSupportAgent() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, "WAITING_AGENT");

        assertEquals(1, response.size());
        assertEquals("WAITING_AGENT", response.get(0).getStatus());
    }

    @Test
    void supportAgentPendingQueueOnlyShowsUnclaimedWaitingConversations() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation publicWaiting = supportConversation(1L, 10L, "WAITING_AGENT", null);
        SupportConversation assignedWaiting = supportConversation(2L, 11L, "WAITING_AGENT", 31L);
        SupportConversation ownAssigned = supportConversation(3L, 12L, "ASSIGNED", 30L);
        when(conversationMapper.selectList(any())).thenReturn(List.of(publicWaiting, assignedWaiting, ownAssigned));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, null, "pending");

        assertEquals(List.of(1L), response.stream().map(SupportConversationResponse::getId).collect(Collectors.toList()));
    }

    @Test
    void supportAgentInProgressQueueOnlyShowsOwnAssignedConversations() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation ownAssigned = supportConversation(1L, 10L, "ASSIGNED", 30L);
        SupportConversation otherAssigned = supportConversation(2L, 11L, "ASSIGNED", 31L);
        SupportConversation publicWaiting = supportConversation(3L, 12L, "WAITING_AGENT", null);
        when(conversationMapper.selectList(any())).thenReturn(List.of(ownAssigned, otherAssigned, publicWaiting));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, null, "in_progress");

        assertEquals(List.of(1L), response.stream().map(SupportConversationResponse::getId).collect(Collectors.toList()));
    }

    @Test
    void responseIncludesSlaWhenHumanConversationWaitsForFirstResponse() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation conversation = supportConversation(1L, 10L, "WAITING_AGENT", null);
        conversation.setFirstResponseDueAt(LocalDateTime.of(2026, 6, 2, 10, 5));
        conversation.setLastUserMessageAt(LocalDateTime.of(2026, 6, 2, 10, 0));
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, null, "pending");

        assertEquals(LocalDateTime.of(2026, 6, 2, 10, 5), response.get(0).getFirstResponseDueAt());
        assertTrue(response.get(0).getUserWaitingSeconds() >= 0);
    }

    @Test
    void supportAgentOverdueQueueShowsOnlyVisibleOverdueConversations() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation overduePublic = supportConversation(1L, 10L, "WAITING_AGENT", null);
        overduePublic.setFirstResponseDueAt(LocalDateTime.now().minusMinutes(1));
        SupportConversation overdueOwn = supportConversation(2L, 11L, "ASSIGNED", 30L);
        overdueOwn.setLastUserMessageAt(LocalDateTime.now().minusMinutes(11));
        SupportConversation overdueOther = supportConversation(3L, 12L, "ASSIGNED", 31L);
        overdueOther.setLastUserMessageAt(LocalDateTime.now().minusMinutes(11));
        SupportConversation normalPublic = supportConversation(4L, 13L, "WAITING_AGENT", null);
        normalPublic.setFirstResponseDueAt(LocalDateTime.now().plusMinutes(4));
        when(conversationMapper.selectList(any())).thenReturn(List.of(overduePublic, overdueOwn, overdueOther, normalPublic));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, null, "overdue");

        assertEquals(List.of(1L, 2L), response.stream().map(SupportConversationResponse::getId).collect(Collectors.toList()));
    }

    @Test
    void supportAgentDefaultQueueExcludesAiOnlyOpenConversations() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        SupportConversation aiOnly = new SupportConversation();
        aiOnly.setId(98L);
        aiOnly.setUserId(10L);
        aiOnly.setStatus("OPEN");
        SupportConversation waiting = new SupportConversation();
        waiting.setId(99L);
        waiting.setUserId(10L);
        waiting.setStatus("WAITING_AGENT");
        when(conversationMapper.selectList(any())).thenReturn(List.of(aiOnly, waiting));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, null);

        assertEquals(1, response.size());
        assertEquals(99L, response.get(0).getId());
        assertEquals("WAITING_AGENT", response.get(0).getStatus());
    }

    @Test
    void supportManagerConversationListIncludesAiAndOtherAgentsConversations() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "support"));
        when(supportAccountMapper.selectById(30L)).thenReturn(supportAccount(30L, "support_manager"));
        SupportConversation aiOnly = supportConversation(98L, 10L, "OPEN", null);
        aiOnly.setSourceType("AI");
        SupportConversation waiting = supportConversation(99L, 11L, "WAITING_AGENT", null);
        SupportConversation otherAssigned = supportConversation(100L, 12L, "ASSIGNED", 31L);
        when(conversationMapper.selectList(any())).thenReturn(List.of(aiOnly, waiting, otherAssigned));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, null);

        assertEquals(List.of(98L, 99L, 100L), response.stream().map(SupportConversationResponse::getId).collect(Collectors.toList()));
    }

    @Test
    void adminConversationListIncludesUserDisplayInfo() {
        when(userMapper.selectById(30L)).thenReturn(user(30L, "admin"));
        User customer = user(10L, "user");
        customer.setNickname("小王");
        customer.setPhone("13812348000");
        when(userMapper.selectById(10L)).thenReturn(customer);
        SupportConversation conversation = new SupportConversation();
        conversation.setId(99L);
        conversation.setUserId(10L);
        conversation.setStatus("WAITING_AGENT");
        when(conversationMapper.selectList(any())).thenReturn(List.of(conversation));

        List<SupportConversationResponse> response = service.listAgentConversations(30L, "WAITING_AGENT");

        assertEquals("小王", response.get(0).getUserNickname());
        assertEquals("138****8000", response.get(0).getUserPhoneMask());
    }

    private User user(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    private SupportAccount supportAccount(Long userId, String supportRole) {
        SupportAccount account = new SupportAccount();
        account.setUserId(userId);
        account.setStatus(1);
        account.setSupportRole(supportRole);
        return account;
    }

    private SupportConversation supportConversation(Long id, Long userId, String status, Long assignedAgentId) {
        SupportConversation conversation = new SupportConversation();
        conversation.setId(id);
        conversation.setUserId(userId);
        conversation.setStatus(status);
        conversation.setSourceType("HUMAN");
        conversation.setAssignedAgentId(assignedAgentId);
        conversation.setCreateTime(LocalDateTime.of(2026, 6, 2, 10, 0));
        conversation.setUpdateTime(LocalDateTime.of(2026, 6, 2, 10, 0));
        return conversation;
    }

    private void stubInsertedMessageId(Long messageId) {
        when(messageMapper.insert(any())).thenAnswer(invocation -> {
            SupportMessage inserted = invocation.getArgument(0);
            inserted.setId(messageId);
            return 1;
        });
    }

    private boolean wrapperHasParam(Object wrapper, Object expected) {
        if (!(wrapper instanceof AbstractWrapper)) {
            return false;
        }
        AbstractWrapper<?, ?, ?> queryWrapper = (AbstractWrapper<?, ?, ?>) wrapper;
        queryWrapper.getSqlSegment();
        return queryWrapper.getParamNameValuePairs().values().stream()
                .anyMatch(value -> paramMatches(value, expected));
    }

    private boolean paramMatches(Object value, Object expected) {
        if (expected == null) {
            return value == null;
        }
        if (expected.equals(value)) {
            return true;
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (expected.equals(item)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class DiagnosticOnlySupportAiService extends SupportAiService {
        private final AtomicInteger diagnosticsCalls = new AtomicInteger();

        private DiagnosticOnlySupportAiService() {
            super((question, projectKnowledge) -> Optional.of("诊断回答"));
        }

        @Override
        public String answerStreaming(String question, java.util.function.Consumer<String> onChunk) {
            throw new IllegalStateException("流式客服入口必须使用诊断回答路径");
        }

        @Override
        public AnswerDiagnostics answerStreamingWithDiagnostics(String question, java.util.function.Consumer<String> onChunk) {
            diagnosticsCalls.incrementAndGet();
            return super.answerStreamingWithDiagnostics(question, onChunk);
        }

        private int getDiagnosticsCalls() {
            return diagnosticsCalls.get();
        }
    }
}
