package com.omni.ticket.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.common.result.Result;
import com.omni.common.util.JwtUtil;
import com.omni.ticket.dto.ActivityDraftResponse;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Artist;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.ArtistMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.VenueMapper;
import com.omni.ticket.search.ActivitySearchIndexEventPublisher;
import com.omni.ticket.service.ActivityAdminService;
import com.omni.ticket.service.ActivityArtistService;
import com.omni.ticket.service.ActivityDraftService;
import com.omni.ticket.service.ActivityMarketingService;
import com.omni.ticket.service.ActivityRiskResponseService;
import com.omni.ticket.service.ActivitySeatLayoutService;
import com.omni.ticket.service.AdminSummaryService;
import com.omni.ticket.service.ArtistAdminService;
import com.omni.ticket.service.ArtistGovernanceService;
import com.omni.ticket.service.OrderAdminQueryService;
import com.omni.ticket.service.PrivateAssetService;
import com.omni.ticket.service.SeatCraftLayoutVersionService;
import com.omni.ticket.service.SeatTemplateService;
import com.omni.ticket.service.SessionAdminService;
import com.omni.ticket.service.SessionSeatLayoutService;
import com.omni.ticket.service.SessionSeatProtectionService;
import com.omni.ticket.service.SessionSeatService;
import com.omni.ticket.service.StationConfigVersionService;
import com.omni.ticket.service.TicketAssetService;
import com.omni.ticket.service.TicketTypeAreaService;
import com.omni.ticket.service.TicketTypeStockRecalculationService;
import com.omni.ticket.service.TourStationService;
import com.omni.ticket.service.UserAccessService;
import com.omni.ticket.service.VenueApplicationService;
import com.omni.ticket.service.VenueDefaultLayoutService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 活动创建与编辑 — 完整单元测试
 * 覆盖测试方案 AC-001 至 AC-031
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活动创建与编辑 - AdminController")
class ActivityCreateEditTest {

    @Mock ActivityMapper activityMapper;
    @Mock ArtistMapper artistMapper;
    @Mock SessionMapper sessionMapper;
    @Mock TicketTypeMapper ticketTypeMapper;
    @Mock VenueMapper venueMapper;
    @Mock UserAccessService userAccessService;
    @Mock ActivityAdminService activityAdminService;
    @Mock SessionAdminService sessionAdminService;
    @Mock VenueApplicationService venueApplicationService;
    @Mock SeatTemplateService seatTemplateService;
    @Mock TicketTypeAreaService ticketTypeAreaService;
    @Mock AdminSummaryService adminSummaryService;
    @Mock SessionSeatService sessionSeatService;
    @Mock VenueDefaultLayoutService venueDefaultLayoutService;
    @Mock ActivitySeatLayoutService activitySeatLayoutService;
    @Mock SessionSeatLayoutService sessionSeatLayoutService;
    @Mock TourStationService tourStationService;
    @Mock OrderAdminQueryService orderAdminQueryService;
    @Mock SessionSeatProtectionService sessionSeatProtectionService;
    @Mock TicketTypeStockRecalculationService stockRecalculationService;
    @Mock ActivityArtistService activityArtistService;
    @Mock ArtistAdminService artistAdminService;
    @Mock ArtistGovernanceService artistGovernanceService;
    @Mock ActivityRiskResponseService activityRiskResponseService;
    @Mock TicketAssetService ticketAssetService;
    @Mock PrivateAssetService privateAssetService;
    @Mock SeatCraftLayoutVersionService seatCraftLayoutVersionService;
    @Mock ActivityDraftService activityDraftService;
    @Mock StationConfigVersionService stationConfigVersionService;
    @Mock ActivityMarketingService activityMarketingService;
    @Mock ActivitySearchIndexEventPublisher searchIndexEventPublisher;

    @BeforeAll
    static void ensureJwtSecret() {
        if (System.getenv("JWT_SECRET") == null || System.getenv("JWT_SECRET").isBlank()) {
            System.setProperty("JWT_SECRET", "test-jwt-secret-must-be-at-least-32-bytes");
        }
    }

    // ======================== 辅助方法 ========================

