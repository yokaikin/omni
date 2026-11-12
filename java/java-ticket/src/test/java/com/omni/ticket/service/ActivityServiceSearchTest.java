package com.omni.ticket.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.ActivityDetailVO;
import com.omni.ticket.dto.ActivityVO;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Category;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.ArtistMapper;
import com.omni.ticket.mapper.CategoryMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.StationMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.TourMapper;
import com.omni.ticket.mapper.VenueMapper;
import com.omni.ticket.search.ActivitySearchProvider;
import com.omni.ticket.search.ActivitySearchRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C端活动浏览 - Service层搜索与详情测试。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("活动搜索与详情 - ActivityService")
class ActivityServiceSearchTest {

    @Mock ActivityMapper activityMapper;
    @Mock CategoryMapper categoryMapper;
    @Mock ArtistMapper artistMapper;
    @Mock SessionMapper sessionMapper;
    @Mock VenueMapper venueMapper;
    @Mock TicketTypeMapper ticketTypeMapper;
    @Mock SessionSeatMapper sessionSeatMapper;
    @Mock TourMapper tourMapper;
    @Mock StationMapper stationMapper;

    @BeforeAll
    static void ensureJwtSecret() {
        if (System.getenv("JWT_SECRET") == null || System.getenv("JWT_SECRET").isBlank()) {
            System.setProperty("JWT_SECRET", "test-jwt-secret-must-be-at-least-32-bytes");
        }
    }

    @Nested
    @DisplayName("搜索链路")
    class SearchProviderTests {

        @Test
        void searchUsesInjectedElasticsearchProvider() {
            ActivitySearchProvider searchProvider = mock(ActivitySearchProvider.class);
            Page<ActivityVO> expected = new Page<>(2, 20, 0);
            when(searchProvider.search(any(ActivitySearchRequest.class))).thenReturn(expected);
            ActivityService service = service(searchProvider);

            Page<ActivityVO> result = service.searchActivities(2, 20, 1001L, "jay", "Beijing",
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100"), new BigDecimal("800"), "on_sale",
                    true, true, "price_desc");

            assertSame(expected, result);
            ArgumentCaptor<ActivitySearchRequest> requestCaptor = ArgumentCaptor.forClass(ActivitySearchRequest.class);
            verify(searchProvider).search(requestCaptor.capture());
            ActivitySearchRequest request = requestCaptor.getValue();
            assertEquals(2, request.getPage());
            assertEquals(20, request.getSize());
            assertEquals(1001L, request.getCategoryId());
            assertEquals("jay", request.getKeyword());
            assertEquals("Beijing", request.getCity());
            assertEquals("price_desc", request.getSort());
        }

        @Test
        void searchWithoutProviderFailsInsteadOfQueryingPostgres() {
            ActivityService service = service(null);

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> service.searchActivities(1, 10, null, "周杰伦", null,
                            null, null, null, null, null, null, null, null));

