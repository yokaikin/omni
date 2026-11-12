package com.omni.user.controller;

import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.user.dto.HelpFaqResponse;
import com.omni.user.dto.SupportAccountRequest;
import com.omni.user.dto.SupportAccountResponse;
import com.omni.user.dto.SupportAuditResponse;
import com.omni.user.dto.SupportCloseRejectRequest;
import com.omni.user.dto.SupportCloseRequest;
import com.omni.user.dto.SupportContextResponse;
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
import com.omni.user.service.CustomerSupportService;
import com.omni.user.service.HelpCenterService;
import com.omni.user.service.SupportAccountService;
import com.omni.user.service.SupportContextService;
import com.omni.user.service.SupportPushService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/user")
public class SupportController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final HelpCenterService helpCenterService;
    private final CustomerSupportService customerSupportService;
    private final SupportAccountService supportAccountService;
    private final SupportContextService supportContextService;
    private final SupportPushService supportPushService;

    @Autowired
    public SupportController(HelpCenterService helpCenterService,
                             CustomerSupportService customerSupportService,
                             SupportAccountService supportAccountService,
                             SupportContextService supportContextService,
                             SupportPushService supportPushService) {
        this.helpCenterService = helpCenterService;
        this.customerSupportService = customerSupportService;
        this.supportAccountService = supportAccountService;
        this.supportContextService = supportContextService;
        this.supportPushService = supportPushService;
    }

    @PostMapping("/support/stream-ticket")
    public Result<SupportStreamTicketResponse> issueStreamTicket(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) SupportStreamTicketRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.issueStreamTicket(userId, request));
    }

    @GetMapping(value = "/support/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter supportStream(@RequestParam("ticket") String ticket) {
        return supportPushService.subscribe(ticket);
    }

    @GetMapping("/help/faqs")
    public Result<List<HelpFaqResponse>> listFaqs() {
        return Result.success(helpCenterService.listFaqs());
    }

    @PostMapping("/support/conversations")
    public Result<SupportConversationResponse> startConversation(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) SupportConversationRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.startConversation(userId, request));
    }

    @GetMapping("/support/conversations/my")
    public Result<List<SupportConversationResponse>> listMine(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.listMine(userId));
    }

    @GetMapping("/support/conversations/{id}/messages")
    public Result<List<SupportMessageResponse>> listMessages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.listMessages(userId, id));
    }

    @PostMapping("/support/presence/help")
    public Result<Void> markHelpPresence(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        customerSupportService.markHelpPresence(userId);
        return Result.success();
    }

    @PostMapping("/support/presence/help/leave")
    public Result<Void> clearHelpPresence(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        customerSupportService.clearHelpPresence(userId);
        return Result.success();
    }

    @PostMapping("/support/conversations/{id}/messages")
    public Result<SupportMessageResponse> sendMessage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody SupportMessageRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.sendMessage(userId, id, request));
    }

    @PostMapping(value = "/support/conversations/{id}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStream(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody SupportMessageRequest request) {
        Long userId = parseUserId(authorization);
        return customerSupportService.streamMessage(userId, id, request);
    }

    @PostMapping("/support/conversations/{id}/handoff")
    public Result<SupportConversationResponse> handoff(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.handoff(userId, id));
    }

    @PostMapping("/support/conversations/{id}/close")
    public Result<SupportConversationResponse> confirmClose(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.confirmClose(userId, id));
    }

    @PostMapping("/support/conversations/{id}/close/reject")
    public Result<SupportConversationResponse> rejectClose(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) SupportCloseRejectRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.rejectClose(userId, id, request));
    }

    @GetMapping("/support/agent/conversations")
    public Result<List<SupportConversationResponse>> listAgentConversations(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String queue) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.listAgentConversations(userId, status, queue));
    }

    @GetMapping("/support/agent/conversations/{id}/notes")
    public Result<List<SupportNoteResponse>> listNotes(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.listNotes(userId, id));
    }

    @PostMapping("/support/agent/conversations/{id}/notes")
    public Result<SupportNoteResponse> addNote(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) SupportNoteRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.addNote(userId, id, request));
    }

    @PutMapping("/support/agent/conversations/{id}/tags")
    public Result<SupportConversationResponse> updateTags(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) SupportTagUpdateRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.updateTags(userId, id, request));
    }

    @GetMapping("/support/agent/quick-replies")
    public Result<List<SupportQuickReplyResponse>> listQuickReplies(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.listQuickReplies(userId));
    }

    @GetMapping("/support/agent/accounts")
    public Result<List<SupportAccountResponse>> listEnabledSupportAgents(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportAccountService.listEnabledAgents(userId));
    }

    @PostMapping("/support/agent/conversations/{id}/claim")
    public Result<SupportConversationResponse> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.claim(userId, id));
    }

    @PostMapping("/support/agent/conversations/{id}/close")
    public Result<SupportConversationResponse> close(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) SupportCloseRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.close(userId, id, request));
    }

    @PostMapping("/support/agent/conversations/{id}/transfer")
    public Result<SupportConversationResponse> transfer(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) SupportTransferRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.transfer(userId, id, request));
    }

    @PostMapping("/support/agent/conversations/{id}/escalate")
    public Result<SupportConversationResponse> escalate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) SupportCloseRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.escalate(userId, id, request));
    }

    @GetMapping("/support/agent/conversations/{id}/audits")
    public Result<List<SupportAuditResponse>> listAudits(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(customerSupportService.listAudits(userId, id));
    }

    @GetMapping("/support/agent/conversations/{id}/context")
    public Result<SupportContextResponse> getContext(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportContextService.getContext(userId, id));
    }

    @GetMapping("/support/admin/accounts")
    public Result<List<SupportAccountResponse>> listSupportAccounts(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportAccountService.list(userId));
    }

    @PostMapping("/support/admin/accounts")
    public Result<SupportAccountResponse> createSupportAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody SupportAccountRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportAccountService.create(userId, request));
    }

    @PutMapping("/support/admin/accounts/{id}")
    public Result<SupportAccountResponse> updateSupportAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody SupportAccountRequest request) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportAccountService.update(userId, id, request));
    }

    @PostMapping("/support/admin/accounts/{id}/deactivate")
    public Result<SupportAccountResponse> deactivateSupportAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportAccountService.deactivate(userId, id));
    }

    @DeleteMapping("/support/admin/accounts/{id}")
    public Result<SupportAccountResponse> deleteSupportAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long userId = parseUserId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(supportAccountService.delete(userId, id));
    }

    private Long parseUserId(String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            Claims claims = JwtUtil.parseToken(authorization.substring(BEARER_PREFIX.length()));
            return Long.valueOf(claims.getSubject());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
