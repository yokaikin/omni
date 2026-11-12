package com.omni.ticket.controller;

import com.omni.common.dto.OperationAuditWriteRequest;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.ticket.search.ActivitySearchIndexService;
import com.omni.ticket.search.ActivitySearchRebuildResult;
import com.omni.ticket.search.SearchIndexConsistencyResult;
import com.omni.ticket.search.SearchIndexDeadLetter;
import com.omni.ticket.search.SearchIndexDeadLetterService;
import com.omni.ticket.search.SearchIndexHealth;
import com.omni.ticket.search.SearchIndexOpsService;
import com.omni.ticket.service.UserAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@ConditionalOnProperty(prefix = "omni.search", name = "provider", havingValue = "elasticsearch")
@RequestMapping("/api/ticket/admin/search-index")
public class ActivitySearchIndexController {

    private static final Logger log = LoggerFactory.getLogger(ActivitySearchIndexController.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final int MAX_DEAD_LETTER_LIMIT = 200;

    private final ActivitySearchIndexService indexService;
    private final SearchIndexOpsService opsService;
    private final SearchIndexDeadLetterService deadLetterService;
    private final UserAccessService userAccessService;

    @Autowired
    public ActivitySearchIndexController(ActivitySearchIndexService indexService,
                                         SearchIndexOpsService opsService,
                                         SearchIndexDeadLetterService deadLetterService,
                                         UserAccessService userAccessService) {
        this.indexService = indexService;
        this.opsService = opsService;
        this.deadLetterService = deadLetterService;
        this.userAccessService = userAccessService;
    }

    @GetMapping("/health")
    public Result<SearchIndexHealth> health(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (checkOpsPermissionRole(operatorId) == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(opsService.health());
    }

    @GetMapping("/consistency")
    public Result<SearchIndexConsistencyResult> consistency(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (checkOpsPermissionRole(operatorId) == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(opsService.checkConsistency());
    }

    @PostMapping("/rebuild")
    public Result<ActivitySearchRebuildResult> rebuild(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkOpsPermissionRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        ActivitySearchRebuildResult result = indexService.rebuildAll();
        writeOpsAudit(operatorId, role, "search_index.rebuild", null, "全量重建搜索索引",
                "indexed=" + result.getIndexedCount());
        return Result.success(result);
    }

    @GetMapping("/dead-letters")
    public Result<List<SearchIndexDeadLetter>> listDeadLetters(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (checkOpsPermissionRole(operatorId) == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(deadLetterService.list(status,
                Math.min(Math.max(limit, 1), MAX_DEAD_LETTER_LIMIT)));
    }

    @PostMapping("/dead-letters/{id}/replay")
    public Result<SearchIndexDeadLetter> replayDeadLetter(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable("id") Long id) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkOpsPermissionRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        SearchIndexDeadLetter deadLetter = deadLetterService.replay(id);
        writeOpsAudit(operatorId, role, "search_index.dead_letter.replay", id,
                "重放搜索索引死信", deadLetter.getStatus());
        return Result.success(deadLetter);
    }

    private String checkOpsPermissionRole(Long operatorId) {
        return userAccessService.requireAdminOrAnyPermissionRole(operatorId, "activity.manage", "rbac.manage");
    }

    private void writeOpsAudit(Long operatorId, String operatorRole, String action, Long targetId,
                               String reason, String result) {
        try {
            OperationAuditWriteRequest request = new OperationAuditWriteRequest();
            request.setOperatorId(operatorId);
            request.setOperatorRole(operatorRole);
            request.setAction(action);
            request.setTargetType("search_index");
            request.setTargetId(targetId);
            request.setReason(reason);
            request.setResult(result);
            request.setSuccess(true);
            userAccessService.writeOperationAudit(request);
        } catch (RuntimeException e) {
            log.warn("搜索索引运维审计写入失败 action={} targetId={}: {}", action, targetId, e.getMessage());
        }
    }

    private Long parseOperatorId(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(JwtUtil.parseToken(authorization.substring(BEARER_PREFIX.length())).getSubject());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
