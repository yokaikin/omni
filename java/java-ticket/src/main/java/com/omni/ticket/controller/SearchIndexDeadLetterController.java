package com.omni.ticket.controller;

import com.omni.common.result.Result;
import com.omni.ticket.search.SearchIndexDeadLetter;
import com.omni.ticket.search.SearchIndexDeadLetterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搜索索引 MQ 死信审计内部接口：查询死信记录、重放死信消息。
 */
@RestController
@RequestMapping("/api/ticket/internal/search")
public class SearchIndexDeadLetterController {

    private static final int MAX_LIMIT = 200;

    private final SearchIndexDeadLetterService deadLetterService;
    private final String internalApiToken;

    public SearchIndexDeadLetterController(SearchIndexDeadLetterService deadLetterService,
                                           @Value("${internal.api.token:${INTERNAL_API_TOKEN:}}") String internalApiToken) {
        this.deadLetterService = deadLetterService;
        this.internalApiToken = internalApiToken;
    }

    @GetMapping("/dead-letters")
    public Result<List<SearchIndexDeadLetter>> list(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                    @RequestParam(value = "status", required = false) String status,
                                                    @RequestParam(value = "limit", defaultValue = "50") int limit) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(deadLetterService.list(status, Math.min(Math.max(limit, 1), MAX_LIMIT)));
    }

    @PostMapping("/dead-letters/{id}/replay")
    public Result<SearchIndexDeadLetter> replay(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                                @PathVariable("id") Long id) {
        if (!StringUtils.hasText(internalApiToken) || !internalApiToken.equals(token)) {
            return Result.fail(403, "无权限");
        }
        return Result.success(deadLetterService.replay(id));
    }
}