            assertEquals(503, exception.getCode());
            assertEquals("搜索服务暂时不可用，请稍后重试", exception.getMessage());
            verify(activityMapper, never()).selectPage(any(), any());
        }
    }

    @Nested
    @DisplayName("活动详情")
    class ActivityDetailTests {

        @Test
        @DisplayName("AB-016: 获取已发布活动详情")
        void getActivityDetailPublished() {
            Activity activity = activity(10L, "测试活动", 1001L, "published");
            when(activityMapper.selectById(10L)).thenReturn(activity);
            when(sessionMapper.selectList(any())).thenReturn(Collections.emptyList());
            when(categoryMapper.selectById(1001L)).thenReturn(category(1001L, "演唱会"));

            ActivityDetailVO detail = service().getActivityDetail(10L);

            assertNotNull(detail);
            assertNotNull(detail.getActivity());
            assertEquals(10L, detail.getActivity().getId());
            assertEquals("测试活动", detail.getActivity().getName());
            assertEquals("published", detail.getActivity().getPublishStatus());
        }

        @Test
        void getActivityDetailUsesSessionSeatStockForSeatedTicketTypes() {
            Activity activity = activity(10L, "测试活动", 1001L, "published");
            Session session = session(1L, 10L, 101L);
            TicketType ticketType = ticketType(10L, 1L, new BigDecimal("380.00"), 1);
            ticketType.setTotalStock(200);
            ticketType.setRemainStock(200);
            when(activityMapper.selectById(10L)).thenReturn(activity);
            when(sessionMapper.selectList(any())).thenReturn(List.of(session));
            when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
            when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(1L))
                    .thenReturn(List.of(stockSnapshot(10L, 200, 199)));

            ActivityDetailVO detail = service().getActivityDetail(10L);

            TicketType visibleTicket = detail.getSessions().get(0).getTicketTypes().get(0);
            assertEquals(200, visibleTicket.getTotalStock());
            assertEquals(199, visibleTicket.getRemainStock());
        }

        @Test
        @DisplayName("AB-018: 草稿活动抛出异常")
        void getActivityDetailDraftException() {
            Activity activity = activity(10L, "草稿活动", 1001L, "draft");
            when(activityMapper.selectById(10L)).thenReturn(activity);

            assertThrows(BusinessException.class, () -> service().getActivityDetail(10L));
        }

        @Test
        @DisplayName("AB-019: 下架活动抛出异常")
        void getActivityDetailDeactivatedException() {
            Activity activity = activity(10L, "下架活动", 1001L, "deactivated");
            when(activityMapper.selectById(10L)).thenReturn(activity);

            assertThrows(BusinessException.class, () -> service().getActivityDetail(10L));
        }

        @Test
        @DisplayName("AB-020: 不存在活动抛出异常")
        void getActivityDetailNotFound() {
            when(activityMapper.selectById(999999L)).thenReturn(null);

            assertThrows(BusinessException.class, () -> service().getActivityDetail(999999L));
        }
    }

    @Nested
    @DisplayName("分类列表")
    class CategoryListTests {

        @Test
        @DisplayName("AB-021: 获取分类列表")
        void listCategories() {
            when(categoryMapper.selectList(any())).thenReturn(List.of(
                    category(1001L, "演唱会"), category(1002L, "话剧")));

            List<Category> result = service().listCategories();

            assertEquals(2, result.size());
        }
    }

    private ActivityService service() {
        return service(null);
    }

    private ActivityService service(ActivitySearchProvider searchProvider) {
        return new ActivityService(activityMapper, categoryMapper, artistMapper,
                sessionMapper, venueMapper, ticketTypeMapper, null, tourMapper, stationMapper,
                sessionSeatMapper, searchProvider);
    }

    private Activity activity(Long id, String name, Long categoryId, String publishStatus) {
        Activity a = new Activity();
        a.setId(id);
        a.setName(name);
        a.setCategoryId(categoryId);
        a.setPublishStatus(publishStatus);
        a.setStatus(1);
        return a;
    }

    private Session session(Long id, Long activityId, Long venueId) {
        Session s = new Session();
        s.setId(id);
        s.setActivityId(activityId);
        s.setVenueId(venueId);
        s.setStartTime(LocalDateTime.now().plusDays(30));
        s.setStatus(1);
        return s;
    }

    private TicketType ticketType(Long id, Long sessionId, BigDecimal price, Integer status) {
        TicketType tt = new TicketType();
        tt.setId(id);
        tt.setSessionId(sessionId);
        tt.setPrice(price);
        tt.setStatus(status);
        return tt;
    }

    private Category category(Long id, String name) {
        Category c = new Category();
        c.setId(id);
        c.setName(name);
        return c;
    }

    private TicketTypeSeatStockSnapshot stockSnapshot(Long ticketTypeId, Integer totalStock, Integer remainStock) {
        TicketTypeSeatStockSnapshot snapshot = new TicketTypeSeatStockSnapshot();
        snapshot.setTicketTypeId(ticketTypeId);
        snapshot.setTotalStock(totalStock);
        snapshot.setRemainStock(remainStock);
        return snapshot;
    }
}
