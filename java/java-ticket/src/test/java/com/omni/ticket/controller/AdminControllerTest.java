package com.omni.ticket.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.LambdaUtils;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.DeactivateActivityRequest;
import com.omni.ticket.dto.DeactivateOrganizerRequest;
import com.omni.ticket.dto.DeleteActivityRequest;
import com.omni.ticket.dto.DeleteActivityResponse;
import com.omni.ticket.dto.RefundImpactResponse;
import com.omni.ticket.dto.ActivityBuyerNotificationRequest;
import com.omni.ticket.dto.ActivityBuyerNotificationResponse;
import com.omni.ticket.dto.ActivityArtistDto;
import com.omni.ticket.dto.ActivityDraftResponse;
import com.omni.ticket.dto.ActivityMarketingOverviewResponse;
import com.omni.ticket.dto.ActivityMarketingRuleRequest;
import com.omni.ticket.dto.ActivityRiskResolutionRequest;
import com.omni.ticket.dto.ActivityRiskResolutionReviewRequest;
import com.omni.ticket.dto.ActivityRiskResolutionResponse;
import com.omni.ticket.dto.AdminRecentOrderContextResponse;
import com.omni.ticket.dto.ArtistReviewRequest;
import com.omni.ticket.dto.ArtistRiskRequest;
import com.omni.ticket.dto.ArtistSearchResponse;
import com.omni.ticket.dto.ArtistSubmissionRequest;
import com.omni.ticket.dto.ArtistUpdateRequest;
import com.omni.ticket.dto.AssetUploadResponse;
import com.omni.ticket.dto.OrderInfoResponse;
import com.omni.ticket.dto.PrivateAssetDownload;
import com.omni.ticket.dto.PrivateAssetResponse;
import com.omni.ticket.dto.SeatLayoutTemplateCandidateResponse;
import com.omni.ticket.dto.SeatCraftBlockDtos;
import com.omni.ticket.dto.SeatCraftLayoutDtos;
import com.omni.ticket.dto.SeatTemplateResponse;
import com.omni.ticket.dto.StationConfigVersionRequest;
import com.omni.ticket.dto.StationConfigVersionResponse;
import com.omni.ticket.dto.StationConfigVersionReviewRequest;
import com.omni.ticket.dto.TicketTypeBatchUpdateRequest;
import com.omni.ticket.dto.UpdateActivityStatusRequest;
import com.omni.ticket.dto.VenueApplicationRequest;
import com.omni.ticket.dto.VenueApplicationReviewRequest;
import com.omni.ticket.dto.VenueApplicationResponse;
import com.omni.ticket.dto.VenueSeatRequest;
import com.omni.ticket.entity.Activity;
import com.omni.ticket.entity.Artist;
import com.omni.ticket.entity.Session;
import com.omni.ticket.entity.SessionSeat;
import com.omni.ticket.entity.TicketType;
import com.omni.ticket.entity.Venue;
import com.omni.ticket.entity.VenueApplication;
import com.omni.ticket.entity.VenueSeat;
import com.omni.ticket.mapper.ActivityMapper;
import com.omni.ticket.mapper.ArtistMapper;
import com.omni.ticket.mapper.SessionMapper;
import com.omni.ticket.mapper.TicketTypeMapper;
import com.omni.ticket.service.UserAccessService;
import com.omni.ticket.mapper.VenueMapper;
import com.omni.ticket.service.ActivityAdminService;
import com.omni.ticket.service.ActivityArtistService;
import com.omni.ticket.service.ActivityDraftService;
import com.omni.ticket.service.ActivityMarketingService;
import com.omni.ticket.service.ArtistAdminService;
import com.omni.ticket.service.ArtistGovernanceService;
import com.omni.ticket.service.ActivityRiskResponseService;
import com.omni.ticket.service.ActivitySeatLayoutService;
import com.omni.ticket.service.AdminSummaryService;
import com.omni.ticket.service.AdminOrderContextService;
import com.omni.ticket.service.OrderAdminQueryService;
import com.omni.ticket.service.VenueDefaultLayoutService;
import com.omni.ticket.service.SeatTemplateService;
import com.omni.ticket.service.SessionAdminService;
import com.omni.ticket.service.SessionSeatLayoutService;
import com.omni.ticket.service.SessionSeatProtectionService;
import com.omni.ticket.service.SessionSeatService;
import com.omni.ticket.service.PrivateAssetService;
import com.omni.ticket.service.SeatCraftLayoutVersionService;
import com.omni.ticket.service.StationConfigVersionService;
import com.omni.ticket.service.TicketAssetService;
import com.omni.ticket.service.TicketTypeAreaService;
import com.omni.ticket.service.TicketTypeStockRecalculationService;
import com.omni.ticket.service.TourStationService;
import com.omni.ticket.service.VenueApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.omni.ticket.entity.Tour;
import com.omni.ticket.search.ActivitySearchIndexEventPublisher;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private ActivityMapper activityMapper;
    @Mock
    private ArtistMapper artistMapper;
    @Mock
    private SessionMapper sessionMapper;
    @Mock
    private TicketTypeMapper ticketTypeMapper;
    @Mock
    private VenueMapper venueMapper;
    @Mock
    private UserAccessService userAccessService;
    @Mock
    private ActivityAdminService activityAdminService;
    @Mock
    private ActivityArtistService activityArtistService;
    @Mock
    private ArtistAdminService artistAdminService;
    @Mock
    private ArtistGovernanceService artistGovernanceService;
    @Mock
    private ActivityRiskResponseService activityRiskResponseService;
    @Mock
    private SessionAdminService sessionAdminService;
    @Mock
    private VenueApplicationService venueApplicationService;
    @Mock
    private SeatTemplateService seatTemplateService;
    @Mock
    private TicketTypeAreaService ticketTypeAreaService;
    @Mock
    private AdminSummaryService adminSummaryService;
    @Mock
    private SessionSeatService sessionSeatService;
    @Mock
    private VenueDefaultLayoutService venueDefaultLayoutService;
    @Mock
    private ActivitySeatLayoutService activitySeatLayoutService;
    @Mock
    private SessionSeatLayoutService sessionSeatLayoutService;
    @Mock
    private TourStationService tourStationService;
    @Mock
    private OrderAdminQueryService orderAdminQueryService;
    @Mock
    private SessionSeatProtectionService sessionSeatProtectionService;
    @Mock
    private TicketTypeStockRecalculationService stockRecalculationService;
    @Mock
    private TicketAssetService ticketAssetService;
    @Mock
    private PrivateAssetService privateAssetService;
    @Mock
    private SeatCraftLayoutVersionService seatCraftLayoutVersionService;
    @Mock
    private ActivityDraftService activityDraftService;
    @Mock
    private StationConfigVersionService stationConfigVersionService;
    @Mock
    private ActivityMarketingService activityMarketingService;
    @Mock
    private ActivitySearchIndexEventPublisher searchIndexEventPublisher;
    @Mock
    private AdminOrderContextService adminOrderContextService;

    private void allowActivityRole(Long userId, String role) {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "activity.manage"))
                .thenReturn(role);
    }

    private void allowActivityOrTourRole(Long userId, String role) {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "activity.manage", "tour.manage"))
                .thenReturn(role);
    }

    private void allowSessionRole(Long userId, String role) {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(
                userId, "session.manage", "activity.manage", "tour.manage"))
                .thenReturn(role);
    }

    private void allowVenueReadRole(Long userId, String role) {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(
                userId, "venue.manage", "session.manage", "activity.manage", "tour.manage"))
                .thenReturn(role);
    }

    private void allowVenueManageRole(Long userId, String role) {
        when(userAccessService.requireAdminOrAnyPermissionRole(userId, "venue.manage"))
                .thenReturn(role);
    }

    private void allowArtistManageRole(Long userId, String role) {
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "artist.manage"))
                .thenReturn(role);
    }

    @Test
    void listAdminVenuesUsesStableDisplayOrder() {
        AdminController controller = controller();
        allowVenueReadRole(2002L, "admin");
        when(venueMapper.selectList(any())).thenReturn(Collections.emptyList());

        controller.listAdminVenues(adminToken(), 9999L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Venue>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(venueMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment().trim();
        assertEquals("(status = #{ew.paramNameValuePairs.MPGENVAL1}) ORDER BY id ASC", sqlSegment);
    }

    @Test
    void searchArtistsRequiresAuthorizationToken() {
        AdminController controller = controller();
        allowActivityOrTourRole(2003L, "organizer");
        when(artistAdminService.search("周")).thenReturn(List.of(new ArtistSearchResponse()));

        Result<List<ArtistSearchResponse>> result = controller.searchArtists(organizerToken(), "周");

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        verify(artistAdminService).search("周");
    }

    @Test
    void searchArtistsRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<List<ArtistSearchResponse>> result = controller.searchArtists(null, "周");

        assertEquals(401, result.getCode());
        verify(artistAdminService, never()).search(any());
    }

    @Test
    void deleteVenuePhysicallyDeletesRecord() {
        AdminController controller = controller();
        Venue venue = new Venue();
        venue.setId(13L);
        venue.setStatus(1);
        allowVenueManageRole(2002L, "admin");
        when(venueMapper.selectById(13L)).thenReturn(venue);

        Result<Void> result = controller.deleteVenue(13L, adminToken(), 9999L);

        assertEquals(200, result.getCode());
        verify(venueMapper).deleteById(13L);
        verify(venueMapper, never()).updateById(any());
    }

    @Test
    void uploadPrivateAssetRequiresAuthorization() {
        AdminController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.4".getBytes());

        Result<PrivateAssetResponse> result = controller.uploadPrivateAsset(null, 2003L, "venue_proof", file);

        assertEquals(401, result.getCode());
        verify(privateAssetService, never()).upload(any(), any(), any());
    }

    @Test
    void uploadPrivateAssetUsesTokenOperator() {
        AdminController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.4".getBytes());
        PrivateAssetResponse response = new PrivateAssetResponse();
        response.setId(1L);
        allowActivityOrTourRole(2003L, "organizer");
        when(privateAssetService.upload(eq(2003L), eq("venue-proof"), eq(file))).thenReturn(response);

        Result<PrivateAssetResponse> result = controller.uploadPrivateAsset(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 9999L, "activity-venue-proof", file);

        assertEquals(200, result.getCode());
        assertEquals(1L, result.getData().getId());
        verify(privateAssetService).upload(2003L, "venue-proof", file);
        verify(privateAssetService, never()).upload(eq(9999L), any(), any());
    }

    @Test
    void uploadPrivateAssetRejectsUserRole() {
        AdminController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.4".getBytes());
        allowActivityOrTourRole(2004L, null);

        Result<PrivateAssetResponse> result = controller.uploadPrivateAsset(
                "Bearer " + JwtUtil.generateToken(2004L, "13900000001", "user"), 2004L, "venue_proof", file);

        assertEquals(403, result.getCode());
        verify(privateAssetService, never()).upload(any(), any(), any());
    }

    @Test
    void submitVenueApplicationUsesTokenSubjectOverBodyUserId() {
        AdminController controller = controller();
        VenueApplicationRequest request = new VenueApplicationRequest();
        request.setUserId(9999L);
        VenueApplication application = new VenueApplication();
        application.setId(10L);
        application.setApplicantId(2003L);
        when(venueApplicationService.submit(any())).thenReturn(application);

        Result<VenueApplicationResponse> result = controller.submitVenueApplication(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), request);

        ArgumentCaptor<VenueApplicationRequest> captor = ArgumentCaptor.forClass(VenueApplicationRequest.class);
        verify(venueApplicationService).submit(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals(2003L, captor.getValue().getUserId());
    }

    @Test
    void submitVenueApplicationRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<VenueApplicationResponse> result = controller.submitVenueApplication(null, new VenueApplicationRequest());

        assertEquals(401, result.getCode());
        verify(venueApplicationService, never()).submit(any());
    }

    @Test
    void listMyVenueApplicationsIgnoresQueryUserId() {
        AdminController controller = controller();
        when(venueApplicationService.listMine(2003L)).thenReturn(List.of());

        Result<List<VenueApplicationResponse>> result = controller.listMyVenueApplications(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 9999L);

        assertEquals(200, result.getCode());
        verify(venueApplicationService).listMine(2003L);
        verify(venueApplicationService, never()).listMine(9999L);
    }

    @Test
    void listVenueApplicationsUsesTokenSubject() {
        AdminController controller = controller();
        when(venueApplicationService.listAdmin(2002L, 0)).thenReturn(List.of());

        Result<List<VenueApplicationResponse>> result = controller.listVenueApplications(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 9999L, 0);

        assertEquals(200, result.getCode());
        verify(venueApplicationService).listAdmin(2002L, 0);
        verify(venueApplicationService, never()).listAdmin(eq(9999L), any());
    }

    @Test
    void reviewVenueApplicationUsesTokenSubject() {
        AdminController controller = controller();
        VenueApplicationReviewRequest request = new VenueApplicationReviewRequest();
        request.setUserId(9999L);
        request.setAction("approve");
        request.setMode("create");
        request.setReviewNote("通过");
        VenueApplication application = new VenueApplication();
        application.setId(10L);
        application.setReviewerId(2002L);
        when(venueApplicationService.approve(10L, 2002L, "create", null, "通过")).thenReturn(application);

        Result<VenueApplicationResponse> result = controller.reviewVenueApplication(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 10L, request);

        assertEquals(200, result.getCode());
        verify(venueApplicationService).approve(10L, 2002L, "create", null, "通过");
        verify(venueApplicationService, never()).approve(eq(10L), eq(9999L), any(), any(), any());
    }

    @Test
    void rejectVenueApplicationUsesTokenSubject() {
        AdminController controller = controller();
        VenueApplicationReviewRequest request = new VenueApplicationReviewRequest();
        request.setUserId(9999L);
        request.setAction("reject");
        request.setReviewNote("资料不完整");
        VenueApplication application = new VenueApplication();
        application.setId(10L);
        application.setReviewerId(2002L);
        when(venueApplicationService.reject(10L, 2002L, "资料不完整")).thenReturn(application);

        Result<VenueApplicationResponse> result = controller.reviewVenueApplication(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 10L, request);

        assertEquals(200, result.getCode());
        verify(venueApplicationService).reject(10L, 2002L, "资料不完整");
        verify(venueApplicationService, never()).reject(10L, 9999L, "资料不完整");
    }

    @Test
    void listVenueApplicationsRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<List<VenueApplicationResponse>> result = controller.listVenueApplications(null, 9999L, 0);

        assertEquals(401, result.getCode());
        verify(venueApplicationService, never()).listAdmin(any(), any());
    }

    @Test
    void reviewVenueApplicationRejectsMissingAuthorization() {
        AdminController controller = controller();
        VenueApplicationReviewRequest request = new VenueApplicationReviewRequest();
        request.setAction("reject");

        Result<VenueApplicationResponse> result = controller.reviewVenueApplication(null, 10L, request);

        assertEquals(401, result.getCode());
        verify(venueApplicationService, never()).approve(any(), any(), any(), any(), any());
        verify(venueApplicationService, never()).reject(any(), any(), any());
    }

    @Test
    void downloadPrivateAssetRequiresAuthorization() throws Exception {
        AdminController controller = controller();

        ResponseEntity<InputStreamResource> response = controller.downloadPrivateAsset(null, 1L);

        assertEquals(401, response.getStatusCodeValue());
        verify(privateAssetService, never()).prepareDownload(any(), any());
    }

    @Test
    void downloadPrivateAssetSetsDownloadHeaders() throws Exception {
        AdminController controller = controller();
        Path file = Files.createTempFile("private-asset", ".pdf");
        Files.writeString(file, "content");
        when(privateAssetService.prepareDownload(1L, 2003L))
                .thenReturn(new PrivateAssetDownload(
                        new ByteArrayInputStream("content".getBytes(StandardCharsets.UTF_8)),
                        "证明 文件.pdf", "application/pdf", 7L));

        ResponseEntity<InputStreamResource> response = controller.downloadPrivateAsset(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 1L);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("application/pdf", response.getHeaders().getContentType().toString());
        assertEquals(7L, response.getHeaders().getContentLength());
        String contentDisposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        org.junit.jupiter.api.Assertions.assertNotNull(contentDisposition);
        org.junit.jupiter.api.Assertions.assertTrue(contentDisposition.contains("attachment"));
        org.junit.jupiter.api.Assertions.assertTrue(contentDisposition.contains("filename*="));
        org.junit.jupiter.api.Assertions.assertTrue(contentDisposition.contains("UTF-8''") || contentDisposition.contains("%E8%AF%81"));
        verify(privateAssetService).prepareDownload(1L, 2003L);
    }

    @Test
    void downloadPrivateAssetReturnsNotFoundWhenFileDisappears() {
        AdminController controller = controller();
        when(privateAssetService.prepareDownload(1L, 2003L))
                .thenThrow(new BusinessException(ResultCode.NOT_FOUND, "私有资产文件不存在"));

        assertThrows(BusinessException.class, () -> controller.downloadPrivateAsset(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 1L));
        verify(privateAssetService).prepareDownload(1L, 2003L);
    }

    @Test
    void deactivateOrganizerUsesAuthorizationTokenAsOperator() {
        AdminController controller = controller();
        DeactivateOrganizerRequest request = new DeactivateOrganizerRequest();
        request.setUserId(9999L);
        request.setOrganizerId(2003L);
        request.setConfirmRefund(true);
        RefundImpactResponse response = new RefundImpactResponse();
        when(activityAdminService.deactivateOrganizer(any())).thenReturn(response);

        Result<RefundImpactResponse> result = controller.deactivateOrganizer(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), request);

        ArgumentCaptor<DeactivateOrganizerRequest> captor = ArgumentCaptor.forClass(DeactivateOrganizerRequest.class);
        verify(activityAdminService).deactivateOrganizer(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals(2002L, captor.getValue().getUserId());
    }

    @Test
    void deactivateOrganizerRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<RefundImpactResponse> result = controller.deactivateOrganizer(null, new DeactivateOrganizerRequest());

        assertEquals(401, result.getCode());
        verify(activityAdminService, never()).deactivateOrganizer(any());
    }

    @Test
    void deactivateTourDelegatesToTourStationService() {
        AdminController controller = controller();
        DeactivateActivityRequest request = new DeactivateActivityRequest();
        request.setUserId(9999L);
        request.setConfirmRefund(true);
        RefundImpactResponse response = new RefundImpactResponse();
        response.setActivityId(10L);
        when(tourStationService.deactivateTour(10L, request)).thenReturn(response);

        Result<RefundImpactResponse> result = controller.deactivateTour(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(10L, result.getData().getActivityId());
        assertEquals(2003L, request.getUserId());
        verify(tourStationService).deactivateTour(10L, request);
    }

    @Test
    void createVenueSeatRejectsEmptyRequestBody() {
        AdminController controller = controller();

        Result<?> result = controller.createVenueSeat(1L, adminToken(), null);

        assertEquals(400, result.getCode());
        assertEquals("座位参数不能为空", result.getMessage());
        verify(seatTemplateService, never()).createSeat(any());
    }

    @Test
    void createVenueUsesAuthorizationToken() {
        AdminController controller = controller();
        allowVenueManageRole(2002L, "admin");
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 9999L);
        body.put("name", "venue");

        Result<Venue> result = controller.createVenue(adminToken(), body);

        assertEquals(200, result.getCode());
        verify(userAccessService).requireAdminOrAnyPermissionRole(2002L, "venue.manage");
        verify(userAccessService, never()).requireAdminOrAnyPermissionRole(9999L, "venue.manage");
    }

    @Test
    void updateVenueUsesAuthorizationToken() {
        AdminController controller = controller();
        allowVenueManageRole(2002L, "admin");
        Venue venue = new Venue();
        venue.setId(13L);
        when(venueMapper.selectById(13L)).thenReturn(venue);
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 9999L);
        body.put("name", "updated-venue");

        Result<Venue> result = controller.updateVenue(13L, adminToken(), body);

        assertEquals(200, result.getCode());
        verify(userAccessService).requireAdminOrAnyPermissionRole(2002L, "venue.manage");
        verify(userAccessService, never()).requireAdminOrAnyPermissionRole(9999L, "venue.manage");
    }

    @Test
    void updateVenuePublishesSearchIndexUpsertsForAffectedActivities() {
        AdminController controller = controller();
        allowVenueManageRole(2002L, "admin");
        Venue venue = new Venue();
        venue.setId(13L);
        when(venueMapper.selectById(13L)).thenReturn(venue);
        Session first = new Session();
        first.setActivityId(10L);
        Session second = new Session();
        second.setActivityId(20L);
        Session duplicate = new Session();
        duplicate.setActivityId(10L);
        when(sessionMapper.selectList(any())).thenReturn(List.of(first, second, duplicate));

        Result<Venue> result = controller.updateVenue(13L, adminToken(), Map.of("city", "北京"));

        assertEquals(200, result.getCode());
        verify(searchIndexEventPublisher).publishUpsert(10L);
        verify(searchIndexEventPublisher).publishUpsert(20L);
    }

    @Test
    void createVenueAreaUsesAuthorizationToken() {
        AdminController controller = controller();
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 9999L);
        when(seatTemplateService.createArea(any())).thenReturn(new SeatTemplateResponse(null, 0));

        Result<SeatTemplateResponse> result = controller.createVenueArea(8L, adminToken(), body);

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(seatTemplateService).createArea(captor.capture());
        assertEquals(2002L, captor.getValue().get("userId"));
        assertEquals(8L, captor.getValue().get("venueId"));
    }

    @Test
    void createVenueSeatUsesAuthorizationToken() {
        AdminController controller = controller();
        VenueSeatRequest request = new VenueSeatRequest();
        request.setUserId(9999L);

        Result<?> result = controller.createVenueSeat(8L, adminToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(2002L, request.getUserId());
        assertEquals(8L, request.getVenueId());
        verify(seatTemplateService).createSeat(request);
    }

    @Test
    void updateVenueSeatUsesAuthorizationToken() {
        AdminController controller = controller();
        VenueSeatRequest request = new VenueSeatRequest();
        request.setUserId(9999L);
        VenueSeat seat = new VenueSeat();
        seat.setVenueId(8L);
        when(seatTemplateService.updateSeat(77L, request)).thenReturn(seat);

        Result<?> result = controller.updateVenueSeat(77L, adminToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(2002L, request.getUserId());
        verify(seatTemplateService).updateSeat(77L, request);
    }

    @Test
    void deleteSessionDelegatesToService() {
        AdminController controller = controller();

        Result<Void> result = controller.deleteSession(organizerToken(), 9999L, 50L);

        assertEquals(200, result.getCode());
        verify(sessionAdminService).deleteSession(2003L, 50L);
    }

    @Test
    void createSessionUsesAuthorizationToken() {
        AdminController controller = controller();
        when(sessionAdminService.createSession(any())).thenReturn(new Session());

        Result<Session> result = controller.createSession(organizerToken(), Map.of(
                "userId", 9999L,
                "activityId", 10L
        ));

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sessionAdminService).createSession(captor.capture());
        assertEquals(2003L, captor.getValue().get("userId"));
    }

    @Test
    void createSessionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<Session> result = controller.createSession(null, Map.of(
                "userId", 9999L,
                "activityId", 10L
        ));

        assertEquals(401, result.getCode());
        verify(sessionAdminService, never()).createSession(any());
    }

    @Test
    void updateSessionUsesAuthorizationToken() {
        AdminController controller = controller();
        when(sessionAdminService.updateSession(eq(50L), any())).thenReturn(new Session());

        Result<Session> result = controller.updateSession(50L, organizerToken(), Map.of("userId", 9999L));

        assertEquals(200, result.getCode());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(sessionAdminService).updateSession(eq(50L), captor.capture());
        assertEquals(2003L, captor.getValue().get("userId"));
    }

    @Test
    void updateSessionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<Session> result = controller.updateSession(50L, null, Map.of("userId", 9999L));

        assertEquals(401, result.getCode());
        verify(sessionAdminService, never()).updateSession(any(), any());
    }

    @Test
    void deleteActivityRejectsBlankReason() {
        AdminController controller = controller();
        DeleteActivityRequest request = new DeleteActivityRequest();
        request.setUserId(9999L);
        request.setReason(" ");

        Result<?> result = controller.deleteActivity(10L, organizerToken(), request);

        assertEquals(400, result.getCode());
        assertEquals(2003L, request.getUserId());
        assertEquals("删除原因不能为空", result.getMessage());
        verify(activityAdminService, never()).deleteActivity(any(), any());
    }

    @Test
    void deleteActivityDelegatesToService() {
        AdminController controller = controller();
        DeleteActivityRequest request = new DeleteActivityRequest();
        request.setUserId(9999L);
        request.setReason("演出计划取消");
        DeleteActivityResponse response = new DeleteActivityResponse();
        response.setActivityId(10L);
        when(activityAdminService.deleteActivity(10L, request)).thenReturn(response);

        Result<DeleteActivityResponse> result = controller.deleteActivity(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(10L, result.getData().getActivityId());
        assertEquals(2003L, request.getUserId());
        verify(activityAdminService).deleteActivity(10L, request);
    }

    @Test
    void createTicketTypeRejectsEmptyLayoutSectionIds() {
        AdminController controller = controller();
        allowSessionRole(2003L, "organizer");
        com.omni.ticket.entity.Session mockSession = new com.omni.ticket.entity.Session();
        mockSession.setActivityId(10L);
        mockSession.setVenueId(1L);
        when(sessionMapper.selectById(1L)).thenReturn(mockSession);
        com.omni.ticket.entity.Activity mockActivity = new com.omni.ticket.entity.Activity();
        mockActivity.setOrganizerId(2003L);
        when(activityMapper.selectById(10L)).thenReturn(mockActivity);

        Result<TicketType> result = controller.createTicketType(organizerToken(), Map.of(
                "userId", 9999L, "sessionId", 1L, "name", "VIP", "price", "500", "totalStock", "100", "layoutSectionIds", Collections.emptyList()
        ));

        assertEquals(400, result.getCode());
    }

    @Test
    void deleteTicketTypeRejectsProtectedSeats() {
        AdminController controller = controller();
        allowSessionRole(2003L, "organizer");
        TicketType ticketType = new TicketType();
        ticketType.setId(900L);
        ticketType.setSessionId(99L);
        when(ticketTypeMapper.selectById(900L)).thenReturn(ticketType);
        com.omni.ticket.entity.Session session = new com.omni.ticket.entity.Session();
        session.setId(99L);
        session.setActivityId(10L);
        when(sessionMapper.selectById(99L)).thenReturn(session);
        Activity activity = new Activity();
        activity.setOrganizerId(2003L);
        when(activityMapper.selectById(10L)).thenReturn(activity);
        when(sessionSeatProtectionService.findProtectedSeatIds(99L)).thenReturn(java.util.Set.of(7001L));
        SessionSeat protectedSeat = new SessionSeat();
        protectedSeat.setId(7001L);
        protectedSeat.setSessionId(99L);
        protectedSeat.setTicketTypeId(900L);
        when(sessionSeatService.listBySession(99L)).thenReturn(List.of(protectedSeat));

        Result<Void> result = controller.deleteTicketType(organizerToken(), 9999L, 900L);

        assertEquals(400, result.getCode());
        assertEquals("该票档已有购票订单，请先完成退款后再删除。", result.getMessage());
        verify(ticketTypeMapper, never()).deleteById(900L);
        verify(stockRecalculationService, never()).recalculateForSession(any());
    }

    @Test
    void createTourDraftDelegatesToService() {
        AdminController controller = controller();
        Tour tour = new Tour();
        tour.setId(20L);
        when(tourStationService.createTourDraft(any(), any())).thenReturn(tour);

        Result<Tour> result = controller.createTourDraft(organizerToken(), Map.of("userId", 9999L, "title", "巡演"));

        assertEquals(200, result.getCode());
        assertEquals(20L, result.getData().getId());
        verify(tourStationService).createTourDraft(eq(2003L), any());
    }

    @Test
    void listToursPassesCategoryFilterToService() {
        AdminController controller = controller();
        Page<Tour> page = new Page<>(1, 10);
        when(tourStationService.listManageableTours(2003L, 1, 10, 66L, false)).thenReturn(page);

        Result<Page<Tour>> result = controller.listTours(organizerToken(), 9999L, 1, 10, 66L);

        assertEquals(200, result.getCode());
        assertEquals(page, result.getData());
        verify(tourStationService).listManageableTours(2003L, 1, 10, 66L, false);
    }

    @Test
    void updateSessionSeatLayoutDelegatesToService() {
        AdminController controller = controller();
        SeatCraftLayoutDtos.LayoutSaveRequest request = new SeatCraftLayoutDtos.LayoutSaveRequest();
        request.setUserId(9999L);
        SeatCraftLayoutDtos.LayoutResponse layout = new SeatCraftLayoutDtos.LayoutResponse();
        layout.setName("场次 SeatCraft 座位图");
        request.setLayout(layout);
        when(sessionSeatLayoutService.updateLayout(2003L, 10L, layout)).thenReturn(layout);

        Result<SeatCraftLayoutDtos.LayoutResponse> result = controller.updateSessionSeatLayout(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(2003L, request.getUserId());
        assertEquals("场次 SeatCraft 座位图", result.getData().getName());
        verify(sessionSeatLayoutService).updateLayout(2003L, 10L, layout);
    }

    @Test
    void deactivateActivityUsesAuthorizationToken() {
        AdminController controller = controller();
        DeactivateActivityRequest request = new DeactivateActivityRequest();
        request.setUserId(9999L);
        request.setConfirmRefund(true);
        RefundImpactResponse response = new RefundImpactResponse();
        response.setActivityId(10L);
        when(activityAdminService.deactivateActivity(10L, request)).thenReturn(response);

        Result<RefundImpactResponse> result = controller.deactivateActivity(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(10L, result.getData().getActivityId());
        assertEquals(2003L, request.getUserId());
        verify(activityAdminService).deactivateActivity(10L, request);
    }

    @Test
    void notifyActivityBuyersUsesAuthorizationToken() {
        AdminController controller = controller();
        ActivityBuyerNotificationRequest request = new ActivityBuyerNotificationRequest();
        request.setUserId(9999L);
        request.setConfirmNotify(true);
        request.setContent("演出入场时间有调整，请查看订单详情。");
        ActivityBuyerNotificationResponse response = new ActivityBuyerNotificationResponse();
        response.setActivityId(10L);
        response.setNotificationCount(2);
        when(activityAdminService.notifyActivityBuyers(10L, request)).thenReturn(response);

        Result<ActivityBuyerNotificationResponse> result = controller.notifyActivityBuyers(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(10L, result.getData().getActivityId());
        assertEquals(2, result.getData().getNotificationCount());
        assertEquals(2003L, request.getUserId());
        verify(activityAdminService).notifyActivityBuyers(10L, request);
    }

    @Test
    void getActivitySeatLayoutUsesAuthorizationToken() {
        AdminController controller = controller();
        SeatCraftLayoutDtos.LayoutResponse layout = new SeatCraftLayoutDtos.LayoutResponse();
        layout.setName("activity-layout");
        when(activitySeatLayoutService.getLayout(2003L, 10L)).thenReturn(layout);

        Result<SeatCraftLayoutDtos.LayoutResponse> result = controller.getActivitySeatLayout(10L, organizerToken(), 9999L);

        assertEquals(200, result.getCode());
        assertEquals("activity-layout", result.getData().getName());
        verify(activitySeatLayoutService).getLayout(2003L, 10L);
        verify(activitySeatLayoutService, never()).getLayout(9999L, 10L);
    }

    @Test
    void createBlankActivitySeatLayoutUsesAuthorizationToken() {
        AdminController controller = controller();
        SeatCraftLayoutDtos.LayoutResponse layout = new SeatCraftLayoutDtos.LayoutResponse();
        layout.setName("blank-activity-layout");
        when(activitySeatLayoutService.createBlankLayout(2003L, 10L)).thenReturn(layout);

        Result<SeatCraftLayoutDtos.LayoutResponse> result =
                controller.createBlankActivitySeatLayout(10L, organizerToken(), Map.of("userId", 9999L));

        assertEquals(200, result.getCode());
        assertEquals("blank-activity-layout", result.getData().getName());
        verify(activitySeatLayoutService).createBlankLayout(2003L, 10L);
        verify(activitySeatLayoutService, never()).createBlankLayout(9999L, 10L);
    }

    @Test
    void updateActivitySeatLayoutUsesAuthorizationToken() {
        AdminController controller = controller();
        SeatCraftLayoutDtos.LayoutSaveRequest request = new SeatCraftLayoutDtos.LayoutSaveRequest();
        request.setUserId(9999L);
        SeatCraftLayoutDtos.LayoutResponse layout = new SeatCraftLayoutDtos.LayoutResponse();
        layout.setName("updated-activity-layout");
        request.setLayout(layout);
        when(activitySeatLayoutService.updateLayout(2003L, 10L, layout)).thenReturn(layout);

        Result<SeatCraftLayoutDtos.LayoutResponse> result = controller.updateActivitySeatLayout(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals("updated-activity-layout", result.getData().getName());
        assertEquals(2003L, request.getUserId());
        verify(activitySeatLayoutService).updateLayout(2003L, 10L, layout);
        verify(activitySeatLayoutService, never()).updateLayout(eq(9999L), eq(10L), any());
    }

    @Test
    void getSeatCraftDraftReturnsVersionedDraft() {
        AdminController controller = controller();
        SeatCraftBlockDtos.LayoutRequest layout = new SeatCraftBlockDtos.LayoutRequest();
        layout.setName("版本草稿");
        when(seatCraftLayoutVersionService.getDraft("activity", 10L)).thenReturn(layout);

        Result<SeatCraftBlockDtos.LayoutRequest> result = controller.getSeatCraftDraft(organizerToken(), "activity", 10L);

        assertEquals(200, result.getCode());
        assertEquals("版本草稿", result.getData().getName());
        verify(seatCraftLayoutVersionService).getDraft("activity", 10L);
    }

    @Test
    void getSeatCraftDraftReturnsSuccessWhenServiceReturnsNull() {
        AdminController controller = controller();
        when(seatCraftLayoutVersionService.getDraft("activity", 10L)).thenReturn(null);

        Result<SeatCraftBlockDtos.LayoutRequest> result = controller.getSeatCraftDraft(organizerToken(), "activity", 10L);

        assertEquals(200, result.getCode());
        assertNull(result.getData());
        verify(seatCraftLayoutVersionService).getDraft("activity", 10L);
    }

    @Test
    void listSeatCraftVersionsReturnsServiceList() {
        AdminController controller = controller();
        SeatCraftBlockDtos.VersionSummary summary = new SeatCraftBlockDtos.VersionSummary();
        summary.setId(100L);
        summary.setVersionNo(4);
        summary.setVersionStatus("published");
        summary.setName("正式版本");
        when(seatCraftLayoutVersionService.listVersions("activity", 10L)).thenReturn(List.of(summary));

        Result<List<SeatCraftBlockDtos.VersionSummary>> result = controller.listSeatCraftVersions(organizerToken(), "activity", 10L);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(100L, result.getData().get(0).getId());
        assertEquals(4, result.getData().get(0).getVersionNo());
        verify(seatCraftLayoutVersionService).listVersions("activity", 10L);
    }

    @Test
    void getSeatCraftDraftRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<SeatCraftBlockDtos.LayoutRequest> result = controller.getSeatCraftDraft(null, "activity", 10L);

        assertEquals(401, result.getCode());
        verify(seatCraftLayoutVersionService, never()).getDraft(any(), any());
    }

    @Test
    void listSeatCraftVersionsRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<List<SeatCraftBlockDtos.VersionSummary>> result = controller.listSeatCraftVersions(null, "activity", 10L);

        assertEquals(401, result.getCode());
        verify(seatCraftLayoutVersionService, never()).listVersions(any(), any());
    }

    @Test
    void saveSeatCraftDraftUsesTokenSubjectAndService() {
        AdminController controller = controller();
        SeatCraftBlockDtos.LayoutRequest request = new SeatCraftBlockDtos.LayoutRequest();
        request.setVersionId(9999L);
        request.setName("提交草稿");
        SeatCraftBlockDtos.LayoutRequest saved = new SeatCraftBlockDtos.LayoutRequest();
        saved.setVersionId(100L);
        saved.setName("已保存草稿");
        when(seatCraftLayoutVersionService.saveDraft("activity", 10L, request, 2003L)).thenReturn(saved);

        Result<SeatCraftBlockDtos.LayoutRequest> result = controller.saveSeatCraftDraft(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"),
                "activity",
                10L,
                request);

        assertEquals(200, result.getCode());
        assertEquals(100L, result.getData().getVersionId());
        verify(seatCraftLayoutVersionService).saveDraft("activity", 10L, request, 2003L);
        verify(seatCraftLayoutVersionService, never()).saveDraft(eq("activity"), eq(10L), eq(request), eq(9999L));
    }

    @Test
    void publishSeatCraftDraftUsesTokenSubjectAndService() {
        AdminController controller = controller();
        SeatCraftBlockDtos.LayoutRequest published = new SeatCraftBlockDtos.LayoutRequest();
        published.setVersionStatus("published");
        when(seatCraftLayoutVersionService.publishDraft("session", 20L, 2002L)).thenReturn(published);

        Result<SeatCraftBlockDtos.LayoutRequest> result = controller.publishSeatCraftDraft(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"),
                "session",
                20L);

        assertEquals(200, result.getCode());
        assertEquals("published", result.getData().getVersionStatus());
        verify(seatCraftLayoutVersionService).publishDraft("session", 20L, 2002L);
        verify(seatCraftLayoutVersionService, never()).publishDraft(eq("session"), eq(20L), eq(9999L));
    }

    @Test
    void rollbackSeatCraftVersionUsesTokenSubjectAndService() {
        AdminController controller = controller();
        SeatCraftBlockDtos.LayoutRequest draft = new SeatCraftBlockDtos.LayoutRequest();
        draft.setVersionStatus("draft");
        when(seatCraftLayoutVersionService.rollbackToDraft("activity", 10L, 77L, 2002L)).thenReturn(draft);

        Result<SeatCraftBlockDtos.LayoutRequest> result = controller.rollbackSeatCraftVersion(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"),
                "activity",
                10L,
                77L);

        assertEquals(200, result.getCode());
        assertEquals("draft", result.getData().getVersionStatus());
        verify(seatCraftLayoutVersionService).rollbackToDraft("activity", 10L, 77L, 2002L);
        verify(seatCraftLayoutVersionService, never()).rollbackToDraft(eq("activity"), eq(10L), eq(77L), eq(9999L));
    }

    @Test
    void deleteSeatCraftVersionUsesTokenSubjectAndService() {
        AdminController controller = controller();

        Result<Void> result = controller.deleteSeatCraftVersion(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"),
                "activity",
                10L,
                77L);

        assertEquals(200, result.getCode());
        verify(seatCraftLayoutVersionService).deleteVersion("activity", 10L, 77L, 2002L);
        verify(seatCraftLayoutVersionService, never()).deleteVersion(eq("activity"), eq(10L), eq(77L), eq(9999L));
    }

    @Test
    void createActivityStoresExternalVenueApprovalProof() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");

        Result<Activity> result = controller.createActivity(organizerToken(), Map.of(
                "userId", 2003L,
                "categoryId", 1L,
                "artistId", 1L,
                "name", "审批凭证演出",
                "venueApprovalNo", "BJ-WH-2026-001",
                "venueApprovalFileUrl", "https://example.com/approval.pdf",
                "venueApprovalNote", "已取得城市主管部门审批"
        ));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals("BJ-WH-2026-001", captor.getValue().getVenueApprovalNo());
        assertEquals("https://example.com/approval.pdf", captor.getValue().getVenueApprovalFileUrl());
        assertEquals("已取得城市主管部门审批", captor.getValue().getVenueApprovalNote());
    }

    @Test
    void createActivityUsesAuthorizationToken() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");

        Result<Activity> result = controller.createActivity(organizerToken(), Map.of(
                "userId", 9999L,
                "categoryId", 1L,
                "artistId", 1L,
                "name", "auth activity"
        ));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals(2003L, captor.getValue().getOrganizerId());
        verify(userAccessService).requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage");
        verify(userAccessService, never()).requireAdminOrOrganizerOrAnyPermissionRole(9999L, "activity.manage");
    }

    @Test
    void updateActivityUsesAuthorizationToken() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        when(activityMapper.selectById(10L)).thenReturn(activity);

        Result<Activity> result = controller.updateActivity(10L, organizerToken(), Map.of(
                "userId", 9999L,
                "name", "updated auth activity"
        ));

        assertEquals(200, result.getCode());
        assertEquals("updated auth activity", result.getData().getName());
        verify(userAccessService).requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage");
        verify(userAccessService, never()).requireAdminOrOrganizerOrAnyPermissionRole(9999L, "activity.manage");
    }

    @Test
    void suspendActivityForRiskUsesAuthorizationToken() {
        AdminController controller = controller();
        ActivityRiskResolutionResponse response = new ActivityRiskResolutionResponse();
        response.setActivityId(10L);
        when(activityRiskResponseService.adminSuspendActivity(10L, 2002L, "risk")).thenReturn(response);

        Result<ActivityRiskResolutionResponse> result = controller.suspendActivityForRisk(
                10L,
                adminToken(),
                Map.of("userId", 9999L, "reason", "risk"));

        assertEquals(200, result.getCode());
        verify(activityRiskResponseService).adminSuspendActivity(10L, 2002L, "risk");
        verify(activityRiskResponseService, never()).adminSuspendActivity(10L, 9999L, "risk");
    }

    @Test
    void createActivityStoresPrivateVenueApprovalProofReferenceWithoutBindingAsApplicationProof() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        when(activityMapper.insert(any(Activity.class))).thenAnswer(invocation -> {
            Activity activity = invocation.getArgument(0);
            activity.setId(100L);
            return 1;
        });

        Result<Activity> result = controller.createActivity(organizerToken(), Map.of(
                "userId", 2003L,
                "categoryId", 1L,
                "artistId", 1L,
                "name", "私有凭证演出",
                "venueApprovalFileUrl", "private-asset:9"
        ));

        assertEquals(200, result.getCode());
        assertEquals("private-asset:9", result.getData().getVenueApprovalFileUrl());
        verify(privateAssetService, never()).bindVenueProof(anyLong(), anyLong(), anyLong());
    }

    @Test
    void createActivityStoresSeatMapVisibility() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");

        Result<Activity> result = controller.createActivity(organizerToken(), Map.of(
                "userId", 2003L,
                "categoryId", 1L,
                "artistId", 1L,
                "name", "座位图可见性演出",
                "seatMapVisibility", "published"
        ));

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals("published", captor.getValue().getSeatMapVisibility());
    }

    @Test
    void createActivityStoresRealNameRequirement() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Map<String, Object> body = validCreateActivityBody();
        body.put("realNameRequired", true);

        Result<Activity> result = controller.createActivity(organizerToken(), body);

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals(Boolean.TRUE, captor.getValue().getRealNameRequired());
    }

    @Test
    void createActivityStoresTicketTransferRule() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Map<String, Object> body = validCreateActivityBody();
        body.put("ticketTransferAllowed", false);

        Result<Activity> result = controller.createActivity(organizerToken(), body);

        ArgumentCaptor<Activity> captor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(captor.capture());
        assertEquals(200, result.getCode());
        assertEquals(Boolean.FALSE, captor.getValue().getTicketTransferAllowed());
    }

    @Test
    void updateActivityStoresRealNameRequirement() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        activity.setRealNameRequired(false);
        when(activityMapper.selectById(10L)).thenReturn(activity);

        Result<Activity> result = controller.updateActivity(10L, organizerToken(), Map.of(
                "userId", 2003L,
                "realNameRequired", true
        ));

        assertEquals(200, result.getCode());
        assertEquals(Boolean.TRUE, result.getData().getRealNameRequired());
        verify(activityMapper).updateById(activity);
    }

    @Test
    void updateActivityStoresTicketTransferRule() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        activity.setTicketTransferAllowed(true);
        when(activityMapper.selectById(10L)).thenReturn(activity);

        Result<Activity> result = controller.updateActivity(10L, organizerToken(), Map.of(
                "userId", 2003L,
                "ticketTransferAllowed", false
        ));

        assertEquals(200, result.getCode());
        assertEquals(Boolean.FALSE, result.getData().getTicketTransferAllowed());
        verify(activityMapper).updateById(activity);
    }

    @Test
    void createActivityCreatesArtistFromName() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        when(artistMapper.insert(any())).thenAnswer(invocation -> {
            Artist artist = invocation.getArgument(0);
            artist.setId(88L);
            return 1;
        });

        Result<Activity> result = controller.createActivity(organizerToken(), Map.of(
                "userId", 2003L,
                "categoryId", 1L,
                "artistName", "新乐队",
                "name", "按艺人姓名创建活动"
        ));

        ArgumentCaptor<Artist> artistCaptor = ArgumentCaptor.forClass(Artist.class);
        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(artistMapper).insert(artistCaptor.capture());
        verify(activityMapper).insert(activityCaptor.capture());
        assertEquals(200, result.getCode());
        assertEquals("新乐队", artistCaptor.getValue().getName());
        assertEquals(88L, activityCaptor.getValue().getArtistId());
    }

    @Test
    void getAdminActivityReturnsArtistName() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        activity.setArtistId(88L);
        when(activityMapper.selectById(10L)).thenReturn(activity);
        Artist artist = new Artist();
        artist.setId(88L);
        artist.setName("新乐队");
        when(artistMapper.selectById(88L)).thenReturn(artist);

        Result<Activity> result = controller.getAdminActivity(10L, organizerToken(), 9999L);

        assertEquals(200, result.getCode());
        assertEquals("新乐队", result.getData().getArtistName());
    }

    @Test
    void updateActivityUpdatesArtistFromName() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        activity.setArtistId(1L);
        when(activityMapper.selectById(10L)).thenReturn(activity);
        when(artistMapper.insert(any())).thenAnswer(invocation -> {
            Artist artist = invocation.getArgument(0);
            artist.setId(89L);
            return 1;
        });

        Result<Activity> result = controller.updateActivity(10L, organizerToken(), Map.of(
                "userId", 2003L,
                "artistName", "新组合"
        ));

        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).updateById(activityCaptor.capture());
        assertEquals(200, result.getCode());
        assertEquals(89L, activityCaptor.getValue().getArtistId());
    }

    @Test
    void createActivityStoresLineupAndSyncsPrimaryArtist() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");

        Result<Activity> result = controller.createActivity(organizerToken(), Map.of(
                "userId", 2003L,
                "categoryId", 1L,
                "name", "多艺人活动",
                "artists", List.of(
                        Map.of("artistId", 1L, "isPrimary", false, "sort", 1, "visibility", "public"),
                        Map.of("artistId", 2L, "isPrimary", true, "sort", 2, "visibility", "public")
                )
        ));

        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).insert(activityCaptor.capture());
        verify(activityArtistService).saveLineup(any(), any());
        assertEquals(200, result.getCode());
        assertEquals(2L, activityCaptor.getValue().getArtistId());
    }

    @Test
    void getAdminActivityReturnsFullLineupIncludingHiddenGuest() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        when(activityMapper.selectById(10L)).thenReturn(activity);
        ActivityArtistDto visible = new ActivityArtistDto();
        visible.setArtistId(1L);
        visible.setName("周杰伦");
        visible.setVisibility("public");
        ActivityArtistDto hidden = new ActivityArtistDto();
        hidden.setArtistId(2L);
        hidden.setName("保密嘉宾");
        hidden.setVisibility("hidden");
        when(activityArtistService.listAdminLineup(10L)).thenReturn(List.of(visible, hidden));

        Result<Activity> result = controller.getAdminActivity(10L, organizerToken(), 9999L);

        assertEquals(200, result.getCode());
        assertEquals(2, result.getData().getArtists().size());
        assertEquals("周杰伦", result.getData().getArtistName());
    }

    @Test
    void listAdminActivitiesReturnsLineupSummary() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Activity> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 1);
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setName("多艺人活动");
        page.setRecords(new ArrayList<>(List.of(activity)));
        when(activityMapper.selectPage(any(), any())).thenReturn(page);
        ActivityArtistDto first = new ActivityArtistDto();
        first.setName("周杰伦");
        first.setVisibility("public");
        ActivityArtistDto second = new ActivityArtistDto();
        second.setName("五月天");
        second.setVisibility("public");
        when(activityArtistService.listAdminLineup(10L)).thenReturn(List.of(first, second));

        Result<com.baomidou.mybatisplus.extension.plugins.pagination.Page<Activity>> result =
                controller.listAdminActivities(organizerToken(), 9999L, 1, 10, null, null);

        assertEquals(200, result.getCode());
        assertEquals("周杰伦、五月天", result.getData().getRecords().get(0).getArtistName());
        assertEquals(2, result.getData().getRecords().get(0).getArtists().size());
    }

    @Test
    void listAdminActivitiesOrdersByIdAsc() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Activity> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 0);
        when(activityMapper.selectPage(any(), any())).thenReturn(page);

        controller.listAdminActivities(organizerToken(), 9999L, 1, 10, null, null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Activity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(activityMapper).selectPage(any(), captor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Activity.class);
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(Activity.class));
        String sqlSegment = captor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("ORDER BY id ASC"));
    }

    @Test
    void listAdminActivitiesFiltersByCategoryId() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<Activity> page = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10, 0);
        when(activityMapper.selectPage(any(), any())).thenReturn(page);

        controller.listAdminActivities(organizerToken(), 9999L, 1, 10, null, null, 66L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Activity>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(activityMapper).selectPage(any(), captor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Activity.class);
        LambdaUtils.installCache(TableInfoHelper.getTableInfo(Activity.class));
        String sqlSegment = captor.getValue().getSqlSegment().toLowerCase();
        assertTrue(sqlSegment.contains("category_id"), sqlSegment);
        assertTrue(captor.getValue().getParamNameValuePairs().containsValue(66L));
    }

    @Test
    void updateActivityWithLineupUsesFirstArtistWhenNoPrimary() {
        AdminController controller = controller();
        allowActivityRole(2003L, "organizer");
        Activity activity = new Activity();
        activity.setId(10L);
        activity.setOrganizerId(2003L);
        activity.setArtistId(9L);
        when(activityMapper.selectById(10L)).thenReturn(activity);

        Result<Activity> result = controller.updateActivity(10L, organizerToken(), Map.of(
                "userId", 2003L,
                "artists", List.of(
                        Map.of("artistId", 1L, "isPrimary", false, "sort", 1, "visibility", "public"),
                        Map.of("artistId", 2L, "isPrimary", false, "sort", 2, "visibility", "public")
                )
        ));

        ArgumentCaptor<Activity> activityCaptor = ArgumentCaptor.forClass(Activity.class);
        verify(activityMapper).updateById(activityCaptor.capture());
        assertEquals(200, result.getCode());
        assertEquals(1L, activityCaptor.getValue().getArtistId());
    }

    @Test
    void listVenueSeatLayoutTemplatesDelegatesToApplicationService() {
        AdminController controller = controller();
        SeatLayoutTemplateCandidateResponse candidate = new SeatLayoutTemplateCandidateResponse();
        candidate.setSourceType("legacy_venue_default");
        candidate.setSourceId(7L);
        when(venueApplicationService.listSeatLayoutTemplates(2003L, 99L)).thenReturn(List.of(candidate));

        Result<List<SeatLayoutTemplateCandidateResponse>> result = controller.listVenueSeatLayoutTemplates(99L, organizerToken(), 9999L);

        assertEquals(200, result.getCode());
        assertEquals("legacy_venue_default", result.getData().get(0).getSourceType());
        verify(venueApplicationService).listSeatLayoutTemplates(2003L, 99L);
    }

    @Test
    void uploadAssetRequiresAdminOrOrganizerAndDelegatesToService() {
        AdminController controller = controller();
        allowActivityOrTourRole(2003L, "organizer");
        MockMultipartFile file = new MockMultipartFile("file", "poster.png", "image/png", new byte[] {1, 2, 3});
        AssetUploadResponse response = new AssetUploadResponse();
        response.setBizType("activity-poster");
        response.setPublicUrl("/uploads/ticket/activity-poster/2026/05/a.png");
        when(ticketAssetService.upload(2003L, "activity-poster", file)).thenReturn(response);

        Result<AssetUploadResponse> result = controller.uploadAsset(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"),
                9999L,
                "activity-poster",
                file);

        assertEquals(200, result.getCode());
        assertEquals("/uploads/ticket/activity-poster/2026/05/a.png", result.getData().getPublicUrl());
        verify(userAccessService).requireAdminOrOrganizerOrAnyPermissionRole(2003L, "activity.manage", "tour.manage");
        verify(ticketAssetService).upload(2003L, "activity-poster", file);
    }

    @Test
    void uploadAssetRejectsMissingAuthorization() {
        AdminController controller = controller();
        MockMultipartFile file = new MockMultipartFile("file", "poster.png", "image/png", new byte[] {1, 2, 3});

        Result<AssetUploadResponse> result = controller.uploadAsset(
                null,
                9999L,
                "activity-poster",
                file);

        assertEquals(401, result.getCode());
        verify(userAccessService, never()).requireAdminOrOrganizerOrAnyPermissionRole(anyLong(), any(), any());
        verify(ticketAssetService, never()).upload(any(), any(), any());
    }

    @Test
    void submitArtistDelegatesToGovernanceService() {
        AdminController controller = controller();
        ArtistSubmissionRequest request = new ArtistSubmissionRequest();
        request.setName("新艺人");
        Artist artist = new Artist();
        artist.setId(99L);
        when(artistGovernanceService.submit(any())).thenReturn(artist);

        Result<Artist> result = controller.submitArtist(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), request);

        assertEquals(200, result.getCode());
        assertEquals(99L, result.getData().getId());
        ArgumentCaptor<ArtistSubmissionRequest> captor = ArgumentCaptor.forClass(ArtistSubmissionRequest.class);
        verify(artistGovernanceService).submit(captor.capture());
        assertEquals(2002L, captor.getValue().getUserId());
        assertEquals("新艺人", captor.getValue().getName());
    }

    @Test
    void submitArtistRejectsMissingAuthorization() {
        AdminController controller = controller();
        ArtistSubmissionRequest request = new ArtistSubmissionRequest();
        request.setName("新艺人");

        Result<Artist> result = controller.submitArtist(null, request);

        assertEquals(401, result.getCode());
        verify(artistGovernanceService, never()).submit(any());
    }

    @Test
    void getArtistRequiresAuthorization() {
        AdminController controller = controller();

        Result<Artist> result = controller.getArtist(null, 99L);

        assertEquals(401, result.getCode());
        verify(artistAdminService, never()).getById(any());
    }

    @Test
    void getArtistRequiresAdminOrOrganizerRole() {
        AdminController controller = controller();
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2004L, "artist.manage")).thenReturn(null);

        Result<Artist> result = controller.getArtist(
                "Bearer " + JwtUtil.generateToken(2004L, "13900000001", "user"), 99L);

        assertEquals(403, result.getCode());
        verify(artistAdminService, never()).getById(any());
    }

    @Test
    void getArtistDelegatesForAuthorizedOperator() {
        AdminController controller = controller();
        Artist artist = new Artist();
        artist.setId(99L);
        artist.setSubmittedBy(2003L);
        allowArtistManageRole(2003L, "organizer");
        when(artistAdminService.getById(99L)).thenReturn(artist);

        Result<Artist> result = controller.getArtist(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 99L);

        assertEquals(200, result.getCode());
        assertEquals(99L, result.getData().getId());
        verify(artistAdminService).getById(99L);
    }

    @Test
    void getArtistRejectsOrganizerReadingOthersArtist() {
        AdminController controller = controller();
        Artist artist = new Artist();
        artist.setId(99L);
        artist.setSubmittedBy(2005L);
        allowArtistManageRole(2003L, "organizer");
        when(artistAdminService.getById(99L)).thenReturn(artist);

        Result<Artist> result = controller.getArtist(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 99L);

        assertEquals(403, result.getCode());
        verify(artistAdminService).getById(99L);
    }

    @Test
    void listArtistsRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<Page<Artist>> result = controller.listArtists(null, 1, 10, null, null, null);

        assertEquals(401, result.getCode());
        verify(artistAdminService, never()).listManageable(any(), any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void listArtistsRejectsUserRole() {
        AdminController controller = controller();
        when(userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(2004L, "artist.manage")).thenReturn(null);

        Result<Page<Artist>> result = controller.listArtists(
                "Bearer " + JwtUtil.generateToken(2004L, "13900000001", "user"), 1, 10, null, null, null);

        assertEquals(403, result.getCode());
        verify(artistAdminService, never()).listManageable(any(), any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void listArtistsDelegatesForAdmin() {
        AdminController controller = controller();
        Page<Artist> page = new Page<>(1, 10);
        page.setRecords(List.of(new Artist()));
        allowArtistManageRole(2002L, "admin");
        when(artistAdminService.listManageable(2002L, "admin", 1, 10, "周", "approved", "normal")).thenReturn(page);

        Result<Page<Artist>> result = controller.listArtists(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 1, 10, "周", "approved", "normal");

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().getRecords().size());
        verify(artistAdminService).listManageable(2002L, "admin", 1, 10, "周", "approved", "normal");
    }

    @Test
    void listArtistsDelegatesForOrganizer() {
        AdminController controller = controller();
        Page<Artist> page = new Page<>(1, 10);
        page.setRecords(List.of(new Artist()));
        allowArtistManageRole(2003L, "organizer");
        when(artistAdminService.listManageable(2003L, "organizer", 1, 10, null, "pending", null)).thenReturn(page);

        Result<Page<Artist>> result = controller.listArtists(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"), 1, 10, null, "pending", null);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().getRecords().size());
        verify(artistAdminService).listManageable(2003L, "organizer", 1, 10, null, "pending", null);
    }

    @Test
    void updateArtistDelegatesToGovernanceService() {
        AdminController controller = controller();
        ArtistUpdateRequest request = new ArtistUpdateRequest();
        request.setName("更新艺人");
        Artist artist = new Artist();
        artist.setId(99L);
        artist.setName("更新艺人");
        when(artistGovernanceService.updateProfile(eq(99L), any())).thenReturn(artist);

        Result<Artist> result = controller.updateArtist(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 99L, request);

        assertEquals(200, result.getCode());
        assertEquals("更新艺人", result.getData().getName());
        ArgumentCaptor<ArtistUpdateRequest> captor = ArgumentCaptor.forClass(ArtistUpdateRequest.class);
        verify(artistGovernanceService).updateProfile(eq(99L), captor.capture());
        assertEquals(2002L, captor.getValue().getUserId());
        assertEquals("更新艺人", captor.getValue().getName());
    }

    @Test
    void updateArtistRejectsMissingAuthorization() {
        AdminController controller = controller();
        ArtistUpdateRequest request = new ArtistUpdateRequest();
        request.setName("更新艺人");

        Result<Artist> result = controller.updateArtist(null, 99L, request);

        assertEquals(401, result.getCode());
        verify(artistGovernanceService, never()).updateProfile(any(), any());
    }

    @Test
    void listPendingArtistsDelegatesToGovernanceService() {
        AdminController controller = controller();
        Artist artist = new Artist();
        artist.setId(99L);
        when(artistGovernanceService.listPending(2002L)).thenReturn(List.of(artist));

        Result<List<Artist>> result = controller.listPendingArtists(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"));

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        verify(artistGovernanceService).listPending(2002L);
    }

    @Test
    void listPendingArtistsRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<List<Artist>> result = controller.listPendingArtists(null);

        assertEquals(401, result.getCode());
        verify(artistGovernanceService, never()).listPending(any());
    }

    @Test
    void listPendingArtistsDelegatesPermissionFailureToGovernanceService() {
        AdminController controller = controller();
        when(artistGovernanceService.listPending(2003L))
                .thenThrow(new com.omni.exception.BusinessException(403, "无权限"));

        assertThrows(com.omni.exception.BusinessException.class, () -> controller.listPendingArtists(
                "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer")));

        verify(artistGovernanceService).listPending(2003L);
    }

    @Test
    void reviewArtistDelegatesToGovernanceService() {
        AdminController controller = controller();
        ArtistReviewRequest request = new ArtistReviewRequest();
        request.setAction("approve");
        Artist artist = new Artist();
        artist.setId(99L);
        when(artistGovernanceService.review(eq(99L), any())).thenReturn(artist);

        Result<Artist> result = controller.reviewArtist(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 99L, request);

        assertEquals(200, result.getCode());
        assertEquals(99L, result.getData().getId());
        ArgumentCaptor<ArtistReviewRequest> captor = ArgumentCaptor.forClass(ArtistReviewRequest.class);
        verify(artistGovernanceService).review(eq(99L), captor.capture());
        assertEquals(2002L, captor.getValue().getUserId());
        assertEquals("approve", captor.getValue().getAction());
    }

    @Test
    void reviewArtistRejectsMissingAuthorization() {
        AdminController controller = controller();
        ArtistReviewRequest request = new ArtistReviewRequest();
        request.setAction("approve");

        Result<Artist> result = controller.reviewArtist(null, 99L, request);

        assertEquals(401, result.getCode());
        verify(artistGovernanceService, never()).review(any(), any());
    }

    @Test
    void updateArtistRiskDelegatesToGovernanceService() {
        AdminController controller = controller();
        ArtistRiskRequest request = new ArtistRiskRequest();
        request.setRiskStatus("risky");
        request.setReason("风险原因");
        Artist artist = new Artist();
        artist.setId(99L);
        when(artistGovernanceService.updateRisk(eq(99L), any())).thenReturn(artist);

        Result<Artist> result = controller.updateArtistRisk(
                "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"), 99L, request);

        assertEquals(200, result.getCode());
        assertEquals(99L, result.getData().getId());
        ArgumentCaptor<ArtistRiskRequest> captor = ArgumentCaptor.forClass(ArtistRiskRequest.class);
        verify(artistGovernanceService).updateRisk(eq(99L), captor.capture());
        assertEquals(2002L, captor.getValue().getUserId());
        assertEquals("risky", captor.getValue().getRiskStatus());
        assertEquals("风险原因", captor.getValue().getReason());
    }

    @Test
    void updateArtistRiskRejectsMissingAuthorization() {
        AdminController controller = controller();
        ArtistRiskRequest request = new ArtistRiskRequest();
        request.setRiskStatus("risky");
        request.setReason("风险原因");

        Result<Artist> result = controller.updateArtistRisk(null, 99L, request);

        assertEquals(401, result.getCode());
        verify(artistGovernanceService, never()).updateRisk(any(), any());
    }

    @Test
    void createActivityDraftDelegatesToDraftService() {
        AdminController controller = controller();
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "9999");
        Activity activity = new Activity();
        activity.setId(30L);
        com.omni.ticket.entity.Station station = new com.omni.ticket.entity.Station();
        station.setId(10L);
        ActivityDraftResponse response = new ActivityDraftResponse(activity, station);
        when(activityDraftService.createDraft(eq(2003L), eq(body))).thenReturn(response);

        Result<ActivityDraftResponse> result = controller.createActivityDraft(organizerToken(), body);

        assertEquals(200, result.getCode());
        assertEquals(30L, result.getData().getActivity().getId());
        verify(activityDraftService).createDraft(2003L, body);
    }

    @Test
    void createActivityDraftRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<ActivityDraftResponse> result = controller.createActivityDraft(null, validCreateActivityBody());

        assertEquals(401, result.getCode());
        verify(activityDraftService, never()).createDraft(any(), any());
    }

    @Test
    void createStationConfigVersionUsesTokenSubjectOverBodyUserId() {
        AdminController controller = controller();
        StationConfigVersionRequest request = stationConfigRequest(9999L);
        StationConfigVersionResponse response = stationConfigResponse(100L, "draft");
        when(stationConfigVersionService.createDraft(eq(2003L), eq(10L), eq(request))).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.createStationConfigVersion(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(100L, result.getData().getId());
        verify(stationConfigVersionService).createDraft(2003L, 10L, request);
    }

    @Test
    void createStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<StationConfigVersionResponse> result = controller.createStationConfigVersion(10L, null, stationConfigRequest(2003L));

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).createDraft(any(), any(), any());
    }

    @Test
    void updateStationConfigVersionUsesTokenSubjectOverBodyUserId() {
        AdminController controller = controller();
        StationConfigVersionRequest request = stationConfigRequest(9999L);
        StationConfigVersionResponse response = stationConfigResponse(100L, "draft");
        when(stationConfigVersionService.updateDraft(eq(2003L), eq(100L), eq(request))).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.updateStationConfigVersion(100L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals("draft", result.getData().getStatus());
        verify(stationConfigVersionService).updateDraft(2003L, 100L, request);
    }

    @Test
    void updateStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<StationConfigVersionResponse> result = controller.updateStationConfigVersion(100L, null, stationConfigRequest(2003L));

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).updateDraft(any(), any(), any());
    }

    @Test
    void deleteStationConfigVersionDelegatesToService() {
        AdminController controller = controller();
        Map<String, Object> body = new HashMap<>();
        body.put("userId", "9999");

        Result<Void> result = controller.deleteStationConfigVersion(100L, organizerToken(), body);

        assertEquals(200, result.getCode());
        verify(stationConfigVersionService).deleteDraft(100L, 2003L);
    }

    @Test
    void deleteStationConfigVersionAllowsMissingBody() {
        AdminController controller = controller();

        Result<Void> result = controller.deleteStationConfigVersion(100L, organizerToken(), null);

        assertEquals(200, result.getCode());
        verify(stationConfigVersionService).deleteDraft(100L, 2003L);
    }

    @Test
    void deleteStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<Void> result = controller.deleteStationConfigVersion(100L, null, null);

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).deleteDraft(any(), any());
    }

    @Test
    void submitStationConfigVersionDelegatesToService() {
        AdminController controller = controller();
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 9999L);
        StationConfigVersionResponse response = stationConfigResponse(100L, "submitted");
        when(stationConfigVersionService.submit(100L, 2003L)).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.submitStationConfigVersion(100L, organizerToken(), body);

        assertEquals(200, result.getCode());
        assertEquals("submitted", result.getData().getStatus());
        verify(stationConfigVersionService).submit(100L, 2003L);
    }

    @Test
    void submitStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<StationConfigVersionResponse> result = controller.submitStationConfigVersion(100L, null, null);

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).submit(any(), any());
    }

    @Test
    void withdrawStationConfigVersionAllowsMissingBody() {
        AdminController controller = controller();
        StationConfigVersionResponse response = stationConfigResponse(100L, "withdrawn");
        when(stationConfigVersionService.withdraw(100L, 2003L)).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.withdrawStationConfigVersion(100L, organizerToken(), null);

        assertEquals(200, result.getCode());
        assertEquals("withdrawn", result.getData().getStatus());
        verify(stationConfigVersionService).withdraw(100L, 2003L);
    }

    @Test
    void withdrawStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<StationConfigVersionResponse> result = controller.withdrawStationConfigVersion(100L, null, null);

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).withdraw(any(), any());
    }

    @Test
    void approveStationConfigVersionUsesTokenSubjectOverBodyUserIds() {
        AdminController controller = controller();
        StationConfigVersionReviewRequest request = new StationConfigVersionReviewRequest();
        request.setUserId(9999L);
        request.setReviewerId(9999L);
        request.setReviewNote("通过");
        StationConfigVersionResponse response = stationConfigResponse(100L, "applied");
        when(stationConfigVersionService.approve(eq(2002L), eq(100L), eq(request))).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.approveStationConfigVersion(100L, adminToken(), request);

        assertEquals(200, result.getCode());
        assertEquals("applied", result.getData().getStatus());
        verify(stationConfigVersionService).approve(2002L, 100L, request);
    }

    @Test
    void approveStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<StationConfigVersionResponse> result = controller.approveStationConfigVersion(
                100L, null, new StationConfigVersionReviewRequest());

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).approve(any(), any(), any());
    }

    @Test
    void approveStationConfigVersionAllowsNullBody() {
        AdminController controller = controller();
        StationConfigVersionResponse response = stationConfigResponse(100L, "applied");
        when(stationConfigVersionService.approve(eq(2002L), eq(100L), any())).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.approveStationConfigVersion(100L, adminToken(), null);

        assertEquals(200, result.getCode());
        assertEquals("applied", result.getData().getStatus());
        verify(stationConfigVersionService).approve(2002L, 100L, null);
    }

    @Test
    void rejectStationConfigVersionUsesTokenSubjectOverBodyUserIds() {
        AdminController controller = controller();
        StationConfigVersionReviewRequest request = new StationConfigVersionReviewRequest();
        request.setUserId(9999L);
        request.setReviewerId(9999L);
        request.setReviewNote("资料不足");
        StationConfigVersionResponse response = stationConfigResponse(100L, "rejected");
        when(stationConfigVersionService.reject(eq(2002L), eq(100L), eq(request))).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.rejectStationConfigVersion(100L, adminToken(), request);

        assertEquals(200, result.getCode());
        assertEquals("rejected", result.getData().getStatus());
        verify(stationConfigVersionService).reject(2002L, 100L, request);
    }

    @Test
    void rejectStationConfigVersionAllowsNullBody() {
        AdminController controller = controller();
        StationConfigVersionResponse response = stationConfigResponse(100L, "rejected");
        when(stationConfigVersionService.reject(eq(2002L), eq(100L), any())).thenReturn(response);

        Result<StationConfigVersionResponse> result = controller.rejectStationConfigVersion(100L, adminToken(), null);

        assertEquals(200, result.getCode());
        assertEquals("rejected", result.getData().getStatus());
        verify(stationConfigVersionService).reject(2002L, 100L, null);
    }

    @Test
    void rejectStationConfigVersionRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<StationConfigVersionResponse> result = controller.rejectStationConfigVersion(
                100L, null, new StationConfigVersionReviewRequest());

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).reject(any(), any(), any());
    }

    @Test
    void listStationConfigVersionReviewsUsesTokenSubject() {
        AdminController controller = controller();
        StationConfigVersionResponse response = stationConfigResponse(100L, "submitted");
        when(stationConfigVersionService.listReviews(2002L, "submitted")).thenReturn(List.of(response));

        Result<List<StationConfigVersionResponse>> result = controller.listStationConfigVersionReviews(adminToken(), "submitted");

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        verify(stationConfigVersionService).listReviews(2002L, "submitted");
    }

    @Test
    void listStationConfigVersionReviewsRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<List<StationConfigVersionResponse>> result = controller.listStationConfigVersionReviews(null, "submitted");

        assertEquals(401, result.getCode());
        verify(stationConfigVersionService, never()).listReviews(any(), any());
    }

    @Test
    void listAdminOrdersUsesTokenSubjectInsteadOfQueryUserId() {
        AdminController controller = controller();
        List<OrderInfoResponse> orders = List.of(new OrderInfoResponse());
        when(orderAdminQueryService.listOrders(2002L, false)).thenReturn(orders);

        Result<List<OrderInfoResponse>> result = controller.listAdminOrders(adminToken(), 9999L, false);

        assertEquals(200, result.getCode());
        assertEquals(orders, result.getData());
        verify(orderAdminQueryService).listOrders(2002L, false);
        verify(orderAdminQueryService, never()).listOrders(eq(9999L), any());
    }

    @Test
    void listAdminOrdersRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<List<OrderInfoResponse>> result = controller.listAdminOrders(null, 2002L, false);

        assertEquals(401, result.getCode());
        verify(orderAdminQueryService, never()).listOrders(anyLong(), any());
    }

    @Test
    void getAdminSummaryUsesAuthorizationToken() {
        AdminController controller = controller();

        controller.getAdminSummary(adminToken(), 9999L);

        verify(adminSummaryService).getSummary(2002L);
        verify(adminSummaryService, never()).getSummary(9999L);
    }

    @Test
    void getActivityMarketingUsesAuthorizationToken() {
        AdminController controller = controller();
        ActivityMarketingOverviewResponse overview = new ActivityMarketingOverviewResponse();
        overview.setActivityId(101L);
        when(activityMarketingService.getMarketing(2003L, 101L)).thenReturn(overview);

        Result<ActivityMarketingOverviewResponse> result = controller.getActivityMarketing(101L, organizerToken());

        assertEquals(200, result.getCode());
        assertEquals(101L, result.getData().getActivityId());
        verify(activityMarketingService).getMarketing(2003L, 101L);
    }

    @Test
    void updateActivityMarketingUsesAuthorizationToken() {
        AdminController controller = controller();
        ActivityMarketingRuleRequest request = new ActivityMarketingRuleRequest();
        ActivityMarketingOverviewResponse overview = new ActivityMarketingOverviewResponse();
        overview.setActivityId(101L);
        when(activityMarketingService.saveMarketing(2003L, 101L, request)).thenReturn(overview);

        Result<ActivityMarketingOverviewResponse> result = controller.updateActivityMarketing(101L, organizerToken(), request);

        assertEquals(200, result.getCode());
        verify(activityMarketingService).saveMarketing(2003L, 101L, request);
    }

    @Test
    void listRiskCasesUsesAuthorizationToken() {
        AdminController controller = controller();

        controller.listRiskCases(adminToken(), 9999L);

        verify(activityRiskResponseService).listRiskCases(2002L);
        verify(activityRiskResponseService, never()).listRiskCases(9999L);
    }

    @Test
    void listRiskResolutionsUsesAuthorizationToken() {
        AdminController controller = controller();

        controller.listRiskResolutions(adminToken(), 9999L, "pending");

        verify(activityRiskResponseService).listResolutions(2002L, "pending");
        verify(activityRiskResponseService, never()).listResolutions(9999L, "pending");
    }

    @Test
    void submitRiskResolutionUsesAuthorizationToken() {
        AdminController controller = controller();
        ActivityRiskResolutionRequest request = new ActivityRiskResolutionRequest();
        request.setUserId(9999L);
        request.setResolutionNote("resolved");
        ActivityRiskResolutionResponse response = new ActivityRiskResolutionResponse();
        when(activityRiskResponseService.submitResolution(10L, request)).thenReturn(response);

        Result<ActivityRiskResolutionResponse> result = controller.submitRiskResolution(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(2003L, request.getUserId());
        verify(activityRiskResponseService).submitResolution(10L, request);
    }

    @Test
    void submitRiskResolutionRejectsMissingAuthorization() {
        AdminController controller = controller();
        ActivityRiskResolutionRequest request = new ActivityRiskResolutionRequest();
        request.setUserId(9999L);

        Result<ActivityRiskResolutionResponse> result = controller.submitRiskResolution(10L, null, request);

        assertEquals(401, result.getCode());
        assertEquals(9999L, request.getUserId());
        verify(activityRiskResponseService, never()).submitResolution(any(), any());
    }

    @Test
    void reviewRiskResolutionUsesAuthorizationToken() {
        AdminController controller = controller();
        ActivityRiskResolutionReviewRequest request = new ActivityRiskResolutionReviewRequest();
        request.setUserId(9999L);
        request.setAction("approve");
        ActivityRiskResolutionResponse response = new ActivityRiskResolutionResponse();
        when(activityRiskResponseService.reviewResolution(77L, request)).thenReturn(response);

        Result<ActivityRiskResolutionResponse> result = controller.reviewRiskResolution(77L, adminToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(2002L, request.getUserId());
        verify(activityRiskResponseService).reviewResolution(77L, request);
    }

    @Test
    void reviewRiskResolutionRejectsMissingAuthorization() {
        AdminController controller = controller();
        ActivityRiskResolutionReviewRequest request = new ActivityRiskResolutionReviewRequest();
        request.setUserId(9999L);

        Result<ActivityRiskResolutionResponse> result = controller.reviewRiskResolution(77L, null, request);

        assertEquals(401, result.getCode());
        assertEquals(9999L, request.getUserId());
        verify(activityRiskResponseService, never()).reviewResolution(any(), any());
    }

    @Test
    void updateActivityStatusUsesAuthorizationToken() {
        AdminController controller = controller();
        UpdateActivityStatusRequest request = new UpdateActivityStatusRequest();
        request.setUserId(9999L);
        request.setStatus(1);

        Result<Void> result = controller.updateActivityStatus(10L, organizerToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(2003L, request.getUserId());
        verify(activityAdminService).updateActivityStatus(10L, request);
    }

    @Test
    void updateActivityStatusRejectsMissingAuthorization() {
        AdminController controller = controller();
        UpdateActivityStatusRequest request = new UpdateActivityStatusRequest();
        request.setUserId(9999L);
        request.setStatus(1);

        Result<Void> result = controller.updateActivityStatus(10L, null, request);

        assertEquals(401, result.getCode());
        assertEquals(9999L, request.getUserId());
        verify(activityAdminService, never()).updateActivityStatus(any(), any());
    }

    @Test
    void batchUpdateTicketTypesUpdatesPriceThroughAtomicEndpoint() {
        AdminController controller = controller();
        allowSessionRole(2002L, "admin");
        TicketType first = ticketType(101L, 100L, "内场票", "880", 100, 60, 1);
        TicketType second = ticketType(102L, 100L, "看台票", "380", 200, 180, 1);
        Session session = session(100L, 10L);
        when(ticketTypeMapper.selectById(101L)).thenReturn(first);
        when(ticketTypeMapper.selectById(102L)).thenReturn(second);
        when(sessionMapper.selectById(100L)).thenReturn(session);
        TicketTypeBatchUpdateRequest request = new TicketTypeBatchUpdateRequest();
        request.setIds(List.of(101L, 102L));
        request.setAction("UPDATE_PRICE");
        request.setPrice(new java.math.BigDecimal("520.00"));

        Result<List<TicketType>> result = controller.batchUpdateTicketTypes(adminToken(), request);

        assertEquals(200, result.getCode());
        assertEquals(new java.math.BigDecimal("520.00"), first.getPrice());
        assertEquals(new java.math.BigDecimal("520.00"), second.getPrice());
        verify(ticketTypeMapper).updateById(first);
        verify(ticketTypeMapper).updateById(second);
        verify(searchIndexEventPublisher).publishUpsert(10L);
    }

    @Test
    void batchUpdateTicketTypesRejectsWholeStockBatchBeforeAnyWriteWhenOneTicketWouldGoBelowSold() {
        AdminController controller = controller();
        allowSessionRole(2002L, "admin");
        TicketType first = ticketType(101L, 100L, "内场票", "880", 100, 90, 1);
        TicketType second = ticketType(102L, 100L, "看台票", "380", 200, 80, 1);
        Session session = session(100L, 10L);
        when(ticketTypeMapper.selectById(101L)).thenReturn(first);
        when(ticketTypeMapper.selectById(102L)).thenReturn(second);
        when(sessionMapper.selectById(100L)).thenReturn(session);
        TicketTypeBatchUpdateRequest request = new TicketTypeBatchUpdateRequest();
        request.setIds(List.of(101L, 102L));
        request.setAction("ADJUST_STOCK");
        request.setTotalStock(100);

        Result<List<TicketType>> result = controller.batchUpdateTicketTypes(adminToken(), request);

        assertEquals(400, result.getCode());
        assertEquals("目标总库存不能小于票档“看台票”的已售数量", result.getMessage());
        assertEquals(100, first.getTotalStock());
        assertEquals(90, first.getRemainStock());
        assertEquals(200, second.getTotalStock());
        assertEquals(80, second.getRemainStock());
        verify(ticketTypeMapper, never()).updateById(any());
    }

    @Test
    void getVenueDefaultLayoutRequiresAuthorizationToken() {
        AdminController controller = controller();
        SeatCraftLayoutDtos.LayoutResponse response = new SeatCraftLayoutDtos.LayoutResponse();
        response.setName("default-layout");
        when(venueDefaultLayoutService.getLayout(8L)).thenReturn(response);

        Result<SeatCraftLayoutDtos.LayoutResponse> result = controller.getVenueDefaultLayout(8L, adminToken());

        assertEquals(200, result.getCode());
        assertEquals("default-layout", result.getData().getName());
        verify(venueDefaultLayoutService).getLayout(8L);
    }

    @Test
    void getVenueDefaultLayoutRejectsMissingAuthorization() {
        AdminController controller = controller();

        Result<SeatCraftLayoutDtos.LayoutResponse> result = controller.getVenueDefaultLayout(8L, null);

        assertEquals(401, result.getCode());
        verify(venueDefaultLayoutService, never()).getLayout(any());
    }

    @Test
    void returnsRecentOrderContextAsArrayForConsole() {
        AdminController controller = controller();
        controller.setAdminOrderContextService(adminOrderContextService);
        AdminRecentOrderContextResponse context = new AdminRecentOrderContextResponse();
        context.setUserId(7001L);
        AdminRecentOrderContextResponse.OrderSummary order = new AdminRecentOrderContextResponse.OrderSummary();
        order.setOrderNo("ORD-1");
        order.setActivityName("演唱会A");
        context.setOrders(List.of(order));
        when(adminOrderContextService.getRecentOrderContext(7001L, 2002L)).thenReturn(context);

        Result<List<AdminRecentOrderContextResponse.OrderSummary>> result =
                controller.getRecentOrderContext(7001L, adminToken());

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals("ORD-1", result.getData().get(0).getOrderNo());
    }

    private AdminController controller() {
        AdminController controller = new AdminController(activityMapper, artistMapper, sessionMapper, ticketTypeMapper, venueMapper, userAccessService, activityAdminService, sessionAdminService, venueApplicationService, seatTemplateService, ticketTypeAreaService, adminSummaryService, sessionSeatService, venueDefaultLayoutService, activitySeatLayoutService, sessionSeatLayoutService, tourStationService, orderAdminQueryService, sessionSeatProtectionService, stockRecalculationService, activityArtistService, artistAdminService, artistGovernanceService, activityRiskResponseService, ticketAssetService, privateAssetService, seatCraftLayoutVersionService, activityDraftService, stationConfigVersionService, activityMarketingService);
        controller.setSearchIndexEventPublisher(searchIndexEventPublisher);
        return controller;
    }

    private StationConfigVersionRequest stationConfigRequest(Long userId) {
        StationConfigVersionRequest request = new StationConfigVersionRequest();
        request.setUserId(userId);
        request.setChangeType("update_city");
        return request;
    }

    private StationConfigVersionResponse stationConfigResponse(Long id, String status) {
        StationConfigVersionResponse response = new StationConfigVersionResponse();
        response.setId(id);
        response.setStatus(status);
        return response;
    }

    private String adminToken() {
        return "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin");
    }

    private String organizerToken() {
        return "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer");
    }

    private Map<String, Object> validCreateActivityBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", 2003L);
        body.put("categoryId", 1L);
        body.put("artistId", 1L);
        body.put("name", "测试活动");
        return body;
    }

    private TicketType ticketType(Long id, Long sessionId, String name, String price, Integer totalStock, Integer remainStock, Integer status) {
        TicketType ticketType = new TicketType();
        ticketType.setId(id);
        ticketType.setSessionId(sessionId);
        ticketType.setName(name);
        ticketType.setPrice(new java.math.BigDecimal(price));
        ticketType.setTotalStock(totalStock);
        ticketType.setRemainStock(remainStock);
        ticketType.setStatus(status);
        return ticketType;
    }

    private Session session(Long id, Long activityId) {
        Session session = new Session();
        session.setId(id);
        session.setActivityId(activityId);
        return session;
    }
}
