package com.omni.ticket.service;

import com.omni.exception.BusinessException;
import com.omni.ticket.dto.ActivityArtistDto;
import com.omni.ticket.dto.ActivityDraftResponse;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Station;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.StationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivityDraftServiceTest {
    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private StationMapper stationMapper;
    @Mock
    private UserAccessService userAccessService;
    @Mock
    private ActivityArtistService activityArtistService;

    private ActivityDraftService service;

    @BeforeEach
    void setUp() {
        service = new ActivityDraftService(activityMapper, stationMapper, userAccessService, activityArtistService);
    }

    @Test
    void createDraftCreatesActivityDefaultStationAndLineupWithoutVenueProof() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
        doAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            activity.setId(301L);
            return 1;
        }).when(activityMapper).insert(any(Activity.class));
        doAnswer(invocation -> {
            Station station = invocation.getArgument(0);
            station.setId(401L);
            return 1;
        }).when(stationMapper).insert(any(Station.class));
        List<Map<String, Object>> artists = List.of(
                Map.of("artistId", 10L, "primary", true),
                Map.of("artistId", 11L, "roleName", "嘉宾")
        );

        Map<String, Object> body = new HashMap<>();
        body.put("categoryId", 5L);
        body.put("name", "万象音乐节");
        body.put("artistId", 10L);
        body.put("artists", artists);
        body.put("seatMapVisibility", "published");
        body.put("realNameRequired", true);
        body.put("ticketTransferAllowed", false);
        body.put("venueApprovalNo", "NO-1");
        body.put("venueApprovalFileUrl", "https://example.test/proof.pdf");
        body.put("venueApprovalNote", "无需写入");

        ActivityDraftResponse response = service.createDraft(2003L, body);

        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(activityCaptor.capture());
        Activity activity = activityCaptor.getValue();
        assertEquals(5L, activity.getCategoryId());
        assertEquals(10L, activity.getArtistId());
        assertEquals("万象音乐节", activity.getName());
        assertEquals("draft", activity.getPublishStatus());
        assertEquals(1, activity.getStatus());
        assertEquals(2003L, activity.getOrganizerId());
        assertEquals("published", activity.getSeatMapVisibility());
        assertEquals(Boolean.TRUE, activity.getRealNameRequired());
        assertEquals(Boolean.FALSE, activity.getTicketTransferAllowed());
        assertNull(activity.getVenueApplicationId());
        assertNull(activity.getVenueApprovalNo());
        assertNull(activity.getVenueApprovalFileUrl());
        assertNull(activity.getVenueApprovalNote());
        assertNotNull(activity.getCreateTime());
        assertNotNull(activity.getUpdateTime());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActivityArtistDto>> lineupCaptor = ArgumentCaptor.forClass(List.class);
        verify(activityArtistService).saveLineup(eq(activity.getId()), lineupCaptor.capture());
        assertEquals(2, lineupCaptor.getValue().size());
        assertEquals(10L, lineupCaptor.getValue().get(0).getArtistId());
        assertEquals(Boolean.TRUE, lineupCaptor.getValue().get(0).getPrimary());

        ArgumentCaptor<Station> stationCaptor = ArgumentCaptor.forClass(Station.class);
        verify(stationMapper).insert(stationCaptor.capture());
        Station station = stationCaptor.getValue();
        assertEquals(301L, station.getActivityId());
        // 普通活动的默认站点先占位，城市和站点名由后续站点配置版本补齐。
        assertNull(station.getTourId());
        assertNull(station.getCity());
        assertNull(station.getStationName());
        assertEquals("draft", station.getPublishStatus());
        assertEquals(1, station.getStatus());
        assertNotNull(station.getCreateTime());
        assertNotNull(station.getUpdateTime());

        assertSame(activity, response.getActivity());
        assertSame(station, response.getStation());
    }

    @Test
    void createDraftConvertsInvalidLineupToBusinessException() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
        doAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            activity.setId(301L);
            return 1;
        }).when(activityMapper).insert(any(Activity.class));
        doThrow(new IllegalArgumentException("艺人不存在或已停用"))
                .when(activityArtistService).saveLineup(eq(301L), any());

        BusinessException error = assertThrows(BusinessException.class, () -> service.createDraft(2003L, Map.of(
                "categoryId", 5L,
                "name", "万象音乐节",
                "artistId", 10L,
                "artists", List.of(Map.of("artistId", 99L))
        )));

        assertEquals(400, error.getCode());
        assertEquals("艺人不存在或已停用", error.getMessage());
    }

    @Test
    void createDraftDefaultsSeatMapVisibilityToHidden() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

        service.createDraft(2003L, Map.of(
                "categoryId", 5L,
                "name", "万象音乐节",
                "artistId", 10L
        ));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals("hidden", captor.getValue().getSeatMapVisibility());
        assertEquals(Boolean.TRUE, captor.getValue().getRealNameRequired());
    }

    @Test
    void createDraftIgnoresLegacyPerUserLimitWhenMissingOrBlank() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");
        Map<String, Object> blankLimitBody = new HashMap<>();
        blankLimitBody.put("categoryId", 5L);
        blankLimitBody.put("name", "万象音乐节");
        blankLimitBody.put("artistId", 10L);
        blankLimitBody.put("perUserLimit", "  ");

        service.createDraft(2003L, Map.of(
                "categoryId", 5L,
                "name", "万象音乐节",
                "artistId", 10L
        ));
        service.createDraft(2003L, blankLimitBody);

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    }

    @Test
    void createDraftRejectsInvalidSeatMapVisibility() {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage")).thenReturn("organizer");

        BusinessException error = assertThrows(BusinessException.class, () -> service.createDraft(2003L, Map.of(
                "categoryId", 5L,
                "name", "万象音乐节",
                "artistId", 10L,
                "seatMapVisibility", "private"
        )));

        assertEquals(400, error.getCode());
        verifyNoInteractions(activityMapper, stationMapper, activityArtistService);
    }
}