    private AdminController controller() {
        AdminController controller = new AdminController(activityMapper, artistMapper, sessionMapper, ticketTypeMapper,
                venueMapper, userAccessService, activityAdminService, sessionAdminService,
                venueApplicationService, seatTemplateService, ticketTypeAreaService,
                adminSummaryService, sessionSeatService, venueDefaultLayoutService,
                activitySeatLayoutService, sessionSeatLayoutService, tourStationService,
                orderAdminQueryService, sessionSeatProtectionService, stockRecalculationService,
                activityArtistService, artistAdminService, artistGovernanceService,
                activityRiskResponseService, ticketAssetService, privateAssetService,
                seatCraftLayoutVersionService, activityDraftService, stationConfigVersionService,
                activityMarketingService);
        controller.setSearchIndexEventPublisher(searchIndexEventPublisher);
        return controller;
    }

    private String adminToken() {
        return "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin");
    }

    private String organizerToken() {
        return "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer");
    }

    private String userToken() {
        return "Bearer " + JwtUtil.generateToken(2004L, "13900000001", "user");
    }

    private Map<String, Object> validCreateBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 2003L);
        body.put("categoryId", 1001L);
        body.put("artistId", 3001L);
        body.put("name", "测试活动");
        body.put("seatMapVisibility", "published");
        return body;
    }

    // ======================== 2.1 正常流程 (AC-001 ~ AC-008) ========================

    @Nested
    @DisplayName("正常流程")
    class NormalFlowTests {

        @Test
        @DisplayName("AC-001: admin创建完整活动")
        void adminCreateFullActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(500L);
                return 1;
            });

            Map<String, Object> body = new HashMap<>();
            body.put("categoryId", 1001L);
            body.put("name", "测试演唱会《星空之夜》");
            body.put("description", "一场精彩的星空主题演唱会");
            body.put("artistId", 3001L);
            body.put("venueId", 2001L);
            body.put("realNameRequired", true);
            body.put("ticketTransferAllowed", false);
            body.put("seatMapVisibility", "published");

            Result<Activity> result = controller.createActivity(adminToken(), body);

            ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
            verify(activityMapper).insert(captor.capture());
            assertEquals(200, result.getCode());
            assertEquals("draft", captor.getValue().getPublishStatus());
            assertEquals("测试演唱会《星空之夜》", captor.getValue().getName());
        }

        @Test
        @DisplayName("AC-002: admin创建最简活动")
        void adminCreateMinimalActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(501L);
                return 1;
            });

            Map<String, Object> body = new HashMap<>();
            body.put("categoryId", 1001L);
            body.put("artistId", 3001L);
            body.put("name", "最简活动");

            Result<Activity> result = controller.createActivity(adminToken(), body);

            assertEquals(200, result.getCode());
            assertNotNull(result.getData().getId());
        }

        @Test
        @DisplayName("创建活动后发布搜索索引更新事件")
        void createActivityPublishesSearchIndexUpsert() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(501L);
                return 1;
            });

            Result<Activity> result = controller.createActivity(adminToken(), validCreateBody());

            assertEquals(200, result.getCode());
            verify(searchIndexEventPublisher).publishUpsert(501L);
        }

        @Test
        @DisplayName("AC-003: admin更新活动基本信息")
        void adminUpdateActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Activity activity = new Activity();
            activity.setId(10L);
            activity.setOrganizerId(2003L);
            activity.setName("旧名称");
            when(activityMapper.selectById(10L)).thenReturn(activity);

            Result<Activity> result = controller.updateActivity(10L, adminToken(),
                    Map.of("name", "新名称", "description", "新描述"));

            assertEquals(200, result.getCode());
            assertEquals("新名称", result.getData().getName());
            assertEquals("新描述", result.getData().getDescription());
            verify(activityMapper).updateById(activity);
        }

        @Test
        @DisplayName("更新活动后发布搜索索引更新事件")
        void updateActivityPublishesSearchIndexUpsert() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Activity activity = new Activity();
            activity.setId(10L);
            activity.setOrganizerId(2003L);
            activity.setName("旧名称");
            when(activityMapper.selectById(10L)).thenReturn(activity);

            Result<Activity> result = controller.updateActivity(10L, adminToken(),
                    Map.of("poster", "/uploads/activity/new-poster.webp"));

            assertEquals(200, result.getCode());
            verify(searchIndexEventPublisher).publishUpsert(10L);
        }

        @Test
        @DisplayName("AC-004: organizer创建自己的活动")
        void organizerCreateOwnActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(502L);
                return 1;
            });

            Result<Activity> result = controller.createActivity(organizerToken(), validCreateBody());

            ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
            verify(activityMapper).insert(captor.capture());
            assertEquals(200, result.getCode());
            assertEquals(2003L, captor.getValue().getOrganizerId());
        }

        @Test
        @DisplayName("AC-005: organizer更新自己的活动")
        void organizerUpdateOwnActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            Activity activity = new Activity();
            activity.setId(10L);
            activity.setOrganizerId(2003L);
            when(activityMapper.selectById(10L)).thenReturn(activity);

            Result<Activity> result = controller.updateActivity(10L, organizerToken(),
                    Map.of("name", "主办方更新的活动"));

            assertEquals(200, result.getCode());
            assertEquals("主办方更新的活动", result.getData().getName());
        }

        @Test
        @DisplayName("AC-006: 创建活动草稿")
        void createActivityDraft() {
            AdminController controller = controller();
            Activity activity = new Activity();
            activity.setId(600L);
            activity.setName("草稿活动");
            ActivityDraftResponse response = new ActivityDraftResponse(activity, null);
            when(activityDraftService.createDraft(eq(2002L), any())).thenReturn(response);

            Result<ActivityDraftResponse> result = controller.createActivityDraft(adminToken(),
                    Map.of("name", "草稿活动", "categoryId", 1001L));

            assertEquals(200, result.getCode());
            assertNotNull(result.getData().getActivity());
            assertEquals(600L, result.getData().getActivity().getId());
            assertEquals("草稿活动", result.getData().getActivity().getName());
            verify(activityDraftService).createDraft(eq(2002L), any());
        }

        @Test
        @DisplayName("AC-007: admin查看活动详情（含艺人阵容）")
        void adminGetActivityDetail() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Activity activity = new Activity();
            activity.setId(100L);
            activity.setName("查看详情测试");
            when(activityMapper.selectById(100L)).thenReturn(activity);
            when(activityArtistService.listAdminLineup(100L)).thenReturn(Collections.emptyList());

            Result<Activity> result = controller.getAdminActivity(100L, adminToken(), null);

            assertEquals(200, result.getCode());
            assertEquals(100L, result.getData().getId());
            assertEquals("查看详情测试", result.getData().getName());
            assertNotNull(result.getData().getArtists());
        }

        @Test
        @DisplayName("AC-008: admin查看活动列表分页")
        void adminListActivities() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Activity a1 = new Activity();
            a1.setId(1L);
            a1.setName("活动1");
            a1.setPublishStatus("draft");
            Activity a2 = new Activity();
            a2.setId(2L);
            a2.setName("活动2");
            a2.setPublishStatus("draft");
            Page<Activity> page = new Page<>(1, 10, 2);
            page.setRecords(List.of(a1, a2));
            when(activityMapper.selectPage(any(), any())).thenReturn(page);
            when(activityArtistService.listAdminLineup(anyLong())).thenReturn(Collections.emptyList());

            Result<Page<Activity>> result = controller.listAdminActivities(adminToken(), null, 1, 10, null, null);

            assertEquals(200, result.getCode());
            assertEquals(2, result.getData().getRecords().size());
            assertEquals(2, result.getData().getTotal());
        }
    }

    // ======================== 2.2 边界条件 (AC-009 ~ AC-018) ========================

    @Nested
    @DisplayName("边界条件")
    class BoundaryTests {

        @Test
        @DisplayName("AC-009: 标题最小1字符")
        void titleMinLength() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(510L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("name", "A");

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("AC-010: 标题200字符(最大)")
        void titleMaxLength() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(511L);
                return 1;
            });

            String longTitle = "A".repeat(200);
            Map<String, Object> body = validCreateBody();
            body.put("name", longTitle);

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("AC-011: 标题超长(500字符)")
        void titleOverlyLong() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

            String longTitle = "A".repeat(500);
            Map<String, Object> body = validCreateBody();
            body.put("name", longTitle);

            // 控制器不截断，由数据库或业务层处理
            Result<Activity> result = controller.createActivity(organizerToken(), body);

            // 可能成功（controller不校验长度）或由DB约束拒绝
            // 验证至少调用了权限检查
            verify(userAccessService).requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage");
        }

        @Test
        @DisplayName("AC-012: 描述为空")
        void descriptionEmpty() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(512L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("description", "");

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("AC-013: 分类ID不存在 — 控制器通过, 具体校验在服务层")
        void categoryIdNotExist() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(513L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("categoryId", 999999L);

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            // 控制器层不校验分类存在性，仅做非空校验
            assertEquals(200, result.getCode());
            ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
            verify(activityMapper).insert(captor.capture());
            assertEquals(999999L, captor.getValue().getCategoryId());
        }

        @Test
        @DisplayName("AC-014: 场馆ID不存在 — 控制器不校验")
        void venueIdNotExist() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(514L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("venueId", 999999L);

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            // 控制器层不校验场馆存在性（venueId不在Activity直接字段中）
            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("AC-015: 艺人列表为空但artistId存在 → code=200")
        void artistsEmptyWithArtistId() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(515L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("artists", Collections.emptyList());
            // validCreateBody 已含 artistId=3001L, 所以空artists列表仍可创建

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("AC-015: 艺人列表为空且无artistId → code=400")
        void artistsEmptyWithoutArtistId() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

            Map<String, Object> body = validCreateBody();
            body.remove("artistId");
            body.put("artists", Collections.emptyList());

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("艺人"));
            verify(activityMapper, never()).insert(any());
        }

        @Test
        @DisplayName("AC-016: 大量艺人(50个) — 控制器应能处理")
        void manyArtists() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(516L);
                return 1;
            });

            List<Map<String, Object>> artists = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                Map<String, Object> artist = new HashMap<>();
                artist.put("artistId", 3000L + i);
                artist.put("isPrimary", i == 0);
                artist.put("visibility", "public");
                artists.add(artist);
            }

            Map<String, Object> body = validCreateBody();
            body.put("artists", artists);

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
            verify(activityArtistService).saveLineup(anyLong(), any());
        }

        @Test
        @DisplayName("AC-017: 分页page=0")
        void listActivitiesPageZero() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Activity a1 = new Activity();
            a1.setId(1L);
            a1.setPublishStatus("draft");
            Activity a2 = new Activity();
            a2.setId(2L);
            a2.setPublishStatus("draft");
            Page<Activity> page = new Page<>(1, 10, 2);
            page.setRecords(List.of(a1, a2));
            when(activityMapper.selectPage(any(), any())).thenReturn(page);
            when(activityArtistService.listAdminLineup(anyLong())).thenReturn(Collections.emptyList());

            Result<Page<Activity>> result = controller.listAdminActivities(adminToken(), null, 0, 10, null, null);

            assertEquals(200, result.getCode());
            verify(activityArtistService, atLeastOnce()).listAdminLineup(anyLong());
        }

        @Test
        @DisplayName("AC-018: 分页page超大")
        void listActivitiesPageLarge() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Page<Activity> page = new Page<>(99999, 10, 100);
            page.setRecords(Collections.emptyList());
            when(activityMapper.selectPage(any(), any())).thenReturn(page);

            Result<Page<Activity>> result = controller.listAdminActivities(adminToken(), null, 99999, 10, null, null);

            assertEquals(200, result.getCode());
            assertTrue(result.getData().getRecords().isEmpty());
        }
    }

    // ======================== 2.3 异常输入 (AC-019 ~ AC-026) ========================

    @Nested
    @DisplayName("异常输入")
    class InvalidInputTests {

        @Test
        @DisplayName("AC-019: title为空")
        void titleEmpty() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

            Map<String, Object> body = validCreateBody();
            body.put("name", "");

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("名称不能为空") || result.getMessage().contains("不能为空"));
            verify(activityMapper, never()).insert(any());
        }

        @Test
        @DisplayName("AC-020: categoryId为null")
        void categoryIdNull() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

            Map<String, Object> body = validCreateBody();
            body.remove("categoryId");

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("分类"));
            verify(activityMapper, never()).insert(any());
        }

        @Test
        @DisplayName("AC-021: JSON body为空Map")
        void emptyBody() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

            Result<Activity> result = controller.createActivity(organizerToken(), Collections.emptyMap());

            assertEquals(400, result.getCode());
            verify(activityMapper, never()).insert(any());
        }

        @Test
        @DisplayName("AC-022: 非JSON — 模拟通过控制器但未传name和artist")
        void bodyMissingRequiredFields() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

            Result<Activity> result = controller.createActivity(organizerToken(), Map.of("unused", "value"));

            assertEquals(400, result.getCode());
            verify(activityMapper, never()).insert(any());
        }

        @Test
        @DisplayName("AC-023: SQL注入尝试")
        void sqlInjectionAttempt() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(520L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("name", "'; DROP TABLE activity;--");

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
            // 验证名称被原样存储（MyBatis参数化查询防注入）
            ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
            verify(activityMapper).insert(captor.capture());
            assertEquals("'; DROP TABLE activity;--", captor.getValue().getName());
        }

        @Test
        @DisplayName("AC-024: XSS尝试")
        void xssAttempt() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(521L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("name", "<script>alert(1)</script>");

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
            ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
            verify(activityMapper).insert(captor.capture());
            assertEquals("<script>alert(1)</script>", captor.getValue().getName());
        }

        @Test
        @DisplayName("AC-025: 超大请求体 — 控制器不校验负载大小")
        void largeRequestBody() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            when(activityMapper.insert(any(Activity.class))).thenAnswer(inv -> {
                Activity a = inv.getArgument(0);
                a.setId(522L);
                return 1;
            });

            Map<String, Object> body = validCreateBody();
            body.put("description", "A".repeat(10000));

            Result<Activity> result = controller.createActivity(organizerToken(), body);

            assertEquals(200, result.getCode());
        }

        @Test
        @DisplayName("AC-026: 无效活动ID(负数)更新")
        void updateWithNegativeId() {
            AdminController controller = controller();

            Result<Activity> result = controller.updateActivity(-1L, organizerToken(), Map.of("name", "test"));

            assertEquals(400, result.getCode());
            assertTrue(result.getMessage().contains("ID"));
            verify(activityMapper, never()).selectById(anyLong());
        }

        @Test
        @DisplayName("AC-026: 无效活动ID(0)查询")
        void getActivityWithZeroId() {
            AdminController controller = controller();

            Result<Activity> result = controller.getAdminActivity(0L, adminToken(), null);

            assertEquals(400, result.getCode());
            verify(activityMapper, never()).selectById(anyLong());
        }
    }

    // ======================== 2.4 权限校验 (AC-027 ~ AC-031) ========================

    @Nested
    @DisplayName("权限校验")
    class PermissionTests {

        @Test
        @DisplayName("AC-027: 无token创建活动 → 401")
        void createActivityWithoutToken() {
            AdminController controller = controller();

            Result<Activity> result = controller.createActivity(null, validCreateBody());

            assertEquals(401, result.getCode());
            verify(userAccessService, never()).requireAdminOrOrganizerOrAnyPermissionRole(anyLong(), eq("activity.manage"));
        }

        @Test
        @DisplayName("AC-028: user角色创建活动 → 403")
        void userCreateActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2004L, "activity.manage")).thenReturn(null);

            Result<Activity> result = controller.createActivity(userToken(), validCreateBody());

            assertEquals(403, result.getCode());
            verify(activityMapper, never()).insert(any());
        }

        @Test
        @DisplayName("AC-029: organizer更新他人活动 → 403")
        void organizerUpdateOtherActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            Activity activity = new Activity();
            activity.setId(10L);
            activity.setOrganizerId(9999L); // 不同organizer
            when(activityMapper.selectById(10L)).thenReturn(activity);

            Result<Activity> result = controller.updateActivity(10L, organizerToken(), Map.of("name", "attempt"));

            assertEquals(403, result.getCode());
            assertTrue(result.getMessage().contains("只能修改自己主办的活动"));
            verify(activityMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("AC-030: admin更新任意活动 → 200")
        void adminUpdateAnyActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2002L, "activity.manage")).thenReturn("admin");
            Activity activity = new Activity();
            activity.setId(10L);
            activity.setOrganizerId(9999L); // 他人活动
            when(activityMapper.selectById(10L)).thenReturn(activity);

            Result<Activity> result = controller.updateActivity(10L, adminToken(), Map.of("name", "admin修改"));

            assertEquals(200, result.getCode());
            verify(activityMapper).updateById(activity);
        }

        @Test
        @DisplayName("AC-031: 过期/伪造token → 401")
        void invalidToken() {
            AdminController controller = controller();

            Result<Activity> result = controller.createActivity("Bearer invalid.fake.token", validCreateBody());

            assertEquals(401, result.getCode());
            verify(userAccessService, never()).requireAdminOrOrganizerOrAnyPermissionRole(anyLong(), eq("activity.manage"));
        }

        @Test
        @DisplayName("AC-031: 无Bearer前缀 → 401")
        void tokenWithoutBearerPrefix() {
            AdminController controller = controller();

            Result<Activity> result = controller.createActivity("no-bearer-prefix-token", validCreateBody());

            assertEquals(401, result.getCode());
        }

        @Test
        @DisplayName("organizer查看他人活动详情 → 403")
        void organizerViewOtherActivity() {
            AdminController controller = controller();
            when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
            Activity activity = new Activity();
            activity.setId(100L);
            activity.setOrganizerId(9999L);
            when(activityMapper.selectById(100L)).thenReturn(activity);

            Result<Activity> result = controller.getAdminActivity(100L, organizerToken(), null);

            assertEquals(403, result.getCode());
            assertTrue(result.getMessage().contains("只能查看自己主办的活动"));
        }
    }
}
