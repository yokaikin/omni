package com.omni.user.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.omni.common.mq.message.NotificationEventMessage;
import com.omni.common.mq.MqPublishSupport;
import com.omni.common.result.ResultCode;
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
import com.omni.user.dto.SupportQuickReplyResponse;
import com.omni.user.dto.SupportStreamTicketRequest;
import com.omni.user.dto.SupportStreamTicketResponse;
import com.omni.user.dto.SupportTagUpdateRequest;
import com.omni.user.dto.SupportTransferRequest;
import com.omni.user.entity.SupportAccount;
import com.omni.user.entity.SupportConversation;
import com.omni.user.entity.SupportConversationAudit;
import com.omni.user.entity.SupportConversationNote;
import com.omni.user.entity.SupportConversationTag;
import com.omni.user.entity.SupportMessage;
import com.omni.user.entity.SupportQuickReply;
import com.omni.user.entity.User;
import com.omni.user.mapper.SupportAccountMapper;
import com.omni.user.mapper.SupportConversationAuditMapper;
import com.omni.user.mapper.SupportConversationMapper;
import com.omni.user.mapper.SupportConversationNoteMapper;
import com.omni.user.mapper.SupportConversationTagMapper;
import com.omni.user.mapper.SupportMessageMapper;
import com.omni.user.mapper.SupportQuickReplyMapper;
import com.omni.user.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class CustomerSupportService {

    private static final Logger log = LoggerFactory.getLogger(CustomerSupportService.class);

    private static final String ROLE_ADMIN = "admin";
    private static final String ROLE_SUPPORT = "support";
    private static final String SUPPORT_ROLE_MANAGER = "support_manager";
    private static final String STATUS_OPEN = "OPEN";
    private static final String STATUS_WAITING_AGENT = "WAITING_AGENT";
    private static final String STATUS_ASSIGNED = "ASSIGNED";
    private static final String STATUS_CLOSE_REQUESTED = "CLOSE_REQUESTED";
    private static final String STATUS_CLOSED = "CLOSED";
    private static final String SOURCE_AI = "AI";
    private static final String SOURCE_HUMAN = "HUMAN";
    private static final String AUTO_CLOSE_MESSAGE = "用户超过30分钟未继续咨询，系统已自动结束会话。";
    private static final long HELP_PRESENCE_TTL_SECONDS = 60L;
    private static final long SUPPORT_AI_STREAM_TIMEOUT_MS = 90_000L;

    private final SupportConversationMapper conversationMapper;
    private final SupportMessageMapper messageMapper;
    private final SupportConversationNoteMapper noteMapper;
    private final SupportConversationTagMapper tagMapper;
    private final SupportConversationAuditMapper auditMapper;
    private final SupportQuickReplyMapper quickReplyMapper;
    private final SupportAccountMapper supportAccountMapper;
    private final UserMapper userMapper;
    private final SupportAiService supportAiService;
    private final NotificationMqProducer notificationProducer;
    private final SupportPushService supportPushService;
    private final String internalApiToken;
    private final Executor supportAiExecutor;
    private final ConcurrentMap<Long, LocalDateTime> helpPresence = new ConcurrentHashMap<>();

    @Autowired
    public CustomerSupportService(SupportConversationMapper conversationMapper,
                                  SupportMessageMapper messageMapper,
                                  SupportConversationNoteMapper noteMapper,
                                  SupportConversationTagMapper tagMapper,
                                  SupportConversationAuditMapper auditMapper,
                                  SupportQuickReplyMapper quickReplyMapper,
                                  SupportAccountMapper supportAccountMapper,
                                  UserMapper userMapper,
                                  SupportAiService supportAiService,
                                  NotificationMqProducer notificationProducer,
                                  SupportPushService supportPushService,
                                  @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken,
                                  @Qualifier("supportAiExecutor") Executor supportAiExecutor) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.noteMapper = noteMapper;
        this.tagMapper = tagMapper;
        this.auditMapper = auditMapper;
        this.quickReplyMapper = quickReplyMapper;
        this.supportAccountMapper = supportAccountMapper;
        this.userMapper = userMapper;
        this.supportAiService = supportAiService;
        this.notificationProducer = notificationProducer;
        this.supportPushService = supportPushService;
        this.internalApiToken = internalApiToken;
        this.supportAiExecutor = supportAiExecutor;
    }

    public CustomerSupportService(SupportConversationMapper conversationMapper,
                                  SupportMessageMapper messageMapper,
                                  SupportConversationNoteMapper noteMapper,
                                  SupportConversationTagMapper tagMapper,
                                  SupportConversationAuditMapper auditMapper,
                                  SupportQuickReplyMapper quickReplyMapper,
                                  UserMapper userMapper,
                                  SupportAiService supportAiService,
                                  NotificationMqProducer notificationProducer,
                                  SupportPushService supportPushService,
                                  String internalApiToken,
                                  Executor supportAiExecutor) {
        this(conversationMapper, messageMapper, noteMapper, tagMapper, auditMapper, quickReplyMapper, null,
                userMapper, supportAiService, notificationProducer, supportPushService, internalApiToken, supportAiExecutor);
    }

    @Transactional
    public SupportConversationResponse startConversation(Long userId, SupportConversationRequest request) {
        requireActiveUser(userId);
        String initialMessage = request == null ? null : trimToNull(request.getInitialMessage());
        boolean preferHuman = request != null && Boolean.TRUE.equals(request.getPreferHuman());
        SupportConversation conversation = new SupportConversation();
        conversation.setUserId(userId);
        conversation.setSubject(buildSubject(request == null ? null : request.getSubject(), initialMessage));
        conversation.setStatus(preferHuman ? STATUS_WAITING_AGENT : STATUS_OPEN);
        conversation.setSourceType(preferHuman ? SOURCE_HUMAN : SOURCE_AI);
        conversation.setLastMessage(initialMessage == null ? "用户发起在线客服咨询" : initialMessage);
        if (preferHuman) {
            LocalDateTime now = LocalDateTime.now();
            conversation.setFirstResponseDueAt(now.plusMinutes(5));
            if (initialMessage != null) {
                conversation.setLastUserMessageAt(now);
            }
        }
        touch(conversation);
        conversationMapper.insert(conversation);

        if (initialMessage != null) {
            insertMessage(conversation.getId(), userId, "USER", initialMessage);
            if (!preferHuman) {
                queueAiReply(conversation.getId(), initialMessage);
            }
        }
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    public List<SupportConversationResponse> listMine(Long userId) {
        requireActiveUser(userId);
        return conversationMapper.selectList(new LambdaQueryWrapper<SupportConversation>()
                        .eq(SupportConversation::getUserId, userId)
                        .orderByDesc(SupportConversation::getUpdateTime)
                        .orderByDesc(SupportConversation::getId))
                .stream()
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    public List<SupportConversationResponse> listAgentConversations(Long agentUserId, String status) {
        return listAgentConversations(agentUserId, status, null);
    }

    public List<SupportConversationResponse> listAgentConversations(Long agentUserId, String status, String queue) {
        User agent = requireSupportOrAdmin(agentUserId);
        boolean managerScope = hasSupportManagerScope(agent);
        LambdaQueryWrapper<SupportConversation> wrapper = new LambdaQueryWrapper<SupportConversation>()
                .orderByDesc(SupportConversation::getUpdateTime)
                .orderByDesc(SupportConversation::getId);
        String normalizedStatus = trimToNull(status);
        String normalizedQueue = trimToNull(queue);
        if (normalizedStatus != null) {
            wrapper.eq(SupportConversation::getStatus, normalizedStatus);
        } else if ("closed".equals(normalizedQueue)) {
            wrapper.eq(SupportConversation::getStatus, STATUS_CLOSED);
        } else {
            wrapper.ne(SupportConversation::getStatus, STATUS_CLOSED);
        }
        if (ROLE_SUPPORT.equals(agent.getRole()) && !managerScope && normalizedStatus == null && normalizedQueue == null) {
            wrapper.and(w -> w.eq(SupportConversation::getStatus, STATUS_WAITING_AGENT)
                    .isNull(SupportConversation::getAssignedAgentId)
                    .or()
                    .eq(SupportConversation::getAssignedAgentId, agentUserId));
        }
        return conversationMapper.selectList(wrapper).stream()
                .filter(conversation -> isVisibleInAgentQueue(conversation, agent, agentUserId, normalizedStatus, normalizedQueue, managerScope))
                .map(this::toConversationResponse)
                .collect(Collectors.toList());
    }

    private boolean isVisibleInAgentQueue(SupportConversation conversation, User agent, Long agentUserId, String status, String queue, boolean managerScope) {
        if (managerScope || !ROLE_SUPPORT.equals(agent.getRole())) {
            return isVisibleInManagerQueue(conversation, queue);
        }
        if (status != null) {
            if (STATUS_WAITING_AGENT.equals(status)) {
                return conversation.getAssignedAgentId() == null;
            }
            return agentUserId.equals(conversation.getAssignedAgentId());
        }
        if ("overdue".equals(queue)) {
            boolean visible = (STATUS_WAITING_AGENT.equals(conversation.getStatus()) && conversation.getAssignedAgentId() == null)
                    || agentUserId.equals(conversation.getAssignedAgentId());
            return visible && isSlaOverdue(conversation, LocalDateTime.now());
        }
        if ("pending".equals(queue)) {
            return STATUS_WAITING_AGENT.equals(conversation.getStatus()) && conversation.getAssignedAgentId() == null;
        }
        if ("in_progress".equals(queue)) {
            return STATUS_ASSIGNED.equals(conversation.getStatus()) && agentUserId.equals(conversation.getAssignedAgentId());
        }
        if ("close_requested".equals(queue)) {
            return STATUS_CLOSE_REQUESTED.equals(conversation.getStatus()) && agentUserId.equals(conversation.getAssignedAgentId());
        }
        if ("closed".equals(queue)) {
            return STATUS_CLOSED.equals(conversation.getStatus()) && agentUserId.equals(conversation.getAssignedAgentId());
        }
        return (STATUS_WAITING_AGENT.equals(conversation.getStatus()) && conversation.getAssignedAgentId() == null)
                || agentUserId.equals(conversation.getAssignedAgentId());
    }

    private boolean isVisibleInManagerQueue(SupportConversation conversation, String queue) {
        if ("overdue".equals(queue)) {
            return isSlaOverdue(conversation, LocalDateTime.now());
        }
        if ("pending".equals(queue)) {
            return STATUS_WAITING_AGENT.equals(conversation.getStatus()) && conversation.getAssignedAgentId() == null;
        }
        if ("in_progress".equals(queue)) {
            return STATUS_ASSIGNED.equals(conversation.getStatus());
        }
        if ("close_requested".equals(queue)) {
            return STATUS_CLOSE_REQUESTED.equals(conversation.getStatus());
        }
        if ("closed".equals(queue)) {
            return STATUS_CLOSED.equals(conversation.getStatus());
        }
        return true;
    }

    public List<SupportMessageResponse> listMessages(Long actorUserId, Long conversationId) {
        SupportConversation conversation = requireVisibleConversation(actorUserId, conversationId);
        return messageMapper.selectList(new LambdaQueryWrapper<SupportMessage>()
                        .eq(SupportMessage::getConversationId, conversation.getId())
                        .orderByAsc(SupportMessage::getId))
                .stream()
                .map(this::toMessageResponse)
                .collect(Collectors.toList());
    }

    public List<SupportNoteResponse> listNotes(Long actorUserId, Long conversationId) {
        requireSupportOrAdmin(actorUserId);
        requireConversation(conversationId);
        List<SupportConversationNote> notes = noteMapper.selectList(new LambdaQueryWrapper<SupportConversationNote>()
                .eq(SupportConversationNote::getConversationId, conversationId)
                .orderByDesc(SupportConversationNote::getCreateTime)
                .orderByDesc(SupportConversationNote::getId));
        if (notes == null) {
            return Collections.emptyList();
        }
        return notes.stream().map(this::toNoteResponse).collect(Collectors.toList());
    }

    @Transactional
    public SupportNoteResponse addNote(Long actorUserId, Long conversationId, SupportNoteRequest request) {
        requireSupportOrAdmin(actorUserId);
        requireConversation(conversationId);
        String content = request == null ? null : trimToNull(request.getContent());
        if (content == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "内部备注不能为空");
        }
        if (content.length() > 500) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "内部备注不能超过500字");
        }
        SupportConversationNote note = new SupportConversationNote();
        note.setConversationId(conversationId);
        note.setAuthorUserId(actorUserId);
        note.setContent(content);
        note.setCreateTime(LocalDateTime.now());
        noteMapper.insert(note);
        return toNoteResponse(note);
    }

    @Transactional
    public SupportConversationResponse updateTags(Long actorUserId, Long conversationId, SupportTagUpdateRequest request) {
        requireSupportOrAdmin(actorUserId);
        SupportConversation conversation = requireConversation(conversationId);
        List<String> tags = normalizeTags(request == null ? null : request.getTags());
        tagMapper.delete(new LambdaQueryWrapper<SupportConversationTag>()
                .eq(SupportConversationTag::getConversationId, conversationId));
        for (String code : tags) {
            SupportConversationTag tag = new SupportConversationTag();
            tag.setConversationId(conversationId);
            tag.setTagCode(code);
            tag.setCreateBy(actorUserId);
            tag.setCreateTime(LocalDateTime.now());
            tagMapper.insert(tag);
        }
        writeAudit(conversationId, actorUserId, "TAG_UPDATED", conversation.getStatus(), conversation.getStatus(),
                "更新标签：" + tags.stream().map(this::formatTagLabel).collect(Collectors.joining("、")));
        return toConversationResponse(conversation, tags);
    }

    public List<SupportQuickReplyResponse> listQuickReplies(Long actorUserId) {
        requireSupportOrAdmin(actorUserId);
        List<SupportQuickReply> replies = quickReplyMapper.selectList(new LambdaQueryWrapper<SupportQuickReply>()
                .eq(SupportQuickReply::getStatus, 1)
                .orderByAsc(SupportQuickReply::getSortOrder)
                .orderByAsc(SupportQuickReply::getId));
        if (replies == null) {
            return Collections.emptyList();
        }
        return replies.stream().map(this::toQuickReplyResponse).collect(Collectors.toList());
    }

    public void markHelpPresence(Long userId) {
        markHelpPresence(userId, LocalDateTime.now());
    }

    public void markHelpPresence(Long userId, LocalDateTime now) {
        if (userId == null) {
            return;
        }
        requireActiveUser(userId);
        helpPresence.put(userId, now == null ? LocalDateTime.now() : now);
    }

    public void clearHelpPresence(Long userId) {
        if (userId == null) {
            return;
        }
        helpPresence.remove(userId);
    }

    @Transactional
    public SupportMessageResponse sendMessage(Long actorUserId, Long conversationId, SupportMessageRequest request) {
        String content = request == null ? null : trimToNull(request.getContent());
        if (content == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息内容不能为空");
        }
        User actor = requireActiveUser(actorUserId);
        SupportConversation conversation = requireVisibleConversation(actorUserId, conversationId);
        String senderType = actorUserId.equals(conversation.getUserId()) ? "USER" : "AGENT";
        SupportMessage message = insertMessage(conversation.getId(), actorUserId, senderType, content);
        LocalDateTime messageTime = message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime();
        conversation.setLastMessage(content);
        if ("USER".equals(senderType)) {
            conversation.setLastUserMessageAt(messageTime);
        }
        if ("AGENT".equals(senderType)) {
            if (conversation.getFirstAgentRepliedAt() == null) {
                conversation.setFirstAgentRepliedAt(messageTime);
            }
            conversation.setLastAgentMessageAt(messageTime);
        }
        if ("USER".equals(senderType) && STATUS_CLOSE_REQUESTED.equals(conversation.getStatus())) {
            conversation.setStatus(STATUS_ASSIGNED);
        }
        if ("AGENT".equals(senderType) && conversation.getAssignedAgentId() == null) {
            conversation.setAssignedAgentId(actorUserId);
            conversation.setSourceType(SOURCE_HUMAN);
            conversation.setStatus(STATUS_ASSIGNED);
        }
        touch(conversation);
        conversationMapper.updateById(conversation);
        if ("AGENT".equals(senderType)) {
            notifySupportReply(conversation, message);
        }
        pushMessageEvent(conversation.getUserId(), conversation.getId());

        if ("USER".equals(senderType) && SOURCE_AI.equals(conversation.getSourceType()) && conversation.getAssignedAgentId() == null) {
            queueAiReply(conversation.getId(), content);
        }
        return toMessageResponse(message);
    }

    @Transactional
    public SseEmitter streamMessage(Long actorUserId, Long conversationId, SupportMessageRequest request) {
        SseEmitter emitter = new SseEmitter(SUPPORT_AI_STREAM_TIMEOUT_MS);
        String content = request == null ? null : trimToNull(request.getContent());
        try {
            SupportMessageResponse userMessage = insertUserMessageForAiStream(actorUserId, conversationId, content);
            MqPublishSupport.afterCommitOrNow(() -> {
                try {
                    supportAiExecutor.execute(() -> streamAiReply(emitter, conversationId, userMessage, content));
                } catch (RuntimeException e) {
                    log.warn("客服 AI 流式任务提交失败: conversationId={}, message={}", conversationId, e.getMessage());
                    sendErrorAndComplete(emitter, "客服暂时无法回复，请稍后重试");
                }
            });
        } catch (RuntimeException e) {
            sendErrorAndComplete(emitter, toUserVisibleError(e));
        }
        return emitter;
    }

    @Transactional
    public SupportConversationResponse handoff(Long userId, Long conversationId) {
        SupportConversation conversation = requireOwnerConversation(userId, conversationId);
        conversation.setSourceType(SOURCE_HUMAN);
        conversation.setStatus(STATUS_WAITING_AGENT);
        conversation.setLastMessage("用户已申请转人工客服");
        if (conversation.getFirstResponseDueAt() == null) {
            conversation.setFirstResponseDueAt(LocalDateTime.now().plusMinutes(5));
        }
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), null, "SYSTEM", "人工介入请等待");
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    @Transactional
    public SupportConversationResponse claim(Long agentUserId, Long conversationId) {
        User agent = requireSupportOrAdmin(agentUserId);
        SupportConversation conversation = requireConversation(conversationId);
        if (STATUS_CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "已结束的会话不能接入");
        }
        String joinedMessage = buildAgentJoinedMessage(agent);
        conversation.setAssignedAgentId(agentUserId);
        conversation.setSourceType(SOURCE_HUMAN);
        conversation.setStatus(STATUS_ASSIGNED);
        conversation.setLastMessage(joinedMessage);
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), agentUserId, "SYSTEM", joinedMessage);
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    @Transactional
    public SupportConversationResponse close(Long agentUserId, Long conversationId) {
        return close(agentUserId, conversationId, null);
    }

    @Transactional
    public SupportConversationResponse close(Long agentUserId, Long conversationId, SupportCloseRequest request) {
        requireSupportOrAdmin(agentUserId);
        SupportConversation conversation = requireConversation(conversationId);
        if (STATUS_CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "会话已结束");
        }
        String fromStatus = conversation.getStatus();
        String reason = trimToNull(request == null ? null : request.getReason());
        conversation.setStatus(STATUS_CLOSE_REQUESTED);
        conversation.setCloseRequestReason(reason);
        conversation.setCloseRequestedBy(agentUserId);
        conversation.setCloseRequestedAt(LocalDateTime.now());
        String message = buildCloseRequestMessage(reason);
        conversation.setLastMessage(message);
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), agentUserId, "SYSTEM", message);
        writeAudit(conversationId, agentUserId, "CLOSE_REQUESTED", fromStatus, STATUS_CLOSE_REQUESTED, message);
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    @Transactional
    public SupportConversationResponse confirmClose(Long userId, Long conversationId) {
        SupportConversation conversation = requireOwnerConversation(userId, conversationId);
        if (!STATUS_CLOSE_REQUESTED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "当前会话未申请结束");
        }
        conversation.setStatus(STATUS_CLOSED);
        conversation.setClosedAt(LocalDateTime.now());
        conversation.setLastMessage("用户已确认结束会话");
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), userId, "SYSTEM", "用户已确认结束会话");
        writeAudit(conversationId, userId, "CLOSED_CONFIRMED", STATUS_CLOSE_REQUESTED, STATUS_CLOSED, "用户已确认结束会话");
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    @Transactional
    public SupportConversationResponse rejectClose(Long userId, Long conversationId, SupportCloseRejectRequest request) {
        SupportConversation conversation = requireOwnerConversation(userId, conversationId);
        if (!STATUS_CLOSE_REQUESTED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "当前会话未申请结束");
        }
        String reason = trimToNull(request == null ? null : request.getReason());
        String message = reason == null ? "用户拒绝结束会话，继续处理。" : "用户拒绝结束会话，原因：" + reason;
        conversation.setStatus(STATUS_ASSIGNED);
        conversation.setLastMessage(message);
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), userId, "SYSTEM", message);
        writeAudit(conversationId, userId, "CLOSE_REJECTED", STATUS_CLOSE_REQUESTED, STATUS_ASSIGNED, message);
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    @Transactional
    public SupportConversationResponse transfer(Long agentUserId, Long conversationId, SupportTransferRequest request) {
        requireSupportOrAdmin(agentUserId);
        SupportConversation conversation = requireConversation(conversationId);
        if (STATUS_CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "会话已结束");
        }
        Long targetAgentId = request == null ? null : request.getTargetAgentId();
        if (targetAgentId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "转接客服不能为空");
        }
        if (targetAgentId.equals(agentUserId)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不能转接给自己");
        }
        requireSupportOrAdmin(targetAgentId);
        String fromStatus = conversation.getStatus();
        String reason = trimToNull(request.getReason());
        String detail = reason == null
                ? "会话已转接给客服 " + targetAgentId
                : "会话已转接给客服 " + targetAgentId + "，原因：" + reason;
        conversation.setAssignedAgentId(targetAgentId);
        conversation.setSourceType(SOURCE_HUMAN);
        conversation.setStatus(STATUS_ASSIGNED);
        conversation.setLastMessage("会话已转接给客服 " + targetAgentId);
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), agentUserId, "SYSTEM", detail);
        writeAudit(conversationId, agentUserId, "TRANSFERRED", fromStatus, STATUS_ASSIGNED, detail);
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    @Transactional
    public SupportConversationResponse escalate(Long agentUserId, Long conversationId, SupportCloseRequest request) {
        requireSupportOrAdmin(agentUserId);
        SupportConversation conversation = requireConversation(conversationId);
        if (STATUS_CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "会话已结束");
        }
        String reason = trimToNull(request == null ? null : request.getReason());
        conversation.setEscalatedToAdmin(true);
        conversation.setEscalationReason(reason);
        conversation.setEscalatedAt(LocalDateTime.now());
        String detail = reason == null ? "已升级给管理员" : "已升级给管理员，原因：" + reason;
        conversation.setLastMessage(detail);
        touch(conversation);
        conversationMapper.updateById(conversation);
        insertMessage(conversation.getId(), agentUserId, "SYSTEM", detail);
        writeAudit(conversationId, agentUserId, "ESCALATED", conversation.getStatus(), conversation.getStatus(), detail);
        pushConversationEvent(conversation.getUserId(), conversation.getId());
        return toConversationResponse(conversation);
    }

    public List<SupportAuditResponse> listAudits(Long actorUserId, Long conversationId) {
        requireSupportOrAdmin(actorUserId);
        requireConversation(conversationId);
        List<SupportConversationAudit> audits = auditMapper.selectList(new LambdaQueryWrapper<SupportConversationAudit>()
                .eq(SupportConversationAudit::getConversationId, conversationId)
                .orderByDesc(SupportConversationAudit::getCreateTime)
                .orderByDesc(SupportConversationAudit::getId));
        if (audits == null) {
            return Collections.emptyList();
        }
        return audits.stream().map(this::toAuditResponse).collect(Collectors.toList());
    }

    @Scheduled(fixedDelay = 60_000L)
    @Transactional
    public void closeInactiveAssignedHumanConversationsOnSchedule() {
        int closedCount = closeInactiveSupportConversations(LocalDateTime.now());
        if (closedCount > 0) {
            log.info("自动结束超时客服会话: count={}", closedCount);
        }
    }

    public int closeInactiveAssignedHumanConversations(LocalDateTime now) {
        return closeInactiveSupportConversations(now);
    }

    public int closeInactiveSupportConversations(LocalDateTime now) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        LocalDateTime deadline = effectiveNow.minusMinutes(30);
        List<SupportConversation> conversations = conversationMapper.selectList(new LambdaQueryWrapper<SupportConversation>()
                .in(SupportConversation::getStatus, STATUS_OPEN, STATUS_ASSIGNED, STATUS_CLOSE_REQUESTED));
        int closedCount = 0;
        for (SupportConversation conversation : conversations) {
            LocalDateTime lastServiceMessageAt = resolveLastServiceMessageAt(conversation);
            if (lastServiceMessageAt == null) {
                continue;
            }
            if (!lastServiceMessageAt.isBefore(deadline)) {
                continue;
            }
            if (hasUserReplyAtOrAfter(conversation.getId(), lastServiceMessageAt)) {
                continue;
            }
            String fromStatus = conversation.getStatus();
            conversation.setStatus(STATUS_CLOSED);
            conversation.setClosedAt(effectiveNow);
            conversation.setLastMessage(AUTO_CLOSE_MESSAGE);
            touch(conversation, effectiveNow);
            conversationMapper.updateById(conversation);
            insertMessage(conversation.getId(), null, "SYSTEM", AUTO_CLOSE_MESSAGE);
            writeAudit(conversation.getId(), null, "AUTO_CLOSED", fromStatus, STATUS_CLOSED, AUTO_CLOSE_MESSAGE);
            pushConversationEvent(conversation.getUserId(), conversation.getId());
            closedCount++;
        }
        return closedCount;
    }

    private LocalDateTime resolveLastServiceMessageAt(SupportConversation conversation) {
        if (conversation == null || conversation.getId() == null) {
            return null;
        }
        if (STATUS_CLOSE_REQUESTED.equals(conversation.getStatus())) {
            return conversation.getCloseRequestedAt();
        }
        if (STATUS_ASSIGNED.equals(conversation.getStatus())) {
            if (conversation.getLastAgentMessageAt() != null) {
                return conversation.getLastAgentMessageAt();
            }
            return findLastMessageTime(conversation.getId(), Collections.singletonList("AGENT"));
        }
        if (STATUS_OPEN.equals(conversation.getStatus()) && SOURCE_AI.equals(conversation.getSourceType())) {
            return findLastMessageTime(conversation.getId(), Collections.singletonList("AI"));
        }
        return null;
    }

    private LocalDateTime findLastMessageTime(Long conversationId, List<String> senderTypes) {
        if (conversationId == null || senderTypes == null || senderTypes.isEmpty()) {
            return null;
        }
        SupportMessage message = messageMapper.selectOne(new LambdaQueryWrapper<SupportMessage>()
                .eq(SupportMessage::getConversationId, conversationId)
                .in(SupportMessage::getSenderType, senderTypes)
                .orderByDesc(SupportMessage::getCreateTime)
                .orderByDesc(SupportMessage::getId)
                .last("limit 1"));
        return message == null ? null : message.getCreateTime();
    }

    private boolean hasUserReplyAtOrAfter(Long conversationId, LocalDateTime serviceMessageAt) {
        if (conversationId == null || serviceMessageAt == null) {
            return false;
        }
        SupportMessage lastUserMessage = messageMapper.selectOne(new LambdaQueryWrapper<SupportMessage>()
                .eq(SupportMessage::getConversationId, conversationId)
                .eq(SupportMessage::getSenderType, "USER")
                .orderByDesc(SupportMessage::getCreateTime)
                .orderByDesc(SupportMessage::getId)
                .last("limit 1"));
        return lastUserMessage != null
                && lastUserMessage.getCreateTime() != null
                && !lastUserMessage.getCreateTime().isBefore(serviceMessageAt);
    }

    private SupportConversation requireVisibleConversation(Long actorUserId, Long conversationId) {
        User actor = requireActiveUser(actorUserId);
        SupportConversation conversation = requireConversation(conversationId);
        if (!isSupportRole(actor.getRole()) && !actorUserId.equals(conversation.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该客服会话");
        }
        return conversation;
    }

    private SupportConversation requireOwnerConversation(Long userId, Long conversationId) {
        SupportConversation conversation = requireConversation(conversationId);
        if (!userId.equals(conversation.getUserId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权操作该客服会话");
        }
        return conversation;
    }

    private SupportConversation requireConversation(Long conversationId) {
        if (conversationId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "客服会话ID不能为空");
        }
        SupportConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "客服会话不存在");
        }
        return conversation;
    }

    private User requireSupportOrAdmin(Long userId) {
        User user = requireActiveUser(userId);
        if (!isSupportRole(user.getRole())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "仅客服或平台管理员可以处理会话");
        }
        return user;
    }

    private User requireActiveUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        User user = userMapper.selectById(userId);
        if (user == null || Integer.valueOf(0).equals(user.getStatus())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "账号不可用");
        }
        return user;
    }

    private boolean isSupportRole(String role) {
        return ROLE_SUPPORT.equals(role) || ROLE_ADMIN.equals(role);
    }

    private boolean hasSupportManagerScope(User user) {
        if (user == null) {
            return false;
        }
        if (ROLE_ADMIN.equals(user.getRole())) {
            return true;
        }
        if (!ROLE_SUPPORT.equals(user.getRole()) || supportAccountMapper == null) {
            return false;
        }
        SupportAccount account = supportAccountMapper.selectById(user.getId());
        return account != null
                && !Integer.valueOf(0).equals(account.getStatus())
                && SUPPORT_ROLE_MANAGER.equals(account.getSupportRole());
    }

    private SupportMessage insertMessage(Long conversationId, Long senderUserId, String senderType, String content) {
        SupportMessage message = new SupportMessage();
        message.setConversationId(conversationId);
        message.setSenderUserId(senderUserId);
        message.setSenderType(senderType);
        message.setContent(content);
        messageMapper.insert(message);
        return message;
    }

    private void pushMessageEvent(Long ownerUserId, Long conversationId) {
        if (supportPushService != null) {
            supportPushService.publishAfterCommit(ownerUserId, conversationId, "message");
        }
    }

    private void pushConversationEvent(Long ownerUserId, Long conversationId) {
        if (supportPushService != null) {
            supportPushService.publishAfterCommit(ownerUserId, conversationId, "conversation");
        }
    }

    /**
     * 签发一次性推送票据：user 通道任何登录用户可用，agent 通道需客服/管理员角色。
     */
    public SupportStreamTicketResponse issueStreamTicket(Long userId, SupportStreamTicketRequest request) {
        if (supportPushService == null) {
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "推送服务不可用");
        }
        User user = requireActiveUser(userId);
        String channel = request == null ? null : trimToNull(request.getChannel());
        if (SupportPushService.CHANNEL_AGENT.equals(channel)) {
            requireSupportOrAdmin(userId);
        } else {
            channel = SupportPushService.CHANNEL_USER;
        }
        SupportStreamTicketResponse response = new SupportStreamTicketResponse();
        response.setTicket(supportPushService.issueTicket(userId, channel));
        response.setChannel(channel);
        response.setExpiresInMs(30_000L);
        return response;
    }

    private SupportMessageResponse insertUserMessageForAiStream(Long actorUserId, Long conversationId, String content) {
        if (content == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "消息内容不能为空");
        }
        requireActiveUser(actorUserId);
        SupportConversation conversation = requireOwnerConversation(actorUserId, conversationId);
        if (STATUS_CLOSED.equals(conversation.getStatus())) {
            throw new BusinessException(ResultCode.CONFLICT, "会话已结束");
        }

        SupportMessage message = insertMessage(conversation.getId(), actorUserId, "USER", content);
        LocalDateTime messageTime = message.getCreateTime() == null ? LocalDateTime.now() : message.getCreateTime();
        conversation.setLastMessage(content);
        conversation.setLastUserMessageAt(messageTime);
        if (STATUS_CLOSE_REQUESTED.equals(conversation.getStatus())) {
            conversation.setStatus(STATUS_ASSIGNED);
        }
        touch(conversation);
        conversationMapper.updateById(conversation);
        pushMessageEvent(conversation.getUserId(), conversation.getId());
        return toMessageResponse(message);
    }

    private void streamAiReply(SseEmitter emitter, Long conversationId, SupportMessageResponse userMessage, String question) {
        try {
            sendSseEvent(emitter, "userMessage", userMessage);
            SupportConversation beforeAnswer = conversationMapper.selectById(conversationId);
            if (!canAutoReplyWithAi(beforeAnswer)) {
                sendDoneAndComplete(emitter, null);
                return;
            }

            sendSseEvent(emitter, "thinking", eventPayload("message", "客服正在思考"));
            SupportAiService.AnswerDiagnostics diagnostics = supportAiService.answerStreamingWithDiagnostics(question, chunk ->
                    sendSseEvent(emitter, "delta", eventPayload("content", chunk)));
            log.info("客服 AI 流式回复耗时: conversationId={}, source={}, modelAttempted={}, fallbackReason={}, firstChunkMs={}, totalMs={}",
                    conversationId,
                    diagnostics.getSource(),
                    diagnostics.isModelAttempted(),
                    diagnostics.getFallbackReason(),
                    diagnostics.getFirstChunkMillis(),
                    diagnostics.getTotalMillis());
            String answer = diagnostics.getAnswer();

            SupportConversation conversation = conversationMapper.selectById(conversationId);
            SupportMessageResponse assistantMessage = null;
            if (canAutoReplyWithAi(conversation) && StringUtils.hasText(answer)) {
                SupportMessage message = insertMessage(conversation.getId(), null, "AI", answer);
                conversation.setLastMessage(answer);
                touch(conversation);
                conversationMapper.updateById(conversation);
                pushMessageEvent(conversation.getUserId(), conversation.getId());
                assistantMessage = toMessageResponse(message);
            }
            sendDoneAndComplete(emitter, assistantMessage);
        } catch (RuntimeException e) {
            log.warn("客服 AI 流式回复失败: conversationId={}, message={}", conversationId, e.getMessage());
            sendErrorAndComplete(emitter, "客服暂时无法回复，请稍后重试");
        }
    }

    private LinkedHashMap<String, Object> eventPayload(String key, Object value) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put(key, value);
        return payload;
    }

    private void sendDoneAndComplete(SseEmitter emitter, SupportMessageResponse assistantMessage) {
        sendSseEvent(emitter, "done", eventPayload("message", assistantMessage));
        emitter.complete();
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            sendSseEvent(emitter, "error", eventPayload("message", message));
        } catch (RuntimeException ignored) {
            // 客户端已断开时无需继续写入。
        } finally {
            emitter.complete();
        }
    }

    private void sendSseEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("客服流式连接已关闭", e);
        }
    }

    private String toUserVisibleError(RuntimeException e) {
        if (e instanceof BusinessException && StringUtils.hasText(e.getMessage())) {
            return e.getMessage();
        }
        return "客服暂时无法回复，请稍后重试";
    }

    private void touch(SupportConversation conversation) {
        conversation.setUpdateTime(LocalDateTime.now());
    }

    private void touch(SupportConversation conversation, LocalDateTime now) {
        conversation.setUpdateTime(now == null ? LocalDateTime.now() : now);
    }

    private void notifySupportReply(SupportConversation conversation, SupportMessage replyMessage) {
        if (conversation == null || conversation.getUserId() == null || notificationProducer == null) {
            return;
        }
        if (isUserViewingHelp(conversation.getUserId(), LocalDateTime.now())) {
            return;
        }
        if (replyMessage == null || replyMessage.getId() == null) {
            log.warn("客服回复通知缺少消息ID，跳过事件发送: conversationId={}, userId={}",
                    conversation.getId(), conversation.getUserId());
            return;
        }
        try {
            NotificationEventMessage message = new NotificationEventMessage();
            message.setEventId("support-reply:" + conversation.getId() + ":" + replyMessage.getId());
            message.setEventType("SUPPORT_REPLY");
            message.setAggregateKey("SUPPORT_REPLY:" + conversation.getId());
            message.setUserId(conversation.getUserId());
            message.setChannels(List.of("IN_APP"));
            message.setContent("人工客服回复了你的咨询，请查看客服会话。");
            message.setActionHref("/help");
            message.setActionLabel("查看客服会话");
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("conversationId", conversation.getId());
            payload.put("messageId", replyMessage.getId());
            message.setPayload(payload);
            if (replyMessage != null && replyMessage.getCreateTime() != null) {
                message.setOccurredAt(replyMessage.getCreateTime());
            }
            notificationProducer.sendNotificationEvent(message);
        } catch (RuntimeException e) {
            log.warn("客服回复通知发送失败: conversationId={}, userId={}, message={}",
                    conversation.getId(), conversation.getUserId(), e.getMessage());
        }
    }

    private void queueAiReply(Long conversationId, String question) {
        if (conversationId == null || !StringUtils.hasText(question) || supportAiExecutor == null) {
            return;
        }
        MqPublishSupport.afterCommitOrNow(() -> {
            try {
                supportAiExecutor.execute(() -> generateAndPersistAiReply(conversationId, question));
            } catch (RuntimeException e) {
                log.warn("客服 AI 回复任务提交失败: conversationId={}, message={}", conversationId, e.getMessage());
            }
        });
    }

    private void generateAndPersistAiReply(Long conversationId, String question) {
        try {
            SupportConversation beforeAnswer = conversationMapper.selectById(conversationId);
            if (!canAutoReplyWithAi(beforeAnswer)) {
                return;
            }

            String answer = supportAiService.answer(question);
            SupportConversation conversation = conversationMapper.selectById(conversationId);
            if (!canAutoReplyWithAi(conversation) || !StringUtils.hasText(answer)) {
                return;
            }

            insertMessage(conversation.getId(), null, "AI", answer);
            conversation.setLastMessage(answer);
            touch(conversation);
            conversationMapper.updateById(conversation);
            pushMessageEvent(conversation.getUserId(), conversation.getId());
        } catch (RuntimeException e) {
            log.warn("客服 AI 回复生成失败: conversationId={}, message={}", conversationId, e.getMessage());
        }
    }

    private boolean canAutoReplyWithAi(SupportConversation conversation) {
        return conversation != null
                && STATUS_OPEN.equals(conversation.getStatus())
                && SOURCE_AI.equals(conversation.getSourceType())
                && conversation.getAssignedAgentId() == null;
    }

    private boolean isUserViewingHelp(Long userId, LocalDateTime now) {
        if (userId == null) {
            return false;
        }
        LocalDateTime lastSeen = helpPresence.get(userId);
        if (lastSeen == null) {
            return false;
        }
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        if (lastSeen.isBefore(effectiveNow.minusSeconds(HELP_PRESENCE_TTL_SECONDS))) {
            helpPresence.remove(userId, lastSeen);
            return false;
        }
        return true;
    }

    private String buildSubject(String subject, String initialMessage) {
        String explicit = trimToNull(subject);
        if (explicit != null) return abbreviate(explicit, 40);
        String message = trimToNull(initialMessage);
        return message == null ? "在线客服咨询" : abbreviate(message, 40);
    }

    private String abbreviate(String value, int maxLength) {
        if (value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }

    private String buildAgentJoinedMessage(User agent) {
        String nickname = agent == null ? null : trimToNull(agent.getNickname());
        return nickname == null ? "人工客服已介入" : nickname + "已介入";
    }

    private SupportConversationResponse toConversationResponse(SupportConversation conversation) {
        return toConversationResponse(conversation, loadConversationTags(conversation.getId()));
    }

    private SupportConversationResponse toConversationResponse(SupportConversation conversation, List<String> tags) {
        SupportConversationResponse response = new SupportConversationResponse();
        response.setId(conversation.getId());
        response.setUserId(conversation.getUserId());
        User customer = conversation.getUserId() == null ? null : userMapper.selectById(conversation.getUserId());
        if (customer != null) {
            response.setUserNickname(customer.getNickname());
            response.setUserPhoneMask(maskPhone(customer.getPhone()));
        }
        response.setSubject(conversation.getSubject());
        response.setStatus(conversation.getStatus());
        response.setSourceType(conversation.getSourceType());
        response.setAssignedAgentId(conversation.getAssignedAgentId());
        response.setLastMessage(conversation.getLastMessage());
        response.setCreateTime(conversation.getCreateTime());
        response.setUpdateTime(conversation.getUpdateTime());
        response.setClosedAt(conversation.getClosedAt());
        response.setFirstResponseDueAt(conversation.getFirstResponseDueAt());
        response.setFirstAgentRepliedAt(conversation.getFirstAgentRepliedAt());
        response.setLastUserMessageAt(conversation.getLastUserMessageAt());
        response.setLastAgentMessageAt(conversation.getLastAgentMessageAt());
        response.setCloseRequestReason(conversation.getCloseRequestReason());
        response.setCloseRequestedBy(conversation.getCloseRequestedBy());
        response.setCloseRequestedAt(conversation.getCloseRequestedAt());
        response.setEscalatedToAdmin(Boolean.TRUE.equals(conversation.getEscalatedToAdmin()));
        response.setEscalationReason(conversation.getEscalationReason());
        response.setEscalatedAt(conversation.getEscalatedAt());
        response.setTags(tags == null ? Collections.emptyList() : tags);
        LocalDateTime now = LocalDateTime.now();
        response.setUserWaitingSeconds(computeUserWaitingSeconds(conversation, now));
        response.setSlaOverdue(isSlaOverdue(conversation, now));
        return response;
    }

    private List<String> loadConversationTags(Long conversationId) {
        if (conversationId == null) {
            return Collections.emptyList();
        }
        List<SupportConversationTag> rows = tagMapper.selectList(new LambdaQueryWrapper<SupportConversationTag>()
                .eq(SupportConversationTag::getConversationId, conversationId));
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyList();
        }
        return rows.stream()
                .map(SupportConversationTag::getTagCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toList());
    }

    private List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return Collections.emptyList();
        }
        LinkedHashMap<String, Boolean> normalized = new LinkedHashMap<>();
        for (String value : tags) {
            String code = trimToNull(value);
            if (code == null) {
                continue;
            }
            if (!isKnownTag(code)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "客服会话标签不正确");
            }
            normalized.put(code, true);
        }
        return normalized.keySet().stream().collect(Collectors.toList());
    }

    private boolean isKnownTag(String code) {
        return "REFUND".equals(code)
                || "TICKET".equals(code)
                || "ADMISSION".equals(code)
                || "ACCOUNT".equals(code)
                || "PAYMENT_EXCEPTION".equals(code);
    }

    private String formatTagLabel(String code) {
        if ("REFUND".equals(code)) return "退款";
        if ("TICKET".equals(code)) return "票务";
        if ("ADMISSION".equals(code)) return "入场";
        if ("ACCOUNT".equals(code)) return "账号";
        if ("PAYMENT_EXCEPTION".equals(code)) return "支付异常";
        return code;
    }

    private String buildCloseRequestMessage(String reason) {
        return reason == null ? "人工客服申请结束会话，请确认是否结束。" : "人工客服申请结束会话，原因：" + reason;
    }

    private void writeAudit(Long conversationId, Long actorUserId, String action, String fromStatus, String toStatus, String detail) {
        SupportConversationAudit audit = new SupportConversationAudit();
        audit.setConversationId(conversationId);
        audit.setActorUserId(actorUserId);
        audit.setAction(action);
        audit.setFromStatus(fromStatus);
        audit.setToStatus(toStatus);
        audit.setDetail(detail);
        audit.setCreateTime(LocalDateTime.now());
        auditMapper.insert(audit);
    }

    private SupportNoteResponse toNoteResponse(SupportConversationNote note) {
        SupportNoteResponse response = new SupportNoteResponse();
        response.setId(note.getId());
        response.setConversationId(note.getConversationId());
        response.setAuthorUserId(note.getAuthorUserId());
        response.setAuthorDisplayName(buildUserDisplayName(note.getAuthorUserId()));
        response.setContent(note.getContent());
        response.setCreateTime(note.getCreateTime());
        return response;
    }

    private SupportAuditResponse toAuditResponse(SupportConversationAudit audit) {
        SupportAuditResponse response = new SupportAuditResponse();
        response.setId(audit.getId());
        response.setConversationId(audit.getConversationId());
        response.setActorUserId(audit.getActorUserId());
        response.setActorDisplayName(buildUserDisplayName(audit.getActorUserId()));
        response.setAction(audit.getAction());
        response.setFromStatus(audit.getFromStatus());
        response.setToStatus(audit.getToStatus());
        response.setDetail(audit.getDetail());
        response.setCreateTime(audit.getCreateTime());
        return response;
    }

    private SupportQuickReplyResponse toQuickReplyResponse(SupportQuickReply reply) {
        SupportQuickReplyResponse response = new SupportQuickReplyResponse();
        response.setId(reply.getId());
        response.setCategory(reply.getCategory());
        response.setTitle(reply.getTitle());
        response.setContent(reply.getContent());
        response.setSortOrder(reply.getSortOrder());
        return response;
    }

    private String buildUserDisplayName(Long userId) {
        if (userId == null) return null;
        User user = userMapper.selectById(userId);
        if (user == null) return null;
        String nickname = trimToNull(user.getNickname());
        if (nickname != null) return nickname;
        String phoneMask = maskPhone(user.getPhone());
        if (phoneMask != null) return phoneMask;
        return "用户 " + user.getId();
    }

    private Long computeUserWaitingSeconds(SupportConversation conversation, LocalDateTime now) {
        if (conversation.getLastUserMessageAt() == null) {
            return null;
        }
        LocalDateTime lastAgent = conversation.getLastAgentMessageAt();
        if (lastAgent != null && !conversation.getLastUserMessageAt().isAfter(lastAgent)) {
            return 0L;
        }
        return Math.max(0L, Duration.between(conversation.getLastUserMessageAt(), now).getSeconds());
    }

    private boolean isSlaOverdue(SupportConversation conversation, LocalDateTime now) {
        if (STATUS_CLOSED.equals(conversation.getStatus())) {
            return false;
        }
        if (conversation.getFirstAgentRepliedAt() == null
                && conversation.getFirstResponseDueAt() != null
                && now.isAfter(conversation.getFirstResponseDueAt())) {
            return true;
        }
        Long waitingSeconds = computeUserWaitingSeconds(conversation, now);
        return waitingSeconds != null && waitingSeconds > 10 * 60;
    }

    private String maskPhone(String phone) {
        String value = trimToNull(phone);
        if (value == null) return null;
        if (value.length() < 7) return value;
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private SupportMessageResponse toMessageResponse(SupportMessage message) {
        SupportMessageResponse response = new SupportMessageResponse();
        response.setId(message.getId());
        response.setConversationId(message.getConversationId());
        response.setSenderUserId(message.getSenderUserId());
        response.setSenderType(message.getSenderType());
        response.setSenderDisplayName(buildSenderDisplayName(message));
        response.setContent(message.getContent());
        response.setCreateTime(message.getCreateTime());
        return response;
    }

    private String buildSenderDisplayName(SupportMessage message) {
        if (message == null) return null;
        if ("AI".equals(message.getSenderType())) return "AI 客服";
        if ("SYSTEM".equals(message.getSenderType())) return "系统";
        if (message.getSenderUserId() == null) return null;
        User user = userMapper.selectById(message.getSenderUserId());
        if (user == null) return null;
        String nickname = trimToNull(user.getNickname());
        if (nickname != null) return nickname;
        String phoneMask = maskPhone(user.getPhone());
        if (phoneMask != null) return phoneMask;
        return "用户 " + user.getId();
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
