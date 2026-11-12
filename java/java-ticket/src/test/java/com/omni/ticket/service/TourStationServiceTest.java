package com.omni.ticket.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.omni.exception.BusinessException;
import com.omni.ticket.entity.Station;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Tour;
import com.omni.ticket.dto.DeactivateActivityRequest;
import com.omni.ticket.dto.InternalUserRefResponse;
import com.omni.ticket.dto.RefundImpactResponse;
import com.omni.ticket.dto.TicketTypeSeatStockSnapshot;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.entity.VenueApplication;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.SessionSeatMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.StationMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.mapper.TourMapper;
import com.omni.ticket.service.UserAccessService;
import com.omni.ticket.mapper.VenueMapper;
import com.omni.ticket.mapper.VenueApplicationMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;

import java.util.Map;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TourStationServiceTest {
    @Mock
    private TourMapper tourMapper;
    @Mock
    private StationMapper stationMapper;
    @Mock
    private UserAccessService userAccessService;
    @Mock
    private VenueApplicationMapper venueApplicationMapper;
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private TicketTypeMapper ticketTypeMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private ActivitySeatLayoutService activitySeatLayoutService;
    @Mock
    private SessionSeatLayoutService sessionSeatLayoutService;
    @Mock
    private ActivityAdminService activityAdminService;
    @Mock
    private SessionSeatMapper sessionSeatMapper;
    @Mock
    private ActivityArtistService activityArtistService;

    private TourStationService service;

    @BeforeEach
    void setUp() {
        service = new TourStationService(tourMapper, stationMapper, userAccessService, venueApplicationMapper,
                activityMapper, sessionMapper, ticketTypeMapper, venueMapper,
                activitySeatLayoutService, sessionSeatLayoutService, activityAdminService, sessionSeatMapper,
                activityArtistService);
    }

    @Test
    void organizerCreatesTourDraftForSelf() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));

        Tour result = service.createTourDraft(2003L, Map.of("title", "巡回演唱会"));

        ArgumentCaptor<Tour> captor = ArgumentCaptor.forClass(Tour.class);
        verify(tourMapper).insert(captor.capture());
        assertEquals("巡回演唱会", captor.getValue().getTitle());
        assertEquals(2003L, captor.getValue().getOrganizerId());
        assertEquals("draft", captor.getValue().getReviewStatus());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals(captor.getValue(), result);
    }

    @Test
    void adminCreatesTourDraftForProvidedOrganizer() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2002L, "tour.manage")).thenReturn(user(2002L, "admin"));

        service.createTourDraft(2002L, Map.of("title", "平台巡演", "organizerId", 2003L));

        ArgumentCaptor<Tour> captor = ArgumentCaptor.forClass(Tour.class);
        verify(tourMapper).insert(captor.capture());
        assertEquals(2003L, captor.getValue().getOrganizerId());
    }

    @Test
    void createTourDraftCreatesStationDraftsForCities() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        doAnswer(invocation -> {
            Tour tour = invocation.getArgument(0);
            tour.setId(10L);
            return 1;
        }).when(tourMapper).insert(any(Tour.class));

        Tour result = service.createTourDraft(2003L, Map.of(
                "title", "巡回演唱会",
                "cities", List.of("北京", " 上海 ", "")
        ));

        assertEquals(10L, result.getId());
        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<Station> stations = captor.getAllValues();
        assertEquals("北京", stations.get(0).getCity());
        assertEquals("北京站", stations.get(0).getStationName());
        assertEquals(10L, stations.get(0).getTourId());
        assertEquals("draft", stations.get(0).getPublishStatus());
        assertEquals(1, stations.get(0).getStatus());
        assertNotNull(stations.get(0).getCreateTime());
        assertEquals("上海", stations.get(1).getCity());
        assertEquals("上海站", stations.get(1).getStationName());
    }

    @Test
    void createTourDraftCreatesStationDraftsFromCommaSeparatedCities() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        doAnswer(invocation -> {
            Tour tour = invocation.getArgument(0);
            tour.setId(10L);
            return 1;
        }).when(tourMapper).insert(any(Tour.class));

        service.createTourDraft(2003L, Map.of("title", "巡回演唱会", "cities", "北京, 上海, ,广州"));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<Station> stations = captor.getAllValues();
        assertEquals("北京", stations.get(0).getCity());
        assertEquals("上海", stations.get(1).getCity());
        assertEquals("广州", stations.get(2).getCity());
    }

    @Test
    void createTourDraftCreatesStationDraftsFromArrayCities() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        doAnswer(invocation -> {
            Tour tour = invocation.getArgument(0);
            tour.setId(10L);
            return 1;
        }).when(tourMapper).insert(any(Tour.class));

        service.createTourDraft(2003L, Map.of("title", "巡回演唱会", "cities", new Object[]{"北京", " 上海 ", ""}));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<Station> stations = captor.getAllValues();
        assertEquals("北京", stations.get(0).getCity());
        assertEquals("北京站", stations.get(0).getStationName());
        assertEquals("上海", stations.get(1).getCity());
        assertEquals("上海站", stations.get(1).getStationName());
    }

    @Test
    void createTourDraftCreatesStationDraftsFromCityObjectsAndKeepsStationName() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        doAnswer(invocation -> {
            Tour tour = invocation.getArgument(0);
            tour.setId(10L);
            return 1;
        }).when(tourMapper).insert(any(Tour.class));

        service.createTourDraft(2003L, Map.of(
                "title", "巡回演唱会",
                "cities", List.of(
                        Map.of("city", "北京", "stationName", "北京特别场"),
                        Map.of("city", " 上海 ", "stationName", " ")
                )
        ));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<Station> stations = captor.getAllValues();
        assertEquals("北京", stations.get(0).getCity());
        assertEquals("北京特别场", stations.get(0).getStationName());
        assertEquals("上海", stations.get(1).getCity());
        assertEquals("上海站", stations.get(1).getStationName());
    }

    @Test
    void normalUserCannotCreateTourDraft() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2004L, "tour.manage")).thenThrow(new BusinessException(403, "无权限"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createTourDraft(2004L, Map.of("title", "普通用户演出")));

        assertEquals(403, error.getCode());
    }

    @Test
    void organizerCreatesStationDraftForOwnTour() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        when(tourMapper.selectById(10L)).thenReturn(tour(10L, 2003L));

        Station result = service.createStationDraft(2003L, 10L, Map.of("city", "哈尔滨", "stationName", "哈尔滨站"));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper).insert(captor.capture());
        assertEquals(10L, captor.getValue().getTourId());
        assertEquals("哈尔滨", captor.getValue().getCity());
        assertEquals("哈尔滨站", captor.getValue().getStationName());
        assertEquals("draft", captor.getValue().getPublishStatus());
        assertEquals(captor.getValue(), result);
    }

    @Test
    void stationDraftDefaultsBlankStationNameToCityStation() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        when(tourMapper.selectById(10L)).thenReturn(tour(10L, 2003L));

        service.createStationDraft(2003L, 10L, Map.of("city", "杭州", "stationName", "  "));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper).insert(captor.capture());
        assertEquals("杭州站", captor.getValue().getStationName());
    }

    @Test
    void stationDraftStoresVenueApplicationIdWhenProvided() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        when(tourMapper.selectById(10L)).thenReturn(tour(10L, 2003L));

        service.createStationDraft(2003L, 10L, Map.of("city", "上海", "stationName", "上海站", "venueApplicationId", 88L));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper).insert(captor.capture());
        assertEquals(88L, captor.getValue().getVenueApplicationId());
    }

    @Test
    void publishSingleActivityDraftStationUpdatesExistingActivityAndCreatesSession() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "activity.manage")).thenReturn(user(2003L, "organizer"));
        Station station = activityStation(40L, 30L, 88L);
        Activity activity = activity(30L, null, 40L, "draft");
        activity.setOrganizerId(2003L);
        VenueApplication application = approvedApplication(88L, 2003L, 66L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 3, 0, 0));
        when(stationMapper.selectById(40L)).thenReturn(station);
        when(activityMapper.selectById(30L)).thenReturn(activity);
        when(venueApplicationMapper.selectById(88L)).thenReturn(application);
        when(sessionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        doAnswer(invocation -> {
            Session session = invocation.getArgument(0);
            session.setId(501L);
            return 1;
        }).when(sessionMapper).insert(any(Session.class));

        Map<String, Object> result = service.publishStation(2003L, 40L, Map.of(
                "scheduleTba", false,
                "startTime", "2026-06-01T19:30",
                "endTime", "2026-06-01T21:30"
        ));

        assertSame(activity, result.get("activity"));
        verify(activityMapper).updateById(argThat(updated -> Long.valueOf(30L).equals(updated.getId())
                && "published".equals(updated.getPublishStatus())
                && Long.valueOf(88L).equals(updated.getVenueApplicationId())
                && Boolean.TRUE.equals(updated.getRealNameRequired())));
        verify(sessionMapper).insert(argThat(session -> Long.valueOf(30L).equals(session.getActivityId())
                && Long.valueOf(66L).equals(session.getVenueId())
                && LocalDateTime.of(2026, 6, 1, 19, 30).equals(session.getStartTime())
                && LocalDateTime.of(2026, 6, 1, 21, 30).equals(session.getEndTime())));
        verify(activitySeatLayoutService).copyFromVenueApplication(2003L, 30L, 88L);
        verify(sessionSeatLayoutService).copyFromActivity(2003L, 501L, 30L);
        verify(sessionSeatLayoutService).generateSessionSeats(501L);
        verify(stationMapper).updateById(argThat(updated -> "published".equals(updated.getPublishStatus())));
    }

    @Test
    void publishSingleActivityDraftStationRejectsOtherOrganizer() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "activity.manage")).thenReturn(user(2003L, "organizer"));
        Activity activity = activity(30L, null, 40L, "draft");
        activity.setOrganizerId(9999L);
        when(stationMapper.selectById(40L)).thenReturn(activityStation(40L, 30L, 88L));
        when(activityMapper.selectById(30L)).thenReturn(activity);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.publishStation(2003L, 40L, Map.of("scheduleTba", true)));

        assertEquals(403, error.getCode());
        verify(activityMapper, never()).updateById(any());
    }

    @Test
    void publishSingleActivityStationPrefersActivitySeatCraftLayout() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "activity.manage")).thenReturn(user(2003L, "organizer"));
        Station station = activityStation(40L, 30L, 88L);
        Activity activity = activity(30L, null, 40L, "draft");
        activity.setOrganizerId(2003L);
        VenueApplication application = approvedApplication(88L, 2003L, 66L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 3, 0, 0));
        when(stationMapper.selectById(40L)).thenReturn(station);
        when(activityMapper.selectById(30L)).thenReturn(activity);
        when(venueApplicationMapper.selectById(88L)).thenReturn(application);
        when(activitySeatLayoutService.hasBlockLayout("activity", 30L)).thenReturn(true);

        service.publishStation(2003L, 40L, Map.of("scheduleTba", true));

        verify(activitySeatLayoutService).copyFromSeatCraftOwner(2003L, 30L, "activity", 30L);
        verify(activitySeatLayoutService, never()).copyFromVenueApplication(any(), any(), any());
    }

    @Test
    void stationDraftCanBeCityAnnouncedWithoutVenueApplication() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        when(tourMapper.selectById(10L)).thenReturn(tour(10L, 2003L));

        service.createStationDraft(2003L, 10L, Map.of("city", "成都", "stationName", "成都站", "announceOnly", true));

        ArgumentCaptor<Station> captor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper).insert(captor.capture());
        assertEquals("city_announced", captor.getValue().getPublishStatus());
    }

    @Test
    void organizerCannotCreateStationDraftForOthersTour() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        when(tourMapper.selectById(10L)).thenReturn(tour(10L, 9999L));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createStationDraft(2003L, 10L, Map.of("city", "哈尔滨", "stationName", "哈尔滨站")));

        assertEquals(403, error.getCode());
    }

    @Test
    void organizerListsOnlyOwnTours() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Page<Tour> page = new Page<>(1, 10);
        page.setRecords(List.of(tour(10L, 2003L)));
        when(tourMapper.selectPage(any(), any())).thenReturn(page);

        Page<Tour> result = service.listManageableTours(2003L, 1, 10);

        assertSame(page, result);
        verify(tourMapper).selectPage(any(), any());
    }

    @Test
    void listManageableToursOrdersByIdAsc() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2002L, "tour.manage")).thenReturn(user(2002L, "admin"));
        Page<Tour> page = new Page<>(1, 10);
        when(tourMapper.selectPage(any(), any())).thenReturn(page);

        service.listManageableTours(2002L, 1, 10);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tourMapper).selectPage(any(), wrapperCaptor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Tour.class);
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(Tour.class));
        String queryConditions = wrapperCaptor.getValue().getSqlSegment().toLowerCase();
        assertTrue(queryConditions.contains("order by") && queryConditions.contains("id asc"), queryConditions);
    }

    @Test
    void listManageableToursFiltersByCategoryId() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2002L, "tour.manage")).thenReturn(user(2002L, "admin"));
        Page<Tour> page = new Page<>(1, 10);
        when(tourMapper.selectPage(any(), any())).thenReturn(page);

        service.listManageableTours(2002L, 1, 10, 66L);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(tourMapper).selectPage(any(), wrapperCaptor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Tour.class);
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(Tour.class));
        String queryConditions = wrapperCaptor.getValue().getSqlSegment().toLowerCase();
        assertTrue(queryConditions.contains("category_id"), queryConditions);
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(66L));
    }

    @Test
    void organizerAnnouncesOwnTourCitiesWithoutVenueApplication() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setReviewStatus("draft");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("draft");
        when(stationMapper.selectList(any())).thenReturn(List.of(station));

        Tour result = service.announceTourCities(2003L, 10L);

        assertSame(tour, result);
        verify(stationMapper).updateById(argThat(updated -> Long.valueOf(20L).equals(updated.getId())
                && "city_announced".equals(updated.getPublishStatus())
                && Integer.valueOf(1).equals(updated.getStatus())
                && updated.getUpdateTime() != null));
        verify(tourMapper).updateById(argThat(updated -> Long.valueOf(10L).equals(updated.getId())
                && "announced".equals(updated.getReviewStatus())
                && Integer.valueOf(1).equals(updated.getStatus())
                && updated.getUpdateTime() != null));
    }

    @Test
    void organizerCannotAnnounceOtherOrganizerTour() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 9999L);
        tour.setReviewStatus("draft");
        when(tourMapper.selectById(10L)).thenReturn(tour);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.announceTourCities(2003L, 10L));

        assertEquals(403, error.getCode());
        verify(tourMapper, never()).updateById(any());
        verify(stationMapper, never()).updateById(any());
    }

    @Test
    void organizerDeletesOwnTourDraftAndStations() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setReviewStatus("draft");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("draft");
        when(stationMapper.selectList(any())).thenReturn(List.of(station));

        service.deleteTourDraft(2003L, 10L);

        verify(stationMapper).updateById(argThat(updated -> Long.valueOf(20L).equals(updated.getId())
                && Integer.valueOf(0).equals(updated.getStatus())
                && "cancelled".equals(updated.getPublishStatus())));
        verify(tourMapper).updateById(argThat(updated -> Long.valueOf(10L).equals(updated.getId())
                && Integer.valueOf(0).equals(updated.getStatus())
                && "deleted".equals(updated.getReviewStatus())));
    }

    @Test
    void organizerCannotDeleteOtherTourDraft() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 9999L);
        tour.setReviewStatus("draft");
        when(tourMapper.selectById(10L)).thenReturn(tour);

        BusinessException error = assertThrows(BusinessException.class, () -> service.deleteTourDraft(2003L, 10L));

        assertEquals(403, error.getCode());
        verify(tourMapper, never()).updateById(any());
        verify(stationMapper, never()).updateById(any());
    }

    @Test
    void organizerDeactivatesOwnTourAndRefundsPublishedActivities() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setTitle("万象巡演");
        tour.setReviewStatus("announced");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        Activity activity = activity(301L, 10L, 20L, "published");
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        Station publishedStation = station(20L, 10L, 88L);
        publishedStation.setPublishStatus("published");
        Station announcedStation = station(21L, 10L, null);
        announcedStation.setPublishStatus("city_announced");
        when(stationMapper.selectList(any())).thenReturn(List.of(publishedStation, announcedStation));
        RefundImpactResponse impact = new RefundImpactResponse();
        impact.setDeactivatedActivityCount(1);
        when(activityAdminService.deactivateActivities(any(), eq("巡演取消"))).thenReturn(impact);
        DeactivateActivityRequest request = new DeactivateActivityRequest();
        request.setUserId(2003L);
        request.setConfirmRefund(true);
        request.setReason("巡演取消");

        RefundImpactResponse result = service.deactivateTour(10L, request);

        assertSame(impact, result);
        assertEquals(10L, result.getActivityId());
        assertEquals("万象巡演", result.getActivityName());
        verify(activityAdminService).deactivateActivities(argThat(activities ->
                activities.size() == 1 && Long.valueOf(301L).equals(activities.get(0).getId())), eq("巡演取消"));
        verify(stationMapper).updateById(argThat(updated -> Long.valueOf(20L).equals(updated.getId())
                && "deactivated".equals(updated.getPublishStatus())
                && Integer.valueOf(1).equals(updated.getStatus())
                && updated.getUpdateTime() != null));
        verify(stationMapper).updateById(argThat(updated -> Long.valueOf(21L).equals(updated.getId())
                && "deactivated".equals(updated.getPublishStatus())
                && Integer.valueOf(1).equals(updated.getStatus())
                && updated.getUpdateTime() != null));
        verify(tourMapper).updateById(argThat(updated -> Long.valueOf(10L).equals(updated.getId())
                && "deactivated".equals(updated.getReviewStatus())
                && Integer.valueOf(1).equals(updated.getStatus())
                && updated.getUpdateTime() != null));
        verify(activityAdminService).writeDeactivationAudit(2003L, "organizer", "tour", 10L,
                "万象巡演", "巡演取消", impact);
    }

    @Test
    void organizerCannotDeactivateOtherOrganizerTour() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 9999L);
        tour.setReviewStatus("announced");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        DeactivateActivityRequest request = new DeactivateActivityRequest();
        request.setUserId(2003L);
        request.setConfirmRefund(true);

        BusinessException error = assertThrows(BusinessException.class, () -> service.deactivateTour(10L, request));

        assertEquals(403, error.getCode());
        verify(activityAdminService, never()).deactivateActivities(any(), any());
        verify(tourMapper, never()).updateById(any());
        verify(stationMapper, never()).updateById(any());
    }

    @Test
    void getTourDetailReturnsTourAndStations() {
        Tour tour = tour(10L, 2003L);
        Station station = new Station();
        station.setId(20L);
        station.setTourId(10L);
        station.setCity("北京");
        station.setStatus(1);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));

        Map<String, Object> detail = service.getTourDetail(10L);

        assertSame(tour, detail.get("tour"));
        assertEquals(List.of(station), detail.get("stations"));
    }

    @Test
    void getTourDetailIncludesPublishedStationPurchaseData() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, 88L);
        station.setPublishStatus("published");
        Activity activity = new Activity();
        activity.setId(301L);
        activity.setTourId(10L);
        activity.setStationId(20L);
        activity.setPublishStatus("published");
        activity.setStatus(1);
        Session session = new Session();
        session.setId(401L);
        session.setActivityId(301L);
        session.setStatus(1);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));

        Map<String, Object> detail = service.getTourDetail(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        assertEquals(1, stationDetails.size());
        assertSame(station, stationDetails.get(0).get("station"));
        assertSame(activity, stationDetails.get(0).get("activity"));
        assertEquals(List.of(session), stationDetails.get(0).get("sessions"));
    }

    @Test
    void getTourDetailMarksCityAnnouncedStationAsUnannounced() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("city_announced");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> detail = service.getTourDetail(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        Map<String, Object> item = stationDetails.get(0);
        assertEquals("unannounced", item.get("saleStatus"));
        assertEquals("未公布", item.get("saleStatusText"));
        assertEquals("none", item.get("primaryAction"));
        assertEquals(null, item.get("venueName"));
        assertEquals(null, item.get("priceMin"));
    }

    @Test
    void getTourDetailReturnsVenuePriceAndOnSaleStatusForPublishedStation() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, 88L);
        station.setPublishStatus("published");
        Activity activity = activity(301L, 10L, 20L, "published");
        Session session = session(401L, 301L, 501L);
        Venue venue = venue(501L, "万象体育馆", "上海市浦东新区");
        TicketType low = ticketType(601L, 401L, "看台", "280.00", 12);
        TicketType high = ticketType(602L, 401L, "内场", "680.00", 3);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(low, high));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of(venue));

        Map<String, Object> detail = service.getTourDetail(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        Map<String, Object> item = stationDetails.get(0);
        assertEquals("万象体育馆", item.get("venueName"));
        assertEquals("上海市浦东新区", item.get("venueAddress"));
        assertEquals(new BigDecimal("280.00"), item.get("priceMin"));
        assertEquals(new BigDecimal("680.00"), item.get("priceMax"));
        assertEquals(15, item.get("remainStock"));
        assertEquals("on_sale", item.get("saleStatus"));
        assertEquals("售票中", item.get("saleStatusText"));
        assertEquals("buy", item.get("primaryAction"));
    }

    @Test
    void getTourDetailUsesSessionSeatStockForPublishedStationRemainStock() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, 88L);
        station.setPublishStatus("published");
        Activity activity = activity(301L, 10L, 20L, "published");
        Session session = session(401L, 301L, 501L);
        TicketType normal = ticketType(601L, 401L, "鏅€氱エ", "280.00", 200);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(normal));
        when(venueMapper.selectBatchIds(any())).thenReturn(List.of());
        when(sessionSeatMapper.selectSeatStockSnapshotsBySessionId(401L))
                .thenReturn(List.of(stockSnapshot(601L, 200, 199)));

        Map<String, Object> detail = service.getTourDetail(10L);

        Map<String, Object> item = firstStationDetail(detail);
        assertEquals(199, item.get("remainStock"));
        assertEquals("on_sale", item.get("saleStatus"));
    }

    @Test
    void getTourDetailMarksRiskSuspendedStationWithoutActivityAsSuspended() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("risk_suspended");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> detail = service.getTourDetail(10L);

        Map<String, Object> item = firstStationDetail(detail);
        assertEquals("suspended", item.get("saleStatus"));
        assertEquals("暂时停止售票", item.get("saleStatusText"));
        assertEquals("none", item.get("primaryAction"));
    }

    @Test
    void getTourDetailMarksRiskSuspendedStationWithActivityAsSuspended() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("risk_suspended");
        Activity activity = activity(301L, 10L, 20L, "published");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));

        Map<String, Object> detail = service.getTourDetail(10L);

        Map<String, Object> item = firstStationDetail(detail);
        assertEquals("suspended", item.get("saleStatus"));
        assertEquals("暂时停止售票", item.get("saleStatusText"));
        assertEquals("none", item.get("primaryAction"));
    }

    @Test
    void getTourDetailMarksDraftStationAsComingSoonAnnounce() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("draft");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> detail = service.getTourDetail(10L);

        Map<String, Object> item = firstStationDetail(detail);
        assertEquals("coming_soon", item.get("saleStatus"));
        assertEquals("即将公布", item.get("saleStatusText"));
        assertEquals("none", item.get("primaryAction"));
    }

    @Test
    void getTourDetailHandlesNullMapperResults() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("draft");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(null);

        Map<String, Object> detail = service.getTourDetail(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        assertEquals(1, stationDetails.size());
        assertEquals("coming_soon", stationDetails.get(0).get("saleStatus"));
        assertEquals("即将公布", stationDetails.get(0).get("saleStatusText"));
    }

    @Test
    void getTourDetailHandlesNullSessionMapperResults() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("published");
        Activity activity = activity(301L, 10L, 20L, "published");
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(null);

        Map<String, Object> detail = service.getTourDetail(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        assertEquals(1, stationDetails.size());
        assertEquals("to_be_scheduled", stationDetails.get(0).get("saleStatus"));
        assertEquals("待定", stationDetails.get(0).get("saleStatusText"));
    }

    @Test
    void getTourDetailMarksPublishedSessionWithoutTicketTypesAsTicketTba() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("published");
        Activity activity = activity(301L, 10L, 20L, "published");
        Session session = session(401L, 301L, 501L);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(null);

        Map<String, Object> detail = service.getTourDetail(10L);

        Map<String, Object> item = firstStationDetail(detail);
        assertEquals("ticket_tba", item.get("saleStatus"));
        assertEquals("票档待公布", item.get("saleStatusText"));
    }

    @Test
    void getTourDetailHandlesNullVenueMapperResults() {
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("published");
        Activity activity = activity(301L, 10L, 20L, "published");
        Session session = session(401L, 301L, 501L);
        TicketType ticketType = ticketType(601L, 401L, "看台", "280.00", 5);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));
        when(ticketTypeMapper.selectList(any())).thenReturn(List.of(ticketType));
        when(venueMapper.selectBatchIds(any())).thenReturn(null);

        Map<String, Object> detail = service.getTourDetail(10L);

        Map<String, Object> item = firstStationDetail(detail);
        assertEquals("on_sale", item.get("saleStatus"));
        assertEquals(null, item.get("venueName"));
    }

    @Test
    void legacyConstructorHandlesMissingTicketTypeAndVenueMappers() {
        TourStationService legacyService = new TourStationService(tourMapper, stationMapper, userAccessService,
                venueApplicationMapper, activityMapper, sessionMapper,
                activitySeatLayoutService, sessionSeatLayoutService);
        Tour tour = tour(10L, 2003L);
        Station station = station(20L, 10L, null);
        station.setPublishStatus("published");
        Activity activity = activity(301L, 10L, 20L, "published");
        Session session = session(401L, 301L, 501L);
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectList(any())).thenReturn(List.of(station));
        when(activityMapper.selectList(any())).thenReturn(List.of(activity));
        when(sessionMapper.selectList(any())).thenReturn(List.of(session));

        Map<String, Object> detail = legacyService.getTourDetail(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        assertEquals(1, stationDetails.size());
        assertEquals("ticket_tba", stationDetails.get(0).get("saleStatus"));
        assertEquals("票档待公布", stationDetails.get(0).get("saleStatusText"));
        assertEquals(null, stationDetails.get(0).get("venueName"));
    }

    @Test
    void publishStationCreatesActivitySessionCopiesLayoutGeneratesStockAndMarksPublished() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setTitle("万象巡演");
        tour.setCategoryId(2L);
        tour.setArtistId(3L);
        tour.setPoster("poster.jpg");
        tour.setDescription("巡演介绍");
        Station station = station(20L, 10L, 88L);
        VenueApplication application = approvedApplication(88L, 2003L, 101L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59));
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectById(20L)).thenReturn(station);
        when(venueApplicationMapper.selectById(88L)).thenReturn(application);
        doAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            activity.setId(301L);
            return 1;
        }).when(activityMapper).insert(any(Activity.class));
        doAnswer(invocation -> {
            Session session = invocation.getArgument(0);
            session.setId(401L);
            return 1;
        }).when(sessionMapper).insert(any(Session.class));

        Map<String, Object> result = service.publishStation(2003L, 20L, Map.of(
                "startTime", "2026-06-10T20:00",
                "endTime", "2026-06-10T22:00"));

        Activity activity = (Activity) result.get("activity");
        Session session = (Session) result.get("session");
        assertEquals(301L, activity.getId());
        assertEquals(401L, session.getId());
        assertEquals("万象巡演 上海站", activity.getName());
        assertEquals("published", activity.getPublishStatus());
        assertNotNull(activity.getUpdateTime());
        assertNotNull(session.getUpdateTime());
        assertEquals("published", station.getPublishStatus());
        verify(activitySeatLayoutService).copyFromVenueApplication(2003L, 301L, 88L);
        verify(sessionSeatLayoutService).copyFromActivity(2003L, 401L, 301L);
        verify(sessionSeatLayoutService).generateSessionSeats(401L);
        verify(activityArtistService).ensurePrimaryArtist(301L, 3L);
        verify(activityMapper).updateById(activity);
        verify(stationMapper).updateById(station);
    }

    @Test
    void publishStationCanMarkScheduleTbaWithoutCreatingSession() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setTitle("万象巡演");
        tour.setCategoryId(2L);
        tour.setArtistId(3L);
        Station station = station(20L, 10L, 88L);
        VenueApplication application = approvedApplication(88L, 2003L, 101L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59));
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectById(20L)).thenReturn(station);
        when(venueApplicationMapper.selectById(88L)).thenReturn(application);
        doAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            activity.setId(301L);
            return 1;
        }).when(activityMapper).insert(any(Activity.class));

        Map<String, Object> result = service.publishStation(2003L, 20L, Map.of("scheduleTba", true));

        Activity activity = (Activity) result.get("activity");
        assertEquals("published", activity.getPublishStatus());
        assertEquals("published", station.getPublishStatus());
        assertEquals(null, result.get("session"));
        verify(sessionMapper, never()).insert(any(Session.class));
        verify(sessionSeatLayoutService, never()).copyFromActivity(any(), any(), any());
        verify(sessionSeatLayoutService, never()).generateSessionSeats(any());
    }

    @Test
    void publishTourStationPrefersStationSeatCraftLayout() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setTitle("涓囪薄宸℃紨");
        Station station = station(20L, 10L, 88L);
        VenueApplication application = approvedApplication(88L, 2003L, 101L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59));
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectById(20L)).thenReturn(station);
        when(venueApplicationMapper.selectById(88L)).thenReturn(application);
        when(activitySeatLayoutService.hasBlockLayout("station", 20L)).thenReturn(true);
        doAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            activity.setId(301L);
            return 1;
        }).when(activityMapper).insert(any(Activity.class));

        service.publishStation(2003L, 20L, Map.of("scheduleTba", true));

        verify(activitySeatLayoutService).copyFromSeatCraftOwner(2003L, 301L, "station", 20L);
        verify(activitySeatLayoutService, never()).copyFromVenueApplication(any(), any(), any());
    }

    @Test
    void publishStationReusesExistingStationActivity() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        Tour tour = tour(10L, 2003L);
        tour.setTitle("万象巡演");
        Station station = station(20L, 10L, 88L);
        Activity existing = activity(301L, 10L, 20L, "published");
        VenueApplication application = approvedApplication(88L, 2003L, 101L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 23, 59));
        when(tourMapper.selectById(10L)).thenReturn(tour);
        when(stationMapper.selectById(20L)).thenReturn(station);
        when(venueApplicationMapper.selectById(88L)).thenReturn(application);
        when(activityMapper.selectOne(any())).thenReturn(existing);
        doAnswer(invocation -> {
            Session session = invocation.getArgument(0);
            session.setId(401L);
            return 1;
        }).when(sessionMapper).insert(any(Session.class));

        Map<String, Object> result = service.publishStation(2003L, 20L, Map.of(
                "startTime", "2026-06-10T20:00",
                "endTime", "2026-06-10T22:00"));

        assertSame(existing, result.get("activity"));
        assertNotNull(existing.getUpdateTime());
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<LambdaQueryWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(activityMapper).selectOne(wrapperCaptor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Activity.class);
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(Activity.class));
        String queryConditions = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(queryConditions.contains("station_id") || queryConditions.contains("stationId"), queryConditions);
        assertTrue(queryConditions.contains("status"), queryConditions);
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(20L));
        assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(1));
        verify(activityMapper, never()).insert(any(Activity.class));
        verify(activitySeatLayoutService).copyFromVenueApplication(2003L, 301L, 88L);
        verify(sessionSeatLayoutService).copyFromActivity(2003L, 401L, 301L);
        verify(sessionSeatLayoutService).generateSessionSeats(401L);
        verify(activityMapper).updateById(existing);
    }

    @Test
    void publishStationRejectsWhenSessionOutsideVenueApplicationValidity() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2003L, "tour.manage")).thenReturn(user(2003L, "organizer"));
        when(tourMapper.selectById(10L)).thenReturn(tour(10L, 2003L));
        when(stationMapper.selectById(20L)).thenReturn(station(20L, 10L, 88L));
        when(venueApplicationMapper.selectById(88L)).thenReturn(approvedApplication(88L, 2003L, 101L,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 5, 23, 59)));

        BusinessException error = assertThrows(BusinessException.class, () -> service.publishStation(2003L, 20L, Map.of(
                "startTime", "2026-06-10T20:00",
                "endTime", "2026-06-10T22:00")));

        assertEquals("场次时间不在场馆审批文件有效期内", error.getMessage());
    }

    private InternalUserRefResponse user(Long id, String role) {
        InternalUserRefResponse user = new InternalUserRefResponse();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private Tour tour(Long id, Long organizerId) {
        Tour tour = new Tour();
        tour.setId(id);
        tour.setOrganizerId(organizerId);
        tour.setStatus(1);
        return tour;
    }

    private Station station(Long id, Long tourId, Long venueApplicationId) {
        Station station = new Station();
        station.setId(id);
        station.setTourId(tourId);
        station.setStationName("上海站");
        station.setCity("上海");
        station.setVenueApplicationId(venueApplicationId);
        station.setPublishStatus("draft");
        station.setStatus(1);
        return station;
    }

    private Station activityStation(Long id, Long activityId, Long venueApplicationId) {
        Station station = station(id, null, venueApplicationId);
        station.setActivityId(activityId);
        station.setStationName("北京站");
        station.setCity("北京");
        return station;
    }

    private Activity activity(Long id, Long tourId, Long stationId, String publishStatus) {
        Activity activity = new Activity();
        activity.setId(id);
        activity.setTourId(tourId);
        activity.setStationId(stationId);
        activity.setPublishStatus(publishStatus);
        activity.setStatus(1);
        return activity;
    }

    private Session session(Long id, Long activityId, Long venueId) {
        Session session = new Session();
        session.setId(id);
        session.setActivityId(activityId);
        session.setVenueId(venueId);
        session.setStatus(1);
        return session;
    }

    private TicketType ticketType(Long id, Long sessionId, String name, String price, Integer remainStock) {
        TicketType ticketType = new TicketType();
        ticketType.setId(id);
        ticketType.setSessionId(sessionId);
        ticketType.setName(name);
        ticketType.setPrice(new BigDecimal(price));
        ticketType.setRemainStock(remainStock);
        ticketType.setStatus(1);
        return ticketType;
    }

    private TicketTypeSeatStockSnapshot stockSnapshot(Long ticketTypeId, Integer totalStock, Integer remainStock) {
        TicketTypeSeatStockSnapshot snapshot = new TicketTypeSeatStockSnapshot();
        snapshot.setTicketTypeId(ticketTypeId);
        snapshot.setTotalStock(totalStock);
        snapshot.setRemainStock(remainStock);
        return snapshot;
    }

    private Venue venue(Long id, String name, String address) {
        Venue venue = new Venue();
        venue.setId(id);
        venue.setName(name);
        venue.setAddress(address);
        venue.setStatus(1);
        return venue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstStationDetail(Map<String, Object> detail) {
        List<Map<String, Object>> stationDetails = (List<Map<String, Object>>) detail.get("stationDetails");
        return stationDetails.get(0);
    }

    private VenueApplication approvedApplication(Long id, Long applicantId, Long venueId, LocalDateTime validFrom, LocalDateTime validTo) {
        VenueApplication application = new VenueApplication();
        application.setId(id);
        application.setApplicantId(applicantId);
        application.setVenueId(venueId);
        application.setStatus(1);
        application.setValidFrom(validFrom);
        application.setValidTo(validTo);
        return application;
    }
}
