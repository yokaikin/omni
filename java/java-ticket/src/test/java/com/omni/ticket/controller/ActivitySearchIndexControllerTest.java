package com.omni.ticket.controller;

import com.omni.common.result.Result;
import com.omni.common.util.JwtUtil;
import com.omni.ticket.search.ActivitySearchIndexService;
import com.omni.ticket.search.ActivitySearchRebuildResult;
import com.omni.ticket.search.SearchIndexConsistencyResult;
import com.omni.ticket.search.SearchIndexDeadLetter;
import com.omni.ticket.search.SearchIndexDeadLetterService;
import com.omni.ticket.search.SearchIndexHealth;
import com.omni.ticket.search.SearchIndexOpsService;
import com.omni.ticket.service.UserAccessService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivitySearchIndexControllerTest {

    private final ActivitySearchIndexService indexService = mock(ActivitySearchIndexService.class);
    private final SearchIndexOpsService opsService = mock(SearchIndexOpsService.class);
    private final SearchIndexDeadLetterService deadLetterService = mock(SearchIndexDeadLetterService.class);
    private final UserAccessService userAccessService = mock(UserAccessService.class);
    private final ActivitySearchIndexController controller =
            new ActivitySearchIndexController(indexService, opsService, deadLetterService, userAccessService);

    @Test
    void rebuildRequiresAuthorization() {
        Result<ActivitySearchRebuildResult> result = controller.rebuild(null);

        assertEquals(401, result.getCode());
        verify(indexService, never()).rebuildAll();
    }

    @Test
    void rebuildRejectsUserWithoutPermission() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2004L, "activity.manage", "rbac.manage"))
                .thenReturn(null);

        Result<ActivitySearchRebuildResult> result = controller.rebuild(userToken());

        assertEquals(403, result.getCode());
        verify(indexService, never()).rebuildAll();
    }

    @Test
    void rebuildDelegatesForAdmin() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage"))
                .thenReturn("admin");
        ActivitySearchRebuildResult rebuildResult = new ActivitySearchRebuildResult();
        rebuildResult.setIndexedCount(2L);
        rebuildResult.setIndexName("omni_activity_v20260606");
        rebuildResult.setAliasName("omni_activity_current");
        rebuildResult.setStartedAt(LocalDateTime.parse("2026-06-06T20:00:00"));
        rebuildResult.setFinishedAt(LocalDateTime.parse("2026-06-06T20:00:01"));
        when(indexService.rebuildAll()).thenReturn(rebuildResult);

        Result<ActivitySearchRebuildResult> result = controller.rebuild(adminToken());

        assertEquals(200, result.getCode());
        assertEquals(2L, result.getData().getIndexedCount());
        verify(indexService).rebuildAll();
    }

    @Test
    void rebuildChecksExpectedPermissions() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage"))
                .thenReturn("admin");
        when(indexService.rebuildAll()).thenReturn(new ActivitySearchRebuildResult());

        controller.rebuild(adminToken());

        verify(userAccessService).requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage");
        verify(userAccessService, never()).requireAdminOrOrganizerOrAnyPermissionRole(anyLong(), eq("activity.manage"));
    }

    @Test
    void healthRequiresAuthorization() {
        Result<SearchIndexHealth> result = controller.health(null);

        assertEquals(401, result.getCode());
        verify(opsService, never()).health();
    }

    @Test
    void healthDelegatesForAdmin() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage"))
                .thenReturn("admin");
        SearchIndexHealth health = new SearchIndexHealth();
        health.setAvailable(true);
        health.setAliasName("omni_activity_current");
        health.setIndexNames(List.of("omni_activity_v1_20260101"));
        health.setDocCount(3L);
        health.setCheckedAt(LocalDateTime.parse("2026-11-12T10:00:00"));
        when(opsService.health()).thenReturn(health);

        Result<SearchIndexHealth> result = controller.health(adminToken());

        assertEquals(200, result.getCode());
        assertEquals(Boolean.TRUE, result.getData().getAvailable());
        assertEquals(3L, result.getData().getDocCount());
        verify(opsService).health();
    }

    @Test
    void consistencyRejectsUserWithoutPermission() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2004L, "activity.manage", "rbac.manage"))
                .thenReturn(null);

        Result<SearchIndexConsistencyResult> result = controller.consistency(userToken());

        assertEquals(403, result.getCode());
        verify(opsService, never()).checkConsistency();
    }

    @Test
    void consistencyDelegatesForAdmin() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage"))
                .thenReturn("admin");
        SearchIndexConsistencyResult consistency = new SearchIndexConsistencyResult();
        consistency.setDbCount(3);
        consistency.setIndexCount(3);
        consistency.setMissingInIndex(List.of());
        consistency.setStaleInIndex(List.of());
        consistency.setConsistent(true);
        consistency.setCheckedAt(LocalDateTime.parse("2026-11-12T10:00:00"));
        when(opsService.checkConsistency()).thenReturn(consistency);

        Result<SearchIndexConsistencyResult> result = controller.consistency(adminToken());

        assertEquals(200, result.getCode());
        assertEquals(Boolean.TRUE, result.getData().getConsistent());
        verify(opsService).checkConsistency();
    }

    @Test
    void listDeadLettersDelegatesForAdminWithClampedLimit() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage"))
                .thenReturn("admin");
        when(deadLetterService.list(null, 200)).thenReturn(List.of());

        Result<List<SearchIndexDeadLetter>> result = controller.listDeadLetters(adminToken(), null, 500);

        assertEquals(200, result.getCode());
        verify(deadLetterService).list(null, 200);
    }

    @Test
    void replayDeadLetterRejectsUserWithoutPermission() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2004L, "activity.manage", "rbac.manage"))
                .thenReturn(null);

        Result<SearchIndexDeadLetter> result = controller.replayDeadLetter(userToken(), 1L);

        assertEquals(403, result.getCode());
        verify(deadLetterService, never()).replay(anyLong());
    }

    @Test
    void replayDeadLetterDelegatesForAdmin() {
        when(userAccessService.requireAdminOrAnyPermissionRole(2002L, "activity.manage", "rbac.manage"))
                .thenReturn("admin");
        SearchIndexDeadLetter record = new SearchIndexDeadLetter();
        record.setId(7L);
        record.setStatus(SearchIndexDeadLetter.STATUS_REPLAYED);
        when(deadLetterService.replay(7L)).thenReturn(record);

        Result<SearchIndexDeadLetter> result = controller.replayDeadLetter(adminToken(), 7L);

        assertEquals(200, result.getCode());
        assertEquals(Long.valueOf(7L), result.getData().getId());
        verify(deadLetterService).replay(7L);
    }

    private String adminToken() {
        return "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin");
    }

    private String userToken() {
        return "Bearer " + JwtUtil.generateToken(2004L, "13900000001", "user");
    }
}
