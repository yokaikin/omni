package com.omni.ticket.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.common.dto.OperationAuditWriteRequest;
import com.omni.common.result.Result;
import com.omni.common.result.ResultCode;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.DeactivateActivityRequest;
import com.omni.ticket.dto.DeactivateOrganizerRequest;
import com.omni.ticket.dto.AdminSummaryResponse;
import com.omni.ticket.dto.AdminRecentOrderContextResponse;
import com.omni.ticket.dto.ActivityBuyerNotificationRequest;
import com.omni.ticket.dto.ActivityBuyerNotificationResponse;
import com.omni.ticket.dto.DeleteActivityRequest;
import com.omni.ticket.dto.DeleteActivityResponse;
import com.omni.ticket.dto.RefundImpactResponse;
import com.omni.ticket.dto.ActivityArtistDto;
import com.omni.ticket.dto.ActivityMarketingOverviewResponse;
import com.omni.ticket.dto.ActivityMarketingRuleRequest;
import com.omni.ticket.dto.ActivityRiskCaseResponse;
import com.omni.ticket.dto.ActivityRiskResolutionRequest;
import com.omni.ticket.dto.ActivityRiskResolutionResponse;
import com.omni.ticket.dto.ActivityRiskResolutionReviewRequest;
import com.omni.ticket.dto.AssetUploadResponse;
import com.omni.ticket.dto.ActivityDraftResponse;
import com.omni.ticket.dto.ArtistReviewRequest;
import com.omni.ticket.dto.ArtistRiskRequest;
import com.omni.ticket.dto.ArtistSearchResponse;
import com.omni.ticket.dto.ArtistSubmissionRequest;
import com.omni.ticket.dto.ArtistUpdateRequest;
import com.omni.ticket.dto.CheckInManualRequest;
import com.omni.ticket.dto.CheckInOverviewResponse;
import com.omni.ticket.dto.CheckInRecordResponse;
import com.omni.ticket.dto.SeatCraftBlockDtos;
import com.omni.ticket.dto.SeatLayoutTemplateCandidateResponse;
import com.omni.ticket.dto.SeatCraftLayoutDtos;
import com.omni.ticket.dto.SeatTemplateResponse;
import com.omni.ticket.dto.SeatTemplateSyncResponse;
import com.omni.ticket.dto.SessionAdminResponse;
import com.omni.ticket.dto.StationConfigReviewDiffResponse;
import com.omni.ticket.dto.StationConfigVersionDetailResponse;
import com.omni.ticket.dto.StationConfigVersionRequest;
import com.omni.ticket.dto.StationConfigVersionResponse;
import com.omni.ticket.dto.StationConfigVersionReviewRequest;
import com.omni.ticket.dto.TicketTypeBatchUpdateRequest;
import com.omni.ticket.dto.TourDraftCreateDTO;
import com.omni.ticket.dto.UpdateActivityStatusRequest;
import com.omni.ticket.dto.VenueApplicationRequest;
import com.omni.ticket.dto.VenueApplicationResponse;
import com.omni.ticket.dto.VenueApplicationReviewRequest;
import com.omni.ticket.dto.VenueSeatRequest;
import com.omni.ticket.dto.OrderInfoResponse;
import com.omni.ticket.dto.PrivateAssetDownload;
import com.omni.ticket.dto.PrivateAssetResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omni.ticket.entity.*;
import com.omni.ticket.mapper.*;
import com.omni.ticket.search.ActivitySearchIndexEventPublisher;
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
import com.omni.ticket.service.CheckInAdminQueryService;
import com.omni.ticket.service.UserAccessService;
import com.omni.ticket.service.VenueDefaultLayoutService;
import com.omni.ticket.service.SeatTemplateService;
import com.omni.ticket.service.SessionAdminService;
import com.omni.ticket.service.SessionSeatLayoutService;
import com.omni.ticket.service.SessionSeatProtectionService;
import com.omni.ticket.service.SessionSeatService;
import com.omni.ticket.service.StationConfigVersionService;
import com.omni.ticket.service.TicketTypeAreaService;
import com.omni.ticket.service.TicketTypeStockRecalculationService;
import com.omni.ticket.service.TourStationService;
import com.omni.ticket.service.TicketAssetService;
import com.omni.ticket.service.VenueApplicationService;
import com.omni.ticket.service.OrderAdminQueryService;
import com.omni.ticket.service.PrivateAssetService;
import com.omni.ticket.service.SeatCraftLayoutVersionService;
import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * B端管理接口 - 角色分级权限
 * admin（平台管理员）：全部数据增删改查
 * organizer（商户/主办方）：只能管理自己上传的活动，查看自己的订单
 */
@RestController
@RequestMapping("/api/ticket/admin")
public class AdminController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AdminController.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String SEAT_MAP_VISIBILITY_HIDDEN = "hidden";
    private static final String SEAT_MAP_VISIBILITY_PUBLISHED = "published";

    private final ActivityMapper activityMapper;
    private final ArtistMapper artistMapper;
    private final SessionMapper sessionMapper;
    private final TicketTypeMapper ticketTypeMapper;
    private final VenueMapper venueMapper;
    private final UserAccessService userAccessService;
    private final ActivityAdminService activityAdminService;
    private final SessionAdminService sessionAdminService;
    private final VenueApplicationService venueApplicationService;
    private final SeatTemplateService seatTemplateService;
    private final TicketTypeAreaService ticketTypeAreaService;
    private final AdminSummaryService adminSummaryService;
    private final SessionSeatService sessionSeatService;
    private final VenueDefaultLayoutService venueDefaultLayoutService;
    private final ActivitySeatLayoutService activitySeatLayoutService;
    private final SessionSeatLayoutService sessionSeatLayoutService;
    private final TourStationService tourStationService;
    private final OrderAdminQueryService orderAdminQueryService;
    private CheckInAdminQueryService checkInAdminQueryService;
    private final SessionSeatProtectionService sessionSeatProtectionService;
    private final TicketTypeStockRecalculationService stockRecalculationService;
    private final ActivityArtistService activityArtistService;
    private final ArtistAdminService artistAdminService;
    private final ArtistGovernanceService artistGovernanceService;
    private final ActivityRiskResponseService activityRiskResponseService;
    private final TicketAssetService ticketAssetService;
    private final PrivateAssetService privateAssetService;
    private final SeatCraftLayoutVersionService seatCraftLayoutVersionService;
    private final ActivityDraftService activityDraftService;
    private final StationConfigVersionService stationConfigVersionService;
    private final ActivityMarketingService activityMarketingService;
    private AdminOrderContextService adminOrderContextService;
    private ActivitySearchIndexEventPublisher searchIndexEventPublisher;

    public AdminController(ActivityMapper activityMapper, SessionMapper sessionMapper,
                            TicketTypeMapper ticketTypeMapper, VenueMapper venueMapper,
                            UserAccessService userAccessService,
                             ActivityAdminService activityAdminService,
                             SessionAdminService sessionAdminService,
                             VenueApplicationService venueApplicationService,
                              SeatTemplateService seatTemplateService,
                                TicketTypeAreaService ticketTypeAreaService,
                                AdminSummaryService adminSummaryService,
                                 SessionSeatService sessionSeatService,
                                 VenueDefaultLayoutService venueDefaultLayoutService) {
        this(activityMapper, null, sessionMapper, ticketTypeMapper, venueMapper, userAccessService, activityAdminService,
                sessionAdminService, venueApplicationService, seatTemplateService, ticketTypeAreaService,
                adminSummaryService, sessionSeatService, venueDefaultLayoutService, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public AdminController(ActivityMapper activityMapper, ArtistMapper artistMapper, SessionMapper sessionMapper,
                            TicketTypeMapper ticketTypeMapper, VenueMapper venueMapper,
                            UserAccessService userAccessService,
                             ActivityAdminService activityAdminService,
                             SessionAdminService sessionAdminService,
                             VenueApplicationService venueApplicationService,
                              SeatTemplateService seatTemplateService,
                                TicketTypeAreaService ticketTypeAreaService,
                                AdminSummaryService adminSummaryService,
                                  SessionSeatService sessionSeatService,
                                   VenueDefaultLayoutService venueDefaultLayoutService,
                                   ActivitySeatLayoutService activitySeatLayoutService,
                                   SessionSeatLayoutService sessionSeatLayoutService,
                                    TourStationService tourStationService,
                                    OrderAdminQueryService orderAdminQueryService,
                                     SessionSeatProtectionService sessionSeatProtectionService,
                                     TicketTypeStockRecalculationService stockRecalculationService,
                                      ActivityArtistService activityArtistService,
                                       ArtistAdminService artistAdminService,
                                        ArtistGovernanceService artistGovernanceService,
                                         ActivityRiskResponseService activityRiskResponseService,
                                         TicketAssetService ticketAssetService,
                                         PrivateAssetService privateAssetService,
                                         SeatCraftLayoutVersionService seatCraftLayoutVersionService,
                                         ActivityDraftService activityDraftService,
                                         StationConfigVersionService stationConfigVersionService,
                                         ActivityMarketingService activityMarketingService) {
        this.activityMapper = activityMapper;
        this.artistMapper = artistMapper;
        this.sessionMapper = sessionMapper;
        this.ticketTypeMapper = ticketTypeMapper;
        this.venueMapper = venueMapper;
        this.userAccessService = userAccessService;
        this.activityAdminService = activityAdminService;
        this.sessionAdminService = sessionAdminService;
        this.venueApplicationService = venueApplicationService;
        this.seatTemplateService = seatTemplateService;
        this.ticketTypeAreaService = ticketTypeAreaService;
        this.adminSummaryService = adminSummaryService;
        this.sessionSeatService = sessionSeatService;
        this.venueDefaultLayoutService = venueDefaultLayoutService;
        this.activitySeatLayoutService = activitySeatLayoutService;
        this.sessionSeatLayoutService = sessionSeatLayoutService;
        this.tourStationService = tourStationService;
        this.orderAdminQueryService = orderAdminQueryService;
        this.sessionSeatProtectionService = sessionSeatProtectionService;
        this.stockRecalculationService = stockRecalculationService;
        this.activityArtistService = activityArtistService;
        this.artistAdminService = artistAdminService;
        this.artistGovernanceService = artistGovernanceService;
        this.activityRiskResponseService = activityRiskResponseService;
        this.ticketAssetService = ticketAssetService;
        this.privateAssetService = privateAssetService;
        this.seatCraftLayoutVersionService = seatCraftLayoutVersionService;
        this.activityDraftService = activityDraftService;
        this.stationConfigVersionService = stationConfigVersionService;
        this.activityMarketingService = activityMarketingService;
    }

    @Autowired(required = false)
    public void setSearchIndexEventPublisher(ActivitySearchIndexEventPublisher searchIndexEventPublisher) {
        this.searchIndexEventPublisher = searchIndexEventPublisher;
    }

    @Autowired(required = false)
    public void setAdminOrderContextService(AdminOrderContextService adminOrderContextService) {
        this.adminOrderContextService = adminOrderContextService;
    }

    @GetMapping("/orders/user-recent-context")
    public Result<List<AdminRecentOrderContextResponse.OrderSummary>> getRecentOrderContext(
            @RequestParam Long userId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (adminOrderContextService == null) {
            return Result.fail(ResultCode.INTERNAL_ERROR, "订单画像服务未配置");
        }
        try {
            return Result.success(adminOrderContextService.getRecentOrderContext(userId, operatorId).getOrders());
        } catch (BusinessException e) {
            return Result.fail(e.getCode(), e.getMessage());
        }
    }

    @Autowired
    public AdminController(ActivityMapper activityMapper, ArtistMapper artistMapper, SessionMapper sessionMapper,
                            TicketTypeMapper ticketTypeMapper, VenueMapper venueMapper,
                            UserAccessService userAccessService,
                             ActivityAdminService activityAdminService,
                             SessionAdminService sessionAdminService,
                             VenueApplicationService venueApplicationService,
                              SeatTemplateService seatTemplateService,
                                TicketTypeAreaService ticketTypeAreaService,
                                AdminSummaryService adminSummaryService,
                                  SessionSeatService sessionSeatService,
                                   VenueDefaultLayoutService venueDefaultLayoutService,
                                   ActivitySeatLayoutService activitySeatLayoutService,
                                   SessionSeatLayoutService sessionSeatLayoutService,
                                    TourStationService tourStationService,
                                    OrderAdminQueryService orderAdminQueryService,
                                    CheckInAdminQueryService checkInAdminQueryService,
                                     SessionSeatProtectionService sessionSeatProtectionService,
                                     TicketTypeStockRecalculationService stockRecalculationService,
                                      ActivityArtistService activityArtistService,
                                       ArtistAdminService artistAdminService,
                                        ArtistGovernanceService artistGovernanceService,
                                         ActivityRiskResponseService activityRiskResponseService,
                                         TicketAssetService ticketAssetService,
                                         PrivateAssetService privateAssetService,
                                         SeatCraftLayoutVersionService seatCraftLayoutVersionService,
                                         ActivityDraftService activityDraftService,
                                         StationConfigVersionService stationConfigVersionService,
                                         ActivityMarketingService activityMarketingService) {
        this(activityMapper, artistMapper, sessionMapper, ticketTypeMapper, venueMapper, userAccessService,
                activityAdminService, sessionAdminService, venueApplicationService, seatTemplateService,
                ticketTypeAreaService, adminSummaryService, sessionSeatService, venueDefaultLayoutService,
                activitySeatLayoutService, sessionSeatLayoutService, tourStationService, orderAdminQueryService,
                sessionSeatProtectionService, stockRecalculationService, activityArtistService, artistAdminService,
                artistGovernanceService, activityRiskResponseService, ticketAssetService, privateAssetService,
                seatCraftLayoutVersionService, activityDraftService, stationConfigVersionService,
                activityMarketingService);
        this.checkInAdminQueryService = checkInAdminQueryService;
    }

    @GetMapping("/seatcraft/{ownerType}/{ownerId}/draft")
    public Result<SeatCraftBlockDtos.LayoutRequest> getSeatCraftDraft(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                       @PathVariable String ownerType,
                                                                       @PathVariable Long ownerId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(seatCraftLayoutVersionService.getDraft(ownerType, ownerId));
    }

    @GetMapping("/seatcraft/{ownerType}/{ownerId}/versions")
    public Result<List<SeatCraftBlockDtos.VersionSummary>> listSeatCraftVersions(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                                                 @PathVariable String ownerType,
                                                                                 @PathVariable Long ownerId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(seatCraftLayoutVersionService.listVersions(ownerType, ownerId));
    }

    @PutMapping("/seatcraft/{ownerType}/{ownerId}/draft")
    public Result<SeatCraftBlockDtos.LayoutRequest> saveSeatCraftDraft(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ownerType,
            @PathVariable Long ownerId,
            @RequestBody SeatCraftBlockDtos.LayoutRequest layout) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(seatCraftLayoutVersionService.saveDraft(ownerType, ownerId, layout, operatorId));
    }

    @PostMapping("/seatcraft/{ownerType}/{ownerId}/publish")
    public Result<SeatCraftBlockDtos.LayoutRequest> publishSeatCraftDraft(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ownerType,
            @PathVariable Long ownerId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(seatCraftLayoutVersionService.publishDraft(ownerType, ownerId, operatorId));
    }

    @PostMapping("/seatcraft/{ownerType}/{ownerId}/versions/{versionId}/rollback")
    public Result<SeatCraftBlockDtos.LayoutRequest> rollbackSeatCraftVersion(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ownerType,
            @PathVariable Long ownerId,
            @PathVariable Long versionId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(seatCraftLayoutVersionService.rollbackToDraft(ownerType, ownerId, versionId, operatorId));
    }

    @DeleteMapping("/seatcraft/{ownerType}/{ownerId}/versions/{versionId}")
    public Result<Void> deleteSeatCraftVersion(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ownerType,
            @PathVariable Long ownerId,
            @PathVariable Long versionId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        seatCraftLayoutVersionService.deleteVersion(ownerType, ownerId, versionId, operatorId);
        return Result.success(null);
    }

    @PostMapping("/assets")
    public Result<AssetUploadResponse> uploadAsset(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam(required = false) Long userId,
                                                   @RequestParam String bizType,
                                                   @RequestParam MultipartFile file) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        checkActivityOrTourRole(operatorId);
        return Result.success(ticketAssetService.upload(operatorId, bizType, file));
    }

    @PostMapping("/private-assets")
    public Result<PrivateAssetResponse> uploadPrivateAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId,
            @RequestParam String bizType,
            @RequestParam MultipartFile file) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkActivityOrTourRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        // userId 仅兼容旧前端参数，实际操作身份以 token subject 为准。
        return Result.success(privateAssetService.upload(operatorId, normalizePrivateAssetBizType(bizType), file));
    }

    @GetMapping("/private-assets/{id}/download")
    public ResponseEntity<InputStreamResource> downloadPrivateAsset(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return ResponseEntity.status(401).build();
        }
        PrivateAssetDownload download = privateAssetService.prepareDownload(id, operatorId);
        String contentType = StringUtils.hasText(download.getContentType())
                ? download.getContentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(download.getOriginalFilename(), java.nio.charset.StandardCharsets.UTF_8)
                .build();
        InputStream inputStream = openPrivateAssetStream(download);
        if (inputStream == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .contentLength(download.getFileSize())
                .body(new InputStreamResource(inputStream));
    }

    private InputStream openPrivateAssetStream(PrivateAssetDownload download) {
        try {
            return Files.newInputStream(download.getPath());
        } catch (IOException e) {
            return null;
        }
    }

    @GetMapping("/artists/search")
    public Result<List<ArtistSearchResponse>> searchArtists(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkActivityOrTourRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(artistAdminService.search(keyword));
    }

    @GetMapping("/artists")
    public Result<Page<Artist>> listArtists(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String reviewStatus,
                                            @RequestParam(required = false) String riskStatus) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkArtistManageRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(artistAdminService.listManageable(operatorId, role, page, size, keyword, reviewStatus, riskStatus));
    }

    @GetMapping("/artists/{id}")
    public Result<Artist> getArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @PathVariable Long id) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkArtistManageRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        Artist artist = artistAdminService.getById(id);
        if (artist == null) return Result.fail(404, "艺人不存在");
        if ("organizer".equals(role) && !operatorId.equals(artist.getSubmittedBy())) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        return Result.success(artist);
    }

    @PostMapping("/artists/submissions")
    public Result<Artist> submitArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody ArtistSubmissionRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistSubmissionRequest serviceRequest = toServiceArtistSubmissionRequest(operatorId, request);
        return Result.success(artistGovernanceService.submit(serviceRequest));
    }

    @PutMapping("/artists/{id}")
    public Result<Artist> updateArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable Long id,
                                       @RequestBody ArtistUpdateRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistUpdateRequest serviceRequest = toServiceArtistUpdateRequest(operatorId, request);
        return Result.success(artistGovernanceService.updateProfile(id, serviceRequest));
    }

    public Result<List<Artist>> listPendingArtists(String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(artistGovernanceService.listPending(operatorId));
    }
    @GetMapping("/artists/pending")
    public Result<Page<Artist>> listPendingArtists(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam(defaultValue = "1") long page,
                                                   @RequestParam(defaultValue = "10") long size,
                                                   @RequestParam(required = false) String keyword,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String riskStatus,
                                                   @RequestParam(required = false) String qualificationStatus) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String role = checkArtistManageRole(operatorId);
        if (role == null) {
            return Result.fail(ResultCode.FORBIDDEN);
        }
        String reviewStatus = StringUtils.hasText(status) && !"all".equalsIgnoreCase(status.trim()) ? status.trim() : null;
        if (!StringUtils.hasText(status)) {
            reviewStatus = "pending";
        }
        return Result.success(artistAdminService.listManageable(operatorId, role, page, size, keyword, category,
                reviewStatus, riskStatus, qualificationStatus));
    }

    @PostMapping("/artists/{id}/review")
    public Result<Artist> reviewArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable Long id,
                                       @RequestBody ArtistReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistReviewRequest serviceRequest = toServiceArtistReviewRequest(operatorId, request);
        return Result.success(artistGovernanceService.review(id, serviceRequest));
    }

    @PostMapping("/artists/{id}/approve")
    public Result<Artist> approveArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable Long id,
                                        @RequestBody(required = false) ArtistReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistReviewRequest serviceRequest = toServiceArtistReviewRequest(operatorId, request);
        serviceRequest.setAction("approve");
        return Result.success(artistGovernanceService.review(id, serviceRequest));
    }

    @PostMapping("/artists/{id}/reject")
    public Result<Artist> rejectArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable Long id,
                                       @RequestBody(required = false) ArtistReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistReviewRequest serviceRequest = toServiceArtistReviewRequest(operatorId, request);
        serviceRequest.setAction("reject");
        return Result.success(artistGovernanceService.review(id, serviceRequest));
    }

    @PostMapping("/artists/{id}/risk")
    public Result<Artist> updateArtistRisk(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable Long id,
                                            @RequestBody ArtistRiskRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistRiskRequest serviceRequest = toServiceArtistRiskRequest(operatorId, request);
        return Result.success(artistGovernanceService.updateRisk(id, serviceRequest));
    }

    @PostMapping("/artists/{id}/mark-risk")
    public Result<Artist> markRiskArtist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable Long id,
                                         @RequestBody(required = false) ArtistRiskRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        ArtistRiskRequest serviceRequest = toServiceArtistRiskRequest(operatorId, request);
        serviceRequest.setRiskStatus("risky");
        return Result.success(artistGovernanceService.updateRisk(id, serviceRequest));
    }

    private ArtistSubmissionRequest toServiceArtistSubmissionRequest(Long operatorId, ArtistSubmissionRequest source) {
        ArtistSubmissionRequest target = new ArtistSubmissionRequest();
        target.setUserId(operatorId);
        if (source == null) return target;
        target.setName(source.getName());
        target.setAlias(source.getAlias());
        target.setArtistType(source.getArtistType());
        target.setCountryOrRegion(source.getCountryOrRegion());
        target.setAgency(source.getAgency());
        target.setRepresentativeWorks(source.getRepresentativeWorks());
        target.setCategoryTags(source.getCategoryTags());
        target.setDescription(source.getDescription());
        target.setSourceNote(source.getSourceNote());
        return target;
    }

    private ArtistUpdateRequest toServiceArtistUpdateRequest(Long operatorId, ArtistUpdateRequest source) {
        ArtistUpdateRequest target = new ArtistUpdateRequest();
        target.setUserId(operatorId);
        if (source == null) return target;
        target.setName(source.getName());
        target.setAlias(source.getAlias());
        target.setArtistType(source.getArtistType());
        target.setCountryOrRegion(source.getCountryOrRegion());
        target.setAgency(source.getAgency());
        target.setRepresentativeWorks(source.getRepresentativeWorks());
        target.setCategoryTags(source.getCategoryTags());
        target.setDescription(source.getDescription());
        target.setAvatar(source.getAvatar());
        return target;
    }

    private ArtistReviewRequest toServiceArtistReviewRequest(Long operatorId, ArtistReviewRequest source) {
        ArtistReviewRequest target = new ArtistReviewRequest();
        target.setUserId(operatorId);
        if (source == null) return target;
        target.setAction(source.getAction());
        target.setNote(source.getNote());
        return target;
    }

    private ArtistRiskRequest toServiceArtistRiskRequest(Long operatorId, ArtistRiskRequest source) {
        ArtistRiskRequest target = new ArtistRiskRequest();
        target.setUserId(operatorId);
        if (source == null) return target;
        target.setRiskStatus(source.getRiskStatus());
        target.setReason(source.getReason());
        return target;
    }

    @PostMapping("/activities/{id}/risk-resolution")
    public Result<ActivityRiskResolutionResponse> submitRiskResolution(@PathVariable Long id,
                                                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                       @RequestBody ActivityRiskResolutionRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new ActivityRiskResolutionRequest();
        }
        request.setUserId(operatorId);
        return Result.success(activityRiskResponseService.submitResolution(id, request));
    }

    public Result<List<ActivityRiskResolutionResponse>> listRiskResolutions(String authorization,
                                                                            Long userId,
                                                                            String status) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(activityRiskResponseService.listResolutions(operatorId, status));
    }
    @GetMapping("/risk-resolutions")
    public Result<Page<ActivityRiskResolutionResponse>> listRiskResolutions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String reasonType,
            @RequestParam(required = false) String status) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(activityRiskResponseService.listResolutionsPage(operatorId, page, size, keyword, reasonType, status));
    }

    @PostMapping("/risk-resolutions/{id}/review")
    public Result<ActivityRiskResolutionResponse> reviewRiskResolution(@PathVariable Long id,
                                                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                       @RequestBody ActivityRiskResolutionReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new ActivityRiskResolutionReviewRequest();
        }
        request.setUserId(operatorId);
        return Result.success(activityRiskResponseService.reviewResolution(id, request));
    }

    @PostMapping("/risk-resolutions/{id}/approve")
    public Result<ActivityRiskResolutionResponse> approveRiskResolution(@PathVariable Long id,
                                                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                       @RequestBody(required = false) ActivityRiskResolutionReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new ActivityRiskResolutionReviewRequest();
        }
        request.setUserId(operatorId);
        request.setAction("approve");
        return Result.success(activityRiskResponseService.reviewResolution(id, request));
    }

    @PostMapping("/risk-resolutions/{id}/reject")
    public Result<ActivityRiskResolutionResponse> rejectRiskResolution(@PathVariable Long id,
                                                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                      @RequestBody(required = false) ActivityRiskResolutionReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new ActivityRiskResolutionReviewRequest();
        }
        request.setUserId(operatorId);
        request.setAction("reject");
        return Result.success(activityRiskResponseService.reviewResolution(id, request));
    }

    @PostMapping("/activities/{id}/suspend")
    public Result<ActivityRiskResolutionResponse> suspendActivityForRisk(@PathVariable Long id,
                                                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                          @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        String reason = body.get("reason") == null ? null : body.get("reason").toString();
        return Result.success(activityRiskResponseService.adminSuspendActivity(id, userId, reason));
    }

    @GetMapping("/risk-cases")
    public Result<List<ActivityRiskCaseResponse>> listRiskCases(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        userId = operatorId;
        return Result.success(activityRiskResponseService.listRiskCases(userId));
    }

    @GetMapping("/summary")
    public Result<AdminSummaryResponse> getAdminSummary(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        userId = operatorId;
        return Result.success(adminSummaryService.getSummary(userId));
    }

    @GetMapping("/activities/{id}/marketing")
    public Result<ActivityMarketingOverviewResponse> getActivityMarketing(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(activityMarketingService.getMarketing(operatorId, id));
    }

    @PutMapping("/activities/{id}/marketing")
    public Result<ActivityMarketingOverviewResponse> updateActivityMarketing(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) ActivityMarketingRuleRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(activityMarketingService.saveMarketing(operatorId, id, request));
    }

    @PostMapping("/activities/draft")
    public Result<ActivityDraftResponse> createActivityDraft(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> body) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(activityDraftService.createDraft(operatorId, body));
    }

    @GetMapping("/activities/{activityId}/station")
    public Result<StationConfigVersionDetailResponse> getActivityStation(@PathVariable Long activityId,
                                                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.getActivityStationDetail(operatorId, activityId));
    }

    @PostMapping("/stations/{stationId}/config-versions")
    public Result<StationConfigVersionResponse> createStationConfigVersion(@PathVariable Long stationId,
                                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                           @RequestBody StationConfigVersionRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.createDraft(operatorId, stationId, request));
    }

    @PutMapping("/station-config-versions/{versionId}")
    public Result<StationConfigVersionResponse> updateStationConfigVersion(@PathVariable Long versionId,
                                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                           @RequestBody StationConfigVersionRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.updateDraft(operatorId, versionId, request));
    }

    @DeleteMapping("/station-config-versions/{versionId}")
    public Result<Void> deleteStationConfigVersion(@PathVariable Long versionId,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        stationConfigVersionService.deleteDraft(versionId, operatorId);
        return Result.success();
    }

    @PostMapping("/station-config-versions/{versionId}/submit")
    public Result<StationConfigVersionResponse> submitStationConfigVersion(@PathVariable Long versionId,
                                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                           @RequestBody(required = false) Map<String, Object> body) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.submit(versionId, operatorId));
    }

    @PostMapping("/station-config-versions/{versionId}/withdraw")
    public Result<StationConfigVersionResponse> withdrawStationConfigVersion(@PathVariable Long versionId,
                                                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                             @RequestBody(required = false) Map<String, Object> body) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.withdraw(versionId, operatorId));
    }

    @GetMapping("/station-config-versions/reviews")
    public Result<List<StationConfigVersionResponse>> listStationConfigVersionReviews(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "submitted") String status) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.listReviews(operatorId, status));
    }

    @GetMapping("/station-config-reviews")
    public Result<Page<StationConfigVersionResponse>> listStationConfigReviews(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String changeType,
            @RequestParam(defaultValue = "submitted") String status) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.listReviewsPage(
                operatorId, page, size, keyword, city, changeType, status));
    }

    @GetMapping("/station-config-reviews/{id}/diff")
    public Result<StationConfigReviewDiffResponse> getStationConfigReviewDiff(
            @PathVariable Long id,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.getReviewDiff(operatorId, id));
    }

    @PostMapping("/station-config-versions/{versionId}/approve")
    public Result<StationConfigVersionResponse> approveStationConfigVersion(@PathVariable Long versionId,
                                                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                            @RequestBody(required = false) StationConfigVersionReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.approve(operatorId, versionId, request));
    }

    @PostMapping("/station-config-reviews/{id}/approve")
    public Result<StationConfigVersionResponse> approveStationConfigReview(@PathVariable Long id,
                                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                           @RequestBody(required = false) StationConfigVersionReviewRequest request) {
        return approveStationConfigVersion(id, authorization, request);
    }

    @PostMapping("/station-config-versions/{versionId}/reject")
    public Result<StationConfigVersionResponse> rejectStationConfigVersion(@PathVariable Long versionId,
                                                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                           @RequestBody(required = false) StationConfigVersionReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(stationConfigVersionService.reject(operatorId, versionId, request));
    }

    @PostMapping("/station-config-reviews/{id}/reject")
    public Result<StationConfigVersionResponse> rejectStationConfigReview(@PathVariable Long id,
                                                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                          @RequestBody(required = false) StationConfigVersionReviewRequest request) {
        return rejectStationConfigVersion(id, authorization, request);
    }

    @GetMapping("/orders")
    public Result<List<OrderInfoResponse>> listAdminOrders(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestParam(required = false) Long userId,
                                                           @RequestParam(defaultValue = "false") Boolean paidOnly) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(orderAdminQueryService.listOrders(operatorId, paidOnly));
    }

    @GetMapping("/check-in/overview")
    public Result<CheckInOverviewResponse> getCheckInOverview(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam Long sessionId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(checkInAdminQueryService.getOverview(operatorId, sessionId));
    }

    @GetMapping("/check-in/records")
    public Result<List<CheckInRecordResponse>> listCheckInRecords(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam Long sessionId,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(checkInAdminQueryService.listRecords(operatorId, sessionId, result, page, size));
    }

    @PostMapping("/check-in/manual")
    public Result<CheckInRecordResponse> manualCheckIn(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) CheckInManualRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(checkInAdminQueryService.manualCheckIn(operatorId, request));
    }

    @PostMapping("/tours/draft")
    public Result<Tour> createTourDraft(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody TourDraftCreateDTO body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(tourStationService.createTourDraftFromRequest(userId, body));
    }

    public Result<Tour> createTourDraft(String authorization, Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        Map<String, Object> safeBody = withOperatorUserId(body, userId);
        return Result.success(tourStationService.createTourDraft(userId, safeBody));
    }

    @GetMapping("/tours")
    public Result<Page<Tour>> listTours(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(required = false) Long userId,
                                         @RequestParam(defaultValue = "1") Integer page,
                                         @RequestParam(defaultValue = "10") Integer size,
                                         @RequestParam(required = false) Long categoryId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(tourStationService.listManageableTours(userId, page, size, categoryId));
    }

    public Result<Page<Tour>> listTours(String authorization, Long userId, Integer page, Integer size) {
        return listTours(authorization, userId, page, size, null);
    }

    @GetMapping("/tours/{tourId}")
    public Result<Map<String, Object>> getTour(@PathVariable Long tourId,
                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(tourStationService.getManageableTourDetail(userId, tourId));
    }

    @DeleteMapping("/tours/{tourId}")
    public Result<Void> deleteTourDraft(@PathVariable Long tourId,
                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        tourStationService.deleteTourDraft(userId, tourId);
        return Result.success();
    }

    @PostMapping("/tours/{tourId}/announce")
    public Result<Tour> announceTourCities(@PathVariable Long tourId,
                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(tourStationService.announceTourCities(userId, tourId));
    }

    @PostMapping("/tours/{tourId}/deactivate")
    public Result<RefundImpactResponse> deactivateTour(@PathVariable Long tourId,
                                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestBody DeactivateActivityRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) request = new DeactivateActivityRequest();
        request.setUserId(operatorId);
        return Result.success(tourStationService.deactivateTour(tourId, request));
    }

    @PostMapping("/tours/{tourId}/stations/draft")
    public Result<Station> createStationDraft(@PathVariable Long tourId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        Map<String, Object> safeBody = withOperatorUserId(body, userId);
        return Result.success(tourStationService.createStationDraft(userId, tourId, safeBody));
    }

    @PostMapping("/stations/{stationId}/publish")
    public Result<Map<String, Object>> publishStation(@PathVariable Long stationId,
                                                      @RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        return Result.success(tourStationService.publishStation(userId, stationId, body));
    }

    /** 获取用户角色，非admin/organizer返回null并拒绝 */
    private String checkRole(Long userId) {
        return userAccessService.requireAdminOrOrganizerRole(userId);
    }

    private String checkActivityRole(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "activity.manage");
    }

    private String checkActivityOrTourRole(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "activity.manage", "tour.manage");
    }

    private String checkSessionRole(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "session.manage", "activity.manage", "tour.manage");
    }

    private void writeTicketTypeAudit(Long operatorId, String operatorRole, String action, TicketType ticketType, String result) {
        if (ticketType == null || ticketType.getId() == null) return;
        try {
            OperationAuditWriteRequest request = new OperationAuditWriteRequest();
            request.setOperatorId(operatorId);
            request.setOperatorRole(operatorRole);
            request.setAction(action);
            request.setTargetType("ticket_type");
            request.setTargetId(ticketType.getId());
            request.setTargetRef(ticketType.getName());
            request.setReason("ticket_type.create".equals(action) ? "创建票档" : "更新票档");
            request.setResult(result);
            request.setSuccess(true);
            userAccessService.writeOperationAudit(request);
        } catch (RuntimeException e) {
            log.warn("Failed to write ticket type audit action={} ticketTypeId={}: {}", action, ticketType.getId(), e.getMessage());
        }
    }

    private String checkArtistManageRole(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "artist.manage");
    }

    private String checkVenueReadRole(Long userId) {
        return userAccessService.requireAdminOrOrganizerOrAnyPermissionRole(userId, "venue.manage", "session.manage", "activity.manage", "tour.manage");
    }

    private String checkVenueManageRole(Long userId) {
        return userAccessService.requireAdminOrAnyPermissionRole(userId, "venue.manage");
    }

    private Long resolveArtistId(Map<String, Object> body) {
        Long artistId = parsePositiveLong(body.get("artistId"));
        if (artistId != null) return artistId;
        String artistName = parseNonBlankString(body.get("artistName"));
        if (artistName == null || artistMapper == null) return null;
        Artist existing = artistMapper.selectOne(new LambdaQueryWrapper<Artist>()
                .eq(Artist::getName, artistName)
                .eq(Artist::getStatus, 1)
                .last("LIMIT 1"));
        if (existing != null && existing.getId() != null) return existing.getId();
        Artist artist = new Artist();
        artist.setName(artistName);
        artist.setStatus(1);
        artist.setCreateTime(LocalDateTime.now());
        artistMapper.insert(artist);
        return artist.getId();
    }

    /** 检查organizer是否拥有此活动 */
    private boolean ownsActivity(Long activityId, Long userId) {
        Activity a = activityMapper.selectById(activityId);
        return a != null && userId.equals(a.getOrganizerId());
    }

    // ========== 活动管理 ==========

    @Transactional
    @PostMapping("/activities")
    public Result<Activity> createActivity(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        Map<String, Object> safeBody = withOperatorUserId(body, userId);
        if (userId == null) return Result.fail(400, "用户ID不正确");
        String role = checkActivityRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        Long categoryId = parsePositiveLong(safeBody.get("categoryId"));
        if (categoryId == null) return Result.fail(400, "分类ID不正确");
        List<ActivityArtistDto> artists = parseArtists(safeBody.get("artists"));
        Long artistId = artists.stream()
                .filter(a -> Boolean.TRUE.equals(a.getPrimary()))
                .map(ActivityArtistDto::getArtistId)
                .findFirst()
                .orElseGet(() -> resolveArtistId(safeBody));
        if (artistId == null && !artists.isEmpty()) artistId = artists.get(0).getArtistId();
        if (artistId == null) return Result.fail(400, "艺人/团队名称不能为空");
        String name = parseNonBlankString(safeBody.get("name"));
        if (name == null) return Result.fail(400, "活动名称不能为空");

        Activity activity = new Activity();
        activity.setCategoryId(categoryId);
        activity.setArtistId(artistId);
        activity.setTourId(parsePositiveLong(safeBody.get("tourId")));
        activity.setStationId(parsePositiveLong(safeBody.get("stationId")));
        activity.setVenueApplicationId(parsePositiveLong(safeBody.get("venueApplicationId")));
        activity.setVenueApprovalNo(parseNonBlankString(safeBody.get("venueApprovalNo")));
        String venueApprovalFileUrl = parseNonBlankString(safeBody.get("venueApprovalFileUrl"));
        activity.setVenueApprovalFileUrl(venueApprovalFileUrl);
        activity.setVenueApprovalNote(parseNonBlankString(safeBody.get("venueApprovalNote")));
        activity.setPublishStatus(safeBody.get("publishStatus") != null ? safeBody.get("publishStatus").toString() : "draft");
        String seatMapVisibility = parseSeatMapVisibility(safeBody.get("seatMapVisibility"), SEAT_MAP_VISIBILITY_HIDDEN);
        if (seatMapVisibility == null) return Result.fail(400, "座位图展示策略不正确");
        activity.setSeatMapVisibility(seatMapVisibility);
        try {
            activity.setPerUserLimit(parsePerUserLimit(safeBody.get("perUserLimit")));
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        }
        try {
            setRefundPolicy(activity, safeBody);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        }
        activity.setRealNameRequired(true);
        activity.setTicketTransferAllowed(!Boolean.FALSE.equals(parseBooleanFlag(safeBody.get("ticketTransferAllowed"))));
        activity.setName(name);
        activity.setDescription(safeBody.get("description") != null ? safeBody.get("description").toString() : null);
        activity.setPoster(safeBody.get("poster") != null ? safeBody.get("poster").toString() : null);
        activity.setStatus(1);
        activity.setOrganizerId(userId); // 记录创建者
        activityMapper.insert(activity);
        if (!artists.isEmpty()) activityArtistService.saveLineup(activity.getId(), artists);
        publishSearchUpsert(activity.getId());
        return Result.success(activity);
    }

    @PutMapping("/activities/{id}")
    public Result<Activity> updateActivity(@PathVariable Long id,
                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Map<String, Object> body) {
        if (id == null || id <= 0) return Result.fail(400, "活动ID不正确");
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        if (userId == null) return Result.fail(400, "用户ID不正确");
        String role = checkActivityRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        Activity activity = activityMapper.selectById(id);
        if (activity == null) return Result.fail(404, "活动不存在");

        // organizer只能修改自己的活动
        if ("organizer".equals(role) && !userId.equals(activity.getOrganizerId()))
            return Result.fail(403, "只能修改自己主办的活动");

        if (body.containsKey("name")) {
            String name = parseNonBlankString(body.get("name"));
            if (name == null) return Result.fail(400, "活动名称不能为空");
            activity.setName(name);
        }
        if (body.containsKey("description")) activity.setDescription(body.get("description") != null ? body.get("description").toString() : null);
        if (body.containsKey("poster")) activity.setPoster(body.get("poster") != null ? body.get("poster").toString() : null);
        if (body.containsKey("categoryId")) {
            Long categoryId = parsePositiveLong(body.get("categoryId"));
            if (categoryId == null) return Result.fail(400, "分类ID不正确");
            activity.setCategoryId(categoryId);
        }
        if (body.containsKey("artistId")) {
            Long artistId = parsePositiveLong(body.get("artistId"));
            if (artistId == null) return Result.fail(400, "艺人/团队名称不能为空");
            activity.setArtistId(artistId);
        } else if (body.containsKey("artistName")) {
            Long artistId = resolveArtistId(body);
            if (artistId == null) return Result.fail(400, "艺人/团队名称不能为空");
            activity.setArtistId(artistId);
        }
        if (body.containsKey("artists")) {
            List<ActivityArtistDto> artists = parseArtists(body.get("artists"));
            activityArtistService.saveLineup(id, artists);
            Long lineupArtistId = artists.stream()
                    .filter(a -> Boolean.TRUE.equals(a.getPrimary()))
                    .findFirst()
                    .map(ActivityArtistDto::getArtistId)
                    .orElseGet(() -> artists.isEmpty() ? null : artists.get(0).getArtistId());
            if (lineupArtistId != null) activity.setArtistId(lineupArtistId);
        }
        if (body.containsKey("seatMapVisibility")) {
            String seatMapVisibility = parseSeatMapVisibility(body.get("seatMapVisibility"), null);
            if (seatMapVisibility == null) return Result.fail(400, "座位图展示策略不正确");
            activity.setSeatMapVisibility(seatMapVisibility);
        }
        if (body.containsKey("perUserLimit")) {
            try {
                activity.setPerUserLimit(parsePerUserLimit(body.get("perUserLimit")));
            } catch (IllegalArgumentException e) {
                return Result.fail(400, e.getMessage());
            }
        }
        try {
            setRefundPolicy(activity, body);
        } catch (IllegalArgumentException e) {
            return Result.fail(400, e.getMessage());
        }
        activity.setRealNameRequired(true);
        if (body.containsKey("ticketTransferAllowed")) {
            activity.setTicketTransferAllowed(!Boolean.FALSE.equals(parseBooleanFlag(body.get("ticketTransferAllowed"))));
        }
        activityMapper.updateById(activity);
        publishSearchUpsert(activity.getId());
        return Result.success(activity);
    }

    @GetMapping("/activities/{id}")
    public Result<Activity> getAdminActivity(@PathVariable Long id,
                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestParam(required = false) Long userId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        userId = operatorId;
        if (id == null || id <= 0) return Result.fail(400, "活动ID不正确");
        if (userId == null || userId <= 0) return Result.fail(400, "用户ID不正确");
        String role = checkActivityRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        Activity activity = activityMapper.selectById(id);
        if (activity == null) return Result.fail(404, "活动不存在");

        // organizer只能查看自己的活动
        if ("organizer".equals(role) && !userId.equals(activity.getOrganizerId()))
            return Result.fail(403, "只能查看自己主办的活动");

        if (activityArtistService != null) {
            List<ActivityArtistDto> lineup = activityArtistService.listAdminLineup(id);
            activity.setArtists(lineup);
            String summary = lineup.stream()
                    .filter(a -> "public".equals(a.getVisibility()))
                    .map(ActivityArtistDto::getName)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.joining("、"));
            activity.setArtistName(summary);
            if (!StringUtils.hasText(summary)) attachArtistName(activity);
        } else {
            attachArtistName(activity);
        }

        return Result.success(activity);
    }

    private void attachArtistName(Activity activity) {
        if (activity == null || activity.getArtistId() == null || artistMapper == null) return;
        Artist artist = artistMapper.selectById(activity.getArtistId());
        if (artist != null) activity.setArtistName(artist.getName());
    }

    private List<ActivityArtistDto> parseArtists(Object value) {
        if (!(value instanceof List<?>)) return List.of();
        List<?> list = (List<?>) value;
        List<ActivityArtistDto> artists = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<?, ?> map = (Map<?, ?>) item;
            ActivityArtistDto dto = new ActivityArtistDto();
            dto.setArtistId(parsePositiveLong(map.get("artistId")));
            dto.setPrimary(Boolean.TRUE.equals(map.get("isPrimary")) || Boolean.TRUE.equals(map.get("primary")));
            dto.setRoleType(parseNonBlankString(map.get("roleType")));
            dto.setRoleName(parseNonBlankString(map.get("roleName")));
            dto.setVisibility(parseNonBlankString(map.get("visibility")));
            Long sort = parsePositiveLong(map.get("sort"));
            dto.setSort(sort == null ? null : sort.intValue());
            artists.add(dto);
        }
        return artists;
    }

    @PutMapping("/activities/{id}/status")
    public Result<Void> updateActivityStatus(@PathVariable Long id,
                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody UpdateActivityStatusRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) request = new UpdateActivityStatusRequest();
        request.setUserId(operatorId);
        activityAdminService.updateActivityStatus(id, request);
        return Result.success();
    }

    @PostMapping("/activities/{id}/deactivate")
    public Result<RefundImpactResponse> deactivateActivity(@PathVariable Long id,
                                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                                             @RequestBody DeactivateActivityRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) request = new DeactivateActivityRequest();
        request.setUserId(operatorId);
        return Result.success(activityAdminService.deactivateActivity(id, request));
    }

    @PostMapping("/activities/{id}/buyer-notifications")
    public Result<ActivityBuyerNotificationResponse> notifyActivityBuyers(@PathVariable Long id,
                                                                          @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                          @RequestBody ActivityBuyerNotificationRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) request = new ActivityBuyerNotificationRequest();
        request.setUserId(operatorId);
        return Result.success(activityAdminService.notifyActivityBuyers(id, request));
    }

    @GetMapping("/activities/{activityId}/seat-layout")
    public Result<SeatCraftLayoutDtos.LayoutResponse> getActivitySeatLayout(@PathVariable Long activityId,
                                                                             @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                             @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(activitySeatLayoutService.getLayout(userId, activityId));
    }

    @PostMapping("/activities/{activityId}/seat-layout/blank")
    public Result<SeatCraftLayoutDtos.LayoutResponse> createBlankActivitySeatLayout(@PathVariable Long activityId,
                                                                                   @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                                   @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(activitySeatLayoutService.createBlankLayout(userId, activityId));
    }

    @PutMapping("/activities/{activityId}/seat-layout")
    public Result<SeatCraftLayoutDtos.LayoutResponse> updateActivitySeatLayout(@PathVariable Long activityId,
                                                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                               @RequestBody SeatCraftLayoutDtos.LayoutSaveRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) {
            return Result.fail(400, "座位图参数不能为空");
        }
        request.setUserId(operatorId);
        return Result.success(activitySeatLayoutService.updateLayout(operatorId, activityId, request.getLayout()));
    }

    @PostMapping("/organizers/deactivate")
    public Result<RefundImpactResponse> deactivateOrganizer(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DeactivateOrganizerRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new DeactivateOrganizerRequest();
        }
        request.setUserId(operatorId);
        return Result.success(activityAdminService.deactivateOrganizer(request));
    }

    private Long parseOperatorId(String authorization) {
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

    private Map<String, Object> withOperatorUserId(Map<String, Object> body, Long operatorId) {
        Map<String, Object> next = body == null ? new HashMap<>() : new HashMap<>(body);
        next.put("userId", operatorId);
        return next;
    }

    private Long parsePositiveLong(Object value) {
        if (value == null) return null;
        try {
            Long parsed = Long.valueOf(value.toString());
            return parsed > 0 ? parsed : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String parseNonBlankString(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizePrivateAssetBizType(String bizType) {
        if ("activity-venue-proof".equals(bizType)) return "venue-proof";
        if ("venue-change-proof".equals(bizType)) return "venue-proof";
        if ("FIRE_SAFETY_PERMIT".equals(bizType)) return "venue-fire-safety";
        if ("VENUE_LEASE_AGREEMENT".equals(bizType)) return "venue-lease-agreement";
        return bizType;
    }

    private String parseSeatMapVisibility(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        String visibility = value.toString().trim();
        if (SEAT_MAP_VISIBILITY_PUBLISHED.equals(visibility) || SEAT_MAP_VISIBILITY_HIDDEN.equals(visibility)) {
            return visibility;
        }
        return null;
    }

    private Integer parsePerUserLimit(Object value) {
        if (value == null || value.toString().trim().isEmpty()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException("个人限购张数必须大于0");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("个人限购张数必须为数字");
        }
    }

    private void setRefundPolicy(Activity activity, Map<String, Object> body) {
        Integer fullDays = parsePositiveInteger(body.get("refundFullDaysBefore"), activity.getRefundFullDaysBefore() == null ? 30 : activity.getRefundFullDaysBefore(), "全额退款提前天数");
        Integer partialDays = parsePositiveInteger(body.get("refundPartialDaysBefore"), activity.getRefundPartialDaysBefore() == null ? 7 : activity.getRefundPartialDaysBefore(), "折扣退款提前天数");
        Integer partialRate = parsePositiveInteger(body.get("refundPartialRate"), activity.getRefundPartialRate() == null ? 80 : activity.getRefundPartialRate(), "折扣退款比例");
        if (fullDays <= partialDays || partialRate > 100) {
            throw new IllegalArgumentException("退款规则需满足全额退款天数大于折扣退款天数，折扣比例不超过100%");
        }
        activity.setRefundFullDaysBefore(fullDays);
        activity.setRefundPartialDaysBefore(partialDays);
        activity.setRefundPartialRate(partialRate);
    }

    private Integer parsePositiveInteger(Object value, int defaultValue, String label) {
        if (value == null || value.toString().trim().isEmpty()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            if (parsed <= 0) throw new IllegalArgumentException(label + "必须大于0");
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "必须为整数");
        }
    }

    private Boolean parseBooleanFlag(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value == null) {
            return false;
        }
        String text = value.toString().trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text);
    }

    @DeleteMapping("/activities/{id}")
    public Result<DeleteActivityResponse> deleteActivity(@PathVariable Long id,
                                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                                         @RequestBody DeleteActivityRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) request = new DeleteActivityRequest();
        request.setUserId(operatorId);
        if (request.getUserId() == null || request.getUserId() <= 0) {
            return Result.fail(400, "用户ID不正确");
        }
        if (!StringUtils.hasText(request.getReason())) {
            return Result.fail(400, "删除原因不能为空");
        }
        return Result.success(activityAdminService.deleteActivity(id, request));
    }

    @GetMapping("/activities")
    public Result<Page<Activity>> listAdminActivities(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long categoryId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        userId = operatorId;
        String role = checkActivityRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        LambdaQueryWrapper<Activity> wrapper = new LambdaQueryWrapper<>();
        // organizer 只能看自己的活动，admin 看全部
        if ("organizer".equals(role)) {
            wrapper.eq(Activity::getOrganizerId, userId);
        }
        wrapper.ne(Activity::getPublishStatus, "deleted");
        wrapper.isNull(Activity::getTourId);
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Activity::getName, keyword.trim());
        }
        if (status != null) {
            wrapper.eq(Activity::getStatus, status);
        }
        if (categoryId != null) {
            wrapper.eq(Activity::getCategoryId, categoryId);
        }
        wrapper.orderByAsc(Activity::getId);
        Page<Activity> result = activityMapper.selectPage(new Page<>(page, size), wrapper);
        if (activityArtistService != null) {
            result.getRecords().forEach(this::attachLineupSummary);
        }
        return Result.success(result);
    }

    public Result<Page<Activity>> listAdminActivities(String authorization, Long userId, Integer page, Integer size, String keyword, Integer status) {
        return listAdminActivities(authorization, userId, page, size, keyword, status, null);
    }

    private void attachLineupSummary(Activity activity) {
        if (activity == null || activity.getId() == null) return;
        List<ActivityArtistDto> lineup = activityArtistService.listAdminLineup(activity.getId());
        activity.setArtists(lineup);
        String summary = lineup.stream()
                .filter(a -> "public".equals(a.getVisibility()))
                .map(ActivityArtistDto::getName)
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("、"));
        if (StringUtils.hasText(summary)) {
            activity.setArtistName(summary);
        }
    }

    // ========== 场次管理（权限继承自活动） ==========

    @PostMapping("/sessions")
    public Result<Session> createSession(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        Map<String, Object> safeBody = withOperatorUserId(body, userId);
        return Result.success(sessionAdminService.createSession(safeBody));
    }

    @PutMapping("/sessions/{id}")
    public Result<Session> updateSession(@PathVariable Long id,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        Map<String, Object> safeBody = withOperatorUserId(body, userId);
        return Result.success(sessionAdminService.updateSession(id, safeBody));
    }

    @DeleteMapping("/sessions/{id}")
    public Result<Void> deleteSession(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestParam(required = false) Long userId,
                                      @PathVariable Long id) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        sessionAdminService.deleteSession(userId, id);
        return Result.success();
    }

    @GetMapping("/sessions")
    public Result<Page<SessionAdminResponse>> listAdminSessions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long activityId,
            @RequestParam(required = false) Long venueId,
            @RequestParam(required = false) Integer status) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(sessionAdminService.listSessions(userId, page, size, activityId, venueId, status));
    }

    @GetMapping("/sessions/{sessionId}/seat-layout")
    public Result<SeatCraftLayoutDtos.LayoutResponse> getSessionSeatLayout(@PathVariable Long sessionId,
                                                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                              @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(sessionSeatLayoutService.getLayout(userId, sessionId));
    }

    @PostMapping("/sessions/{sessionId}/seat-layout/blank")
    public Result<SeatCraftLayoutDtos.LayoutResponse> createBlankSessionSeatLayout(@PathVariable Long sessionId,
                                                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                               @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(sessionSeatLayoutService.createBlankLayout(userId, sessionId));
    }

    @PutMapping("/sessions/{sessionId}/seat-layout")
    public Result<SeatCraftLayoutDtos.LayoutResponse> updateSessionSeatLayout(@PathVariable Long sessionId,
                                                                                  @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                                  @RequestBody SeatCraftLayoutDtos.LayoutSaveRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) {
            return Result.fail(400, "座位图参数不能为空");
        }
        request.setUserId(operatorId);
        return Result.success(sessionSeatLayoutService.updateLayout(operatorId, sessionId, request.getLayout()));
    }

    @PutMapping("/sessions/{sessionId}/ticket-bindings")
    public Result<Void> updateSessionTicketBindings(@PathVariable Long sessionId,
                                                     @RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody TicketBindingUpdateRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) {
            return Result.fail(400, "票档绑定参数不能为空");
        }
        request.setUserId(operatorId);
        sessionSeatLayoutService.updateTicketBindings(operatorId, sessionId, request.getBindings());
        return Result.success();
    }

    @GetMapping("/sessions/{sessionId}/seat-layout/ticket-drafts")
    public Result<List<SeatCraftLayoutDtos.SectionResponse>> getTicketDrafts(@PathVariable Long sessionId,
                                                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                               @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(sessionSeatLayoutService.buildTicketDraftsForSession(userId, sessionId));
    }

    // ========== 票档管理（权限继承自活动） ==========

    @Transactional
    @PostMapping("/ticket-types")
    public Result<TicketType> createTicketType(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        String role = checkSessionRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        Long sessionId = Long.valueOf(body.get("sessionId").toString());
        Session session = sessionMapper.selectById(sessionId);
        if (session == null) return Result.fail(404, "场次不存在");

        if ("organizer".equals(role) && !ownsActivity(session.getActivityId(), userId))
            return Result.fail(403, "只能管理自己主办的票档");

        TicketType tt = new TicketType();
        tt.setSessionId(sessionId);
        tt.setName(body.get("name").toString());
        tt.setPrice(new java.math.BigDecimal(body.get("price").toString()));
        tt.setStatus(1);
        if (body.containsKey("areaIds")) {
            @SuppressWarnings("unchecked")
            List<Object> rawAreaIds = (List<Object>) body.get("areaIds");
            List<Long> areaIds = rawAreaIds.stream().map(value -> Long.valueOf(value.toString())).collect(java.util.stream.Collectors.toList());
            tt = ticketTypeAreaService.createTicketType(tt, areaIds);
        } else if (body.containsKey("layoutSectionIds")) {
            @SuppressWarnings("unchecked")
            List<Object> rawSectionIds = (List<Object>) body.get("layoutSectionIds");
            List<Long> sectionIds = rawSectionIds.stream().map(value -> Long.valueOf(value.toString())).distinct().collect(Collectors.toList());
            if (sectionIds.isEmpty()) {
                return Result.fail(400, "请选择绑定分区");
            }
            int totalStock = sessionSeatLayoutService.countAvailableSeatsForSections(sessionId, sectionIds);
            if (totalStock <= 0) {
                return Result.fail(400, "所选分区暂无可售座位");
            }
            tt.setTotalStock(totalStock);
            tt.setRemainStock(totalStock);
            ticketTypeMapper.insert(tt);
            Map<Long, SessionSeatLayoutService.TicketDraftInput> drafts = new java.util.LinkedHashMap<>();
            for (Long sectionId : sectionIds) {
                SessionSeatLayoutService.TicketDraftInput input = new SessionSeatLayoutService.TicketDraftInput();
                input.setTicketTypeId(tt.getId());
                input.setName(tt.getName());
                input.setPrice(tt.getPrice());
                drafts.put(sectionId, input);
            }
            sessionSeatLayoutService.bindTicketTypesAndGenerateSeats(userId, sessionId, drafts);
        } else {
            tt.setTotalStock(Integer.valueOf(body.get("totalStock").toString()));
            tt.setRemainStock(Integer.valueOf(body.get("totalStock").toString()));
            ticketTypeMapper.insert(tt);
        }
        publishSearchUpsert(session.getActivityId());
        writeTicketTypeAudit(userId, role, "ticket_type.create", tt,
                "创建票档，场次编号 " + sessionId + "，票价 " + tt.getPrice() + "，总库存 " + tt.getTotalStock());
        return Result.success(tt);
    }

    @PutMapping("/ticket-types/{id}")
    public Result<TicketType> updateTicketType(@PathVariable Long id,
                                               @RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        String role = checkSessionRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        TicketType tt = ticketTypeMapper.selectById(id);
        if (tt == null) return Result.fail(404, "票档不存在");

        Session session = sessionMapper.selectById(tt.getSessionId());
        if ("organizer".equals(role) && !ownsActivity(session.getActivityId(), userId))
            return Result.fail(403, "只能管理自己主办的票档");

        List<String> updatedFields = new ArrayList<>();
        if (body.containsKey("name")) {
            tt.setName(body.get("name").toString());
            updatedFields.add("名称");
        }
        if (body.containsKey("price")) {
            tt.setPrice(new java.math.BigDecimal(body.get("price").toString()));
            updatedFields.add("票价");
        }
        if (body.containsKey("totalStock")) {
            int nextTotalStock = Integer.valueOf(body.get("totalStock").toString());
            if (nextTotalStock < 0) {
                return Result.fail(400, "目标总库存不能小于 0");
            }
            int currentTotalStock = tt.getTotalStock() == null ? 0 : tt.getTotalStock();
            int currentRemainStock = tt.getRemainStock() == null ? 0 : tt.getRemainStock();
            int soldStock = Math.max(0, currentTotalStock - currentRemainStock);
            if (nextTotalStock < soldStock) {
                return Result.fail(400, "目标总库存不能小于已售数量");
            }
            tt.setTotalStock(nextTotalStock);
            tt.setRemainStock(nextTotalStock - soldStock);
            updatedFields.add("总库存");
        }
        if (body.containsKey("status")) {
            tt.setStatus(Integer.valueOf(body.get("status").toString()));
            updatedFields.add("状态");
        }
        ticketTypeMapper.updateById(tt);
        if (session != null) {
            publishSearchUpsert(session.getActivityId());
        }
        String changed = updatedFields.isEmpty() ? "无字段变化" : String.join("、", updatedFields);
        writeTicketTypeAudit(userId, role, "ticket_type.update", tt,
                "更新票档：" + changed + "；场次编号 " + tt.getSessionId() + "，票价 " + tt.getPrice() + "，总库存 " + tt.getTotalStock() + "，状态 " + tt.getStatus());
        return Result.success(tt);
    }

    @Transactional(rollbackFor = Exception.class)
    @PostMapping("/ticket-types/batch-update")
    public Result<List<TicketType>> batchUpdateTicketTypes(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                           @RequestBody TicketTypeBatchUpdateRequest request) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        String role = checkSessionRole(userId);
        if (role == null) return Result.fail(403, "无权限");
        if (request == null || request.getIds() == null || request.getIds().isEmpty()) {
            return Result.fail(400, "请选择需要批量操作的票档");
        }
        List<Long> ids = request.getIds().stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .collect(Collectors.toList());
        if (ids.size() != request.getIds().size()) {
            return Result.fail(400, "票档编号不正确");
        }
        String action = request.getAction() == null ? "" : request.getAction().trim();
        if (!"UPDATE_PRICE".equals(action) && !"SET_STATUS".equals(action) && !"ADJUST_STOCK".equals(action)) {
            return Result.fail(400, "批量操作类型不正确");
        }

        List<TicketType> ticketTypes = new ArrayList<>();
        Map<Long, Session> sessions = new HashMap<>();
        for (Long id : ids) {
            TicketType ticketType = ticketTypeMapper.selectById(id);
            if (ticketType == null) {
                return Result.fail(404, "票档不存在");
            }
            Session session = sessions.computeIfAbsent(ticketType.getSessionId(), sessionMapper::selectById);
            if (session == null) {
                return Result.fail(404, "场次不存在");
            }
            if ("organizer".equals(role) && !ownsActivity(session.getActivityId(), userId)) {
                return Result.fail(403, "只能管理自己主办的票档");
            }
            ticketTypes.add(ticketType);
        }

        Result<Void> validation = validateBatchTicketTypeUpdate(request, action, ticketTypes);
        if (validation.getCode() != 200) {
            return Result.fail(validation.getCode(), validation.getMessage());
        }

        Set<Long> affectedActivityIds = ticketTypes.stream()
                .map(ticketType -> sessions.get(ticketType.getSessionId()))
                .filter(Objects::nonNull)
                .map(Session::getActivityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (TicketType ticketType : ticketTypes) {
            applyBatchTicketTypeUpdate(ticketType, request, action);
            ticketTypeMapper.updateById(ticketType);
            writeTicketTypeAudit(userId, role, "ticket_type.batch_update", ticketType,
                    "批量更新票档：" + batchTicketTypeActionText(action) + "；场次编号 " + ticketType.getSessionId()
                            + "，票价 " + ticketType.getPrice() + "，总库存 " + ticketType.getTotalStock()
                            + "，状态 " + ticketType.getStatus());
        }
        affectedActivityIds.forEach(this::publishSearchUpsert);
        return Result.success(ticketTypes);
    }

    private Result<Void> validateBatchTicketTypeUpdate(TicketTypeBatchUpdateRequest request, String action, List<TicketType> ticketTypes) {
        if ("UPDATE_PRICE".equals(action)) {
            if (request.getPrice() == null || request.getPrice().compareTo(java.math.BigDecimal.ZERO) <= 0) {
                return Result.fail(400, "目标票价必须大于 0");
            }
            return Result.success();
        }
        if ("SET_STATUS".equals(action)) {
            if (request.getStatus() == null || (request.getStatus() != 0 && request.getStatus() != 1)) {
                return Result.fail(400, "票档状态不正确");
            }
            return Result.success();
        }
        if ("ADJUST_STOCK".equals(action)) {
            if (request.getTotalStock() == null || request.getTotalStock() < 0) {
                return Result.fail(400, "目标总库存不能小于 0");
            }
            for (TicketType ticketType : ticketTypes) {
                int soldStock = soldStock(ticketType);
                if (request.getTotalStock() < soldStock) {
                    return Result.fail(400, "目标总库存不能小于票档“" + ticketTypeDisplayName(ticketType) + "”的已售数量");
                }
            }
            return Result.success();
        }
        return Result.fail(400, "批量操作类型不正确");
    }

    private void applyBatchTicketTypeUpdate(TicketType ticketType, TicketTypeBatchUpdateRequest request, String action) {
        if ("UPDATE_PRICE".equals(action)) {
            ticketType.setPrice(request.getPrice());
            return;
        }
        if ("SET_STATUS".equals(action)) {
            ticketType.setStatus(request.getStatus());
            return;
        }
        if ("ADJUST_STOCK".equals(action)) {
            int soldStock = soldStock(ticketType);
            ticketType.setTotalStock(request.getTotalStock());
            ticketType.setRemainStock(request.getTotalStock() - soldStock);
        }
    }

    private int soldStock(TicketType ticketType) {
        int totalStock = ticketType.getTotalStock() == null ? 0 : ticketType.getTotalStock();
        int remainStock = ticketType.getRemainStock() == null ? 0 : ticketType.getRemainStock();
        return Math.max(0, totalStock - remainStock);
    }

    private String ticketTypeDisplayName(TicketType ticketType) {
        String name = ticketType.getName();
        if (StringUtils.hasText(name)) return name.trim();
        return "票档编号：" + ticketType.getId();
    }

    private String batchTicketTypeActionText(String action) {
        if ("UPDATE_PRICE".equals(action)) return "批量改价";
        if ("SET_STATUS".equals(action)) return "批量启停";
        if ("ADJUST_STOCK".equals(action)) return "批量调整库存";
        return "批量更新";
    }

    @DeleteMapping("/ticket-types/{id}")
    public Result<Void> deleteTicketType(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam(required = false) Long userId,
                                         @PathVariable Long id) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        String role = checkSessionRole(userId);
        if (role == null) return Result.fail(403, "无权限");

        TicketType tt = ticketTypeMapper.selectById(id);
        if (tt == null) return Result.fail(404, "票档不存在");

        Session session = sessionMapper.selectById(tt.getSessionId());
        if ("organizer".equals(role) && !ownsActivity(session.getActivityId(), userId))
            return Result.fail(403, "只能删除自己主办的票档");

        Set<Long> protectedSeatIds = sessionSeatProtectionService.findProtectedSeatIds(tt.getSessionId());
        List<SessionSeat> ticketSeats = sessionSeatService.listBySession(tt.getSessionId()).stream()
                .filter(seat -> Objects.equals(seat.getTicketTypeId(), id))
                .collect(Collectors.toList());
        boolean hasProtectedSeat = ticketSeats.stream()
                .map(SessionSeat::getId)
                .anyMatch(protectedSeatIds::contains);
        if (hasProtectedSeat) {
            return Result.fail(400, "该票档已有购票订单，请先完成退款后再删除。");
        }
        ticketSeats.forEach(seat -> {
            seat.setTicketTypeId(null);
            sessionSeatService.update(seat);
        });
        ticketTypeMapper.deleteById(id);
        stockRecalculationService.recalculateForSession(tt.getSessionId());
        if (session != null) {
            publishSearchUpsert(session.getActivityId());
        }
        return Result.success();
    }

    private void publishSearchUpsert(Long activityId) {
        if (searchIndexEventPublisher != null && activityId != null) {
            searchIndexEventPublisher.publishUpsert(activityId);
        }
    }

    private void publishVenueSearchUpserts(Long venueId) {
        if (searchIndexEventPublisher == null || venueId == null) {
            return;
        }
        List<Session> sessions = sessionMapper.selectList(new LambdaQueryWrapper<Session>()
                .eq(Session::getVenueId, venueId));
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        sessions.stream()
                .map(Session::getActivityId)
                .filter(Objects::nonNull)
                .distinct()
                .forEach(this::publishSearchUpsert);
    }

    public static class TicketBindingUpdateRequest {
        private Long userId;
        private List<SessionSeatLayoutService.TicketBindingInput> bindings;

        public Long getUserId() { return userId; }
        public void setUserId(Long userId) { this.userId = userId; }
        public List<SessionSeatLayoutService.TicketBindingInput> getBindings() { return bindings; }
        public void setBindings(List<SessionSeatLayoutService.TicketBindingInput> bindings) { this.bindings = bindings; }
    }

    // ========== 场馆记录（admin全权限，organizer只读） ==========

    @PostMapping("/venues")
    public Result<Venue> createVenue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        String role = checkVenueManageRole(userId);
        if (role == null) return Result.fail(403, "无权限");
        if ("organizer".equals(role)) return Result.fail(403, "仅平台管理员可创建场馆");

        Venue venue = new Venue();
        venue.setName(parseNonBlankString(body.get("name")));
        venue.setCity(body.get("city") != null ? body.get("city").toString() : null);
        venue.setAddress(body.get("address") != null ? body.get("address").toString() : null);
        venue.setCapacity(body.get("capacity") != null ? Integer.valueOf(body.get("capacity").toString()) : null);
        venue.setStatus(1);
        venueMapper.insert(venue);

        if (body.containsKey("layout") && body.get("layout") != null) {
            ObjectMapper objectMapper = new ObjectMapper();
            SeatCraftLayoutDtos.LayoutResponse layout = objectMapper.convertValue(body.get("layout"), SeatCraftLayoutDtos.LayoutResponse.class);
            venueDefaultLayoutService.saveLayout(userId, venue.getId(), layout);
        }

        return Result.success(venue);
    }

    @PutMapping("/venues/{id}")
    public Result<Venue> updateVenue(@PathVariable Long id,
                                     @RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        String role = checkVenueManageRole(userId);
        if (role == null) return Result.fail(403, "无权限");
        if ("organizer".equals(role)) return Result.fail(403, "仅平台管理员可修改场馆");

        Venue venue = venueMapper.selectById(id);
        if (venue == null) return Result.fail(404, "场馆不存在");
        if (body.containsKey("name")) venue.setName(body.get("name").toString());
        if (body.containsKey("city")) venue.setCity(body.get("city") != null ? body.get("city").toString() : null);
        if (body.containsKey("address")) venue.setAddress(body.get("address") != null ? body.get("address").toString() : null);
        if (body.containsKey("capacity")) venue.setCapacity(body.get("capacity") != null ? Integer.valueOf(body.get("capacity").toString()) : null);
        venueMapper.updateById(venue);
        if (body.containsKey("name") || body.containsKey("city")) {
            publishVenueSearchUpserts(id);
        }
        return Result.success(venue);
    }

    @DeleteMapping("/venues/{id}")
    public Result<Void> deleteVenue(@PathVariable Long id,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        String role = checkVenueManageRole(userId);
        if (role == null) return Result.fail(403, "无权限");
        if ("organizer".equals(role)) return Result.fail(403, "仅平台管理员可删除场馆记录");

        Venue venue = venueMapper.selectById(id);
        if (venue == null) {
            return Result.fail(404, "场馆记录不存在");
        }
        try {
            venueMapper.deleteById(id);
        } catch (DataIntegrityViolationException e) {
            return Result.fail(400, "场馆记录已被场次、审核资料或座位模板引用，不能永久删除");
        }
        return Result.success();
    }

    @GetMapping("/venues")
    public Result<List<Venue>> listAdminVenues(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        String role = checkVenueReadRole(userId);
        if (role == null) return Result.fail(403, "无权限");
        return Result.success(venueMapper.selectList(new QueryWrapper<Venue>()
                .eq("status", 1)
                .orderByAsc("id")));
    }

    @PostMapping("/venues/{id}/areas")
    public Result<SeatTemplateResponse> createVenueArea(@PathVariable Long id,
                                                        @RequestHeader(value = "Authorization", required = false) String authorization,
                                                        @RequestBody Map<String, Object> body) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        body = withOperatorUserId(body, userId);
        body.put("venueId", id);
        SeatTemplateResponse response = seatTemplateService.createArea(body);
        response.setSyncResult(sessionSeatService.syncVenueSessions(id));
        return Result.success(response);
    }

    @GetMapping("/venues/{id}/areas")
    public Result<List<VenueArea>> listVenueAreas(@PathVariable Long id,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(seatTemplateService.listAreas(userId, id));
    }

    @GetMapping("/venues/{id}/seats")
    public Result<List<VenueSeat>> listVenueSeats(@PathVariable Long id,
                                                  @RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(seatTemplateService.listSeats(userId, id));
    }

    @GetMapping("/venues/{venueId}/default-layout")
    public Result<SeatCraftLayoutDtos.LayoutResponse> getVenueDefaultLayout(@PathVariable Long venueId,
                                                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        SeatCraftLayoutDtos.LayoutResponse layout = venueDefaultLayoutService.getLayout(venueId);
        return Result.success(layout);
    }

    @PutMapping("/venues/{venueId}/default-layout")
    public Result<SeatCraftLayoutDtos.LayoutResponse> updateVenueDefaultLayout(@PathVariable Long venueId,
                                                                                  @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                                  @RequestBody SeatCraftLayoutDtos.LayoutSaveRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) return Result.fail(400, "\u5ea7\u4f4d\u56fe\u53c2\u6570\u4e0d\u80fd\u4e3a\u7a7a");
        request.setUserId(operatorId);
        return Result.success(venueDefaultLayoutService.saveLayout(operatorId, venueId, request.getLayout()));
    }

    @GetMapping("/venues/{venueId}/seat-layout-templates")
    public Result<List<SeatLayoutTemplateCandidateResponse>> listVenueSeatLayoutTemplates(@PathVariable Long venueId,
                                                                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                                                                            @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        return Result.success(venueApplicationService.listSeatLayoutTemplates(userId, venueId));
    }

    @PostMapping("/venues/{id}/seats")
    public Result<SeatTemplateSyncResponse> createVenueSeat(@PathVariable Long id,
                                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @RequestBody VenueSeatRequest request) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) {
            return Result.fail(400, "座位参数不能为空");
        }
        request.setUserId(userId);
        request.setVenueId(id);
        seatTemplateService.createSeat(request);
        return Result.success(sessionSeatService.syncVenueSessions(id));
    }

    @PutMapping("/venue-seats/{seatId}")
    public Result<SeatTemplateSyncResponse> updateVenueSeat(@PathVariable Long seatId,
                                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @RequestBody VenueSeatRequest request) {
        Long userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        if (request == null) {
            return Result.fail(400, "搴т綅鍙傛暟涓嶈兘涓虹┖");
        }
        request.setUserId(userId);
        VenueSeat seat = seatTemplateService.updateSeat(seatId, request);
        return Result.success(sessionSeatService.syncVenueSessions(seat.getVenueId()));
    }

    @DeleteMapping("/venue-seats/{seatId}")
    public Result<SeatTemplateSyncResponse> deleteVenueSeat(@PathVariable Long seatId,
                                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                                            @RequestParam(required = false) Long userId) {
        userId = parseOperatorId(authorization);
        if (userId == null) return Result.fail(ResultCode.UNAUTHORIZED);
        VenueSeat seat = seatTemplateService.deleteSeat(userId, seatId);
        sessionSeatService.disableAvailableSeatsByVenueSeatId(seat.getId());
        return Result.success(sessionSeatService.syncVenueSessions(seat.getVenueId()));
    }

    @PostMapping("/venue-applications")
    public Result<VenueApplicationResponse> submitVenueApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) VenueApplicationRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new VenueApplicationRequest();
        }
        request.setUserId(operatorId);
        return Result.success(venueApplicationService.toResponse(venueApplicationService.submit(request)));
    }

    @GetMapping("/venue-applications/my")
    public Result<List<VenueApplicationResponse>> listMyVenueApplications(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(venueApplicationService.listMine(operatorId));
    }

    public Result<List<VenueApplicationResponse>> listVenueApplications(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Integer status) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(venueApplicationService.listAdmin(operatorId, status));
    }

    @GetMapping("/venue-applications")
    public Result<Page<VenueApplicationResponse>> listVenueApplicationsPaged(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String capacityScale,
            @RequestParam(required = false) String materialCompleteness) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        return Result.success(venueApplicationService.listAdminPage(
                operatorId, page, size, keyword, status, city, capacityScale, materialCompleteness));
    }

    @PostMapping("/venue-applications/{id}/review")
    public Result<VenueApplicationResponse> reviewVenueApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) VenueApplicationReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        if (request == null) {
            request = new VenueApplicationReviewRequest();
        }
        request.setUserId(operatorId);
        VenueApplication application;
        if ("reject".equals(request.getAction())) {
            application = venueApplicationService.reject(id, operatorId, request.getReviewNote());
        } else {
            application = venueApplicationService.approve(id, operatorId, request.getMode(), request.getVenueId(), request.getReviewNote());
        }
        return Result.success(venueApplicationService.toResponse(application));
    }

    @PostMapping("/venue-applications/{id}/approve")
    public Result<VenueApplicationResponse> approveVenueApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) VenueApplicationReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String reviewNote = request == null ? null : request.getReviewNote();
        return Result.success(venueApplicationService.toResponse(
                venueApplicationService.approve(id, operatorId, null, null, reviewNote)));
    }

    @PostMapping("/venue-applications/{id}/reject")
    public Result<VenueApplicationResponse> rejectVenueApplication(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestBody(required = false) VenueApplicationReviewRequest request) {
        Long operatorId = parseOperatorId(authorization);
        if (operatorId == null) {
            return Result.fail(ResultCode.UNAUTHORIZED);
        }
        String reviewNote = request == null ? null : request.getReviewNote();
        return Result.success(venueApplicationService.toResponse(
                venueApplicationService.reject(id, operatorId, reviewNote)));
    }
}
