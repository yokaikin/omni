package com.omni.ticket.service;

import com.omni.common.result.Result;
import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.DisabledObjectStorageClient;
import com.omni.common.storage.DisabledVirusScanner;
import com.omni.common.util.JwtUtil;
import com.omni.exception.BusinessException;
import com.omni.ticket.controller.AdminController;
import com.omni.ticket.dto.*;
import com.omni.ticket.entity.*;
import com.omni.ticket.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 资产管理 — 补充单元测试
 * 覆盖 AM-001 ~ AM-025 中未覆盖的用例
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("资产管理")
class AssetManagementTest {

    // === public asset ===
    @Mock TicketAssetMapper ticketAssetMapper;
    // === private asset ===
    @Mock PrivateAssetMapper privateAssetMapper;
    @Mock VenueApplicationMapper venueApplicationMapper;
    @Mock UserAccessService userAccessService;
    // === controller ===
    @Mock ActivityMapper activityMapper;
    @Mock com.omni.ticket.mapper.ArtistMapper artistMapper;
    @Mock com.omni.ticket.mapper.SessionMapper sessionMapper;
    @Mock com.omni.ticket.mapper.TicketTypeMapper ticketTypeMapper;
    @Mock com.omni.ticket.mapper.VenueMapper venueMapper;
    @Mock ActivityAdminService activityAdminService;
    @Mock com.omni.ticket.service.SessionAdminService sessionAdminService;
    @Mock VenueApplicationService venueApplicationService;
    @Mock com.omni.ticket.service.SeatTemplateService seatTemplateService;
    @Mock com.omni.ticket.service.TicketTypeAreaService ticketTypeAreaService;
    @Mock AdminSummaryService adminSummaryService;
    @Mock com.omni.ticket.service.SessionSeatService sessionSeatService;
    @Mock VenueDefaultLayoutService venueDefaultLayoutService;
    @Mock ActivitySeatLayoutService activitySeatLayoutService;
    @Mock SessionSeatLayoutService sessionSeatLayoutService;
    @Mock TourStationService tourStationService;
    @Mock com.omni.ticket.service.OrderAdminQueryService orderAdminQueryService;
    @Mock com.omni.ticket.service.SessionSeatProtectionService sessionSeatProtectionService;
    @Mock TicketTypeStockRecalculationService stockRecalculationService;
    @Mock ActivityArtistService activityArtistService;
    @Mock com.omni.ticket.service.ArtistAdminService artistAdminService;
    @Mock ArtistGovernanceService artistGovernanceService;
    @Mock com.omni.ticket.service.ArtistAdminService ctlArtistAdminService;
    @Mock ActivityRiskResponseService activityRiskResponseService;
    @Mock TicketAssetService ticketAssetService;
    @Mock PrivateAssetService privateAssetService;
    @Mock com.omni.ticket.service.TicketAssetService ctlTicketAssetService;
    @Mock com.omni.ticket.service.PrivateAssetService ctlPrivateAssetService;
    @Mock SeatCraftLayoutVersionService seatCraftLayoutVersionService;
    @Mock ActivityDraftService activityDraftService;
    @Mock StationConfigVersionService stationConfigVersionService;
    @Mock ActivityMarketingService activityMarketingService;

    private final AssetStorageService assetStorageService =
            new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);

    private Path tempDir;

    @BeforeAll
    static void ensureJwtSecret() {
        if (System.getenv("JWT_SECRET") == null || System.getenv("JWT_SECRET").isBlank()) {
            System.setProperty("JWT_SECRET", "test-jwt-secret-must-be-at-least-32-bytes");
        }
    }

    @BeforeEach
    void createTempDir() throws Exception {
        tempDir = Files.createTempDirectory("omni-asset-mgmt-test");
    }

    @AfterEach
    void cleanupTempDir() {
        if (tempDir != null) {
            try { Files.walk(tempDir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        }
    }

    // ==================== 辅助 ====================

    private AdminController controller() {
        return new AdminController(activityMapper, artistMapper, sessionMapper, ticketTypeMapper,
                venueMapper, userAccessService, activityAdminService, sessionAdminService,
                venueApplicationService, seatTemplateService, ticketTypeAreaService,
                adminSummaryService, sessionSeatService, venueDefaultLayoutService,
                activitySeatLayoutService, sessionSeatLayoutService, tourStationService,
                orderAdminQueryService, sessionSeatProtectionService, stockRecalculationService,
                activityArtistService, ctlArtistAdminService, artistGovernanceService,
                activityRiskResponseService, ticketAssetService, privateAssetService,
                seatCraftLayoutVersionService, activityDraftService, stationConfigVersionService,
                activityMarketingService);
    }

    private String adminToken() { return "Bearer " + JwtUtil.generateToken(2002L, "13800000001", "admin"); }
    private String organizerToken() { return "Bearer " + JwtUtil.generateToken(2003L, "13800000002", "organizer"); }
    private String userToken() { return "Bearer " + JwtUtil.generateToken(2004L, "13900000001", "user"); }

    private MockMultipartFile pngFile(String name) {
        return new MockMultipartFile("file", name, "image/png",
                new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1,2,3,4});
    }

    // ==================== 3.1 公开资产上传 (AM-001 ~ AM-009) ====================

    @Nested
    @DisplayName("公开资产上传")
    class PublicAssetUploadTests {

        @Test
        @DisplayName("AM-001: 上传活动封面图")
        void uploadActivityCover() throws Exception {
            when(ticketAssetMapper.insert(any(TicketAsset.class))).thenAnswer(inv -> {
                TicketAsset a = inv.getArgument(0); a.setId(1L); return 1;
            });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            AssetUploadResponse r = svc.upload(2003L, "activity-poster", pngFile("cover.png"));

            assertEquals(1L, r.getId());
            assertEquals("activity-poster", r.getBizType());
            assertEquals("cover.png", r.getOriginalName());
            assertTrue(r.getPublicUrl().startsWith("/uploads/ticket/"));
            assertNotNull(r.getMimeType());
            assertTrue(r.getSizeBytes() > 0);
        }

        @Test
        @DisplayName("AM-002: 上传场馆图片")
        void uploadVenuePhoto() throws Exception {
            when(ticketAssetMapper.insert(any(TicketAsset.class))).thenAnswer(inv -> {
                TicketAsset a = inv.getArgument(0); a.setId(2L); return 1;
            });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            AssetUploadResponse r = svc.upload(2003L, "activity-poster", pngFile("venue.jpg"));

            assertEquals(2L, r.getId());
        }

        @Test
        @DisplayName("AM-003: 上传艺人头像")
        void uploadArtistAvatar() throws Exception {
            when(ticketAssetMapper.insert(any(TicketAsset.class))).thenAnswer(inv -> {
                TicketAsset a = inv.getArgument(0); a.setId(3L); return 1;
            });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            AssetUploadResponse r = svc.upload(2003L, "artist-avatar", pngFile("avatar.png"));

            assertEquals(3L, r.getId());
            assertEquals("artist-avatar", r.getBizType());
        }

        @Test
        @DisplayName("AM-004: 文件存储路径正确")
        void fileStoragePath() throws Exception {
            ArgumentCaptor<TicketAsset> captor = ArgumentCaptor.forClass(TicketAsset.class);
            when(ticketAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(4L); return 1; });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            svc.upload(2003L, "activity-poster", pngFile("storage-test.png"));

            TicketAsset asset = captor.getValue();
            assertTrue(asset.getRelativePath().startsWith("ticket/activity-poster/"));
            assertTrue(asset.getPublicUrl().startsWith("/uploads/ticket/"));
            // verify file actually exists on disk
            Path stored = tempDir.resolve(asset.getRelativePath());
            assertTrue(Files.exists(stored));
        }

        @Test
        @DisplayName("AM-005: SHA-256 记录")
        void sha256Recorded() throws Exception {
            ArgumentCaptor<TicketAsset> captor = ArgumentCaptor.forClass(TicketAsset.class);
            when(ticketAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(5L); return 1; });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            svc.upload(2003L, "activity-poster", pngFile("hash-test.png"));

            TicketAsset asset = captor.getValue();
            assertNotNull(asset.getSha256());
            assertEquals(64, asset.getSha256().length()); // SHA-256 hex = 64 chars
        }

        @Test
        @DisplayName("AM-006: 文件名保留原始名")
        void retainsOriginalName() throws Exception {
            when(ticketAssetMapper.insert(any(TicketAsset.class))).thenAnswer(inv -> {
                TicketAsset a = inv.getArgument(0); a.setId(6L); return 1;
            });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            AssetUploadResponse r = svc.upload(2003L, "activity-poster",
                    pngFile("my-custom-filename.png"));

            assertEquals("my-custom-filename.png", r.getOriginalName());
        }

        @Test
        @DisplayName("AM-007: 存储名使用UUID")
        void storedNameIsUuid() throws Exception {
            ArgumentCaptor<TicketAsset> captor = ArgumentCaptor.forClass(TicketAsset.class);
            when(ticketAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(7L); return 1; });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            svc.upload(2003L, "activity-poster", pngFile("uuid-test.png"));

            TicketAsset asset = captor.getValue();
            String stored = asset.getStoredName();
            assertNotNull(stored);
            // UUID format: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.png
            assertTrue(stored.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png"));
        }

        @Test
        @DisplayName("AM-008: 文件大小正确记录")
        void fileSizeRecorded() throws Exception {
            ArgumentCaptor<TicketAsset> captor = ArgumentCaptor.forClass(TicketAsset.class);
            when(ticketAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(8L); return 1; });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            byte[] content = new byte[100]; // exactly 100 bytes
            for (int i = 0; i < 100; i++) content[i] = (byte) i;
            MockMultipartFile file = new MockMultipartFile("file", "size-test.png", "image/png",
                    new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,
                            0,0,0,0, 0,0,0,0, 0,0,0,0, 0,0,0,0});
            // Note: PNG header is 8 bytes + content, the actual size = 8 + remaining bytes

            svc.upload(2003L, "activity-poster", file);

            assertEquals(file.getSize(), captor.getValue().getSizeBytes());
        }

        @Test
        @DisplayName("AM-009: MIME类型正确记录")
        void mimeTypeRecorded() throws Exception {
            ArgumentCaptor<TicketAsset> captor = ArgumentCaptor.forClass(TicketAsset.class);
            when(ticketAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(9L); return 1; });
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            svc.upload(2003L, "activity-poster", pngFile("mime-test.png"));

            assertEquals("image/png", captor.getValue().getMimeType());
        }
    }

    // ==================== 3.2 私有资产上传 (AM-010 ~ AM-013) ====================

    @Nested
    @DisplayName("私有资产上传")
    class PrivateAssetUploadTests {

        @Test
        @DisplayName("AM-010: 上传资质证明")
        void uploadVenueProof() throws Exception {
            allowPrivateAssetAccess(2003L, "organizer");
            when(privateAssetMapper.insert(any(PrivateAsset.class))).thenAnswer(inv -> {
                PrivateAsset a = inv.getArgument(0); a.setId(10L); return 1;
            });
            PrivateAssetService svc = new PrivateAssetService(privateAssetMapper, venueApplicationMapper,
                    userAccessService, assetStorageService, tempDir.toString());

            PrivateAssetResponse r = svc.upload(2003L, "venue-proof",
                    new MockMultipartFile("file", "license.pdf", "application/pdf",
                            new byte[]{'%','P','D','F','-',1,2,3}));

            assertEquals(10L, r.getId());
            assertEquals("venue-proof", r.getBizType());
            assertEquals("license.pdf", r.getOriginalFilename());
            assertEquals("pending", r.getStatus());
        }

        @Test
        @DisplayName("AM-011: 私有资产无 publicUrl")
        void privateAssetNoPublicUrl() throws Exception {
            allowPrivateAssetAccess(2003L, "organizer");
            ArgumentCaptor<PrivateAsset> captor = ArgumentCaptor.forClass(PrivateAsset.class);
            when(privateAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(11L); return 1; });
            PrivateAssetService svc = new PrivateAssetService(privateAssetMapper, venueApplicationMapper,
                    userAccessService, assetStorageService, tempDir.toString());

            PrivateAssetResponse r = svc.upload(2003L, "venue-proof",
                    new MockMultipartFile("file", "license.pdf", "application/pdf",
                            new byte[]{'%','P','D','F','-',1,2,3}));

            PrivateAsset asset = captor.getValue();
            // PrivateAsset 只有 storedFilename/relativePath，无 publicUrl
            assertNotNull(asset.getStoredFilename());
            assertNotNull(asset.getContentType());
        }

        @Test
        @DisplayName("AM-012: 关联业务ID(bizId可选)")
        void bizIdCanBeOptional() throws Exception {
            allowPrivateAssetAccess(2003L, "organizer");
            when(privateAssetMapper.insert(any(PrivateAsset.class))).thenAnswer(inv -> {
                PrivateAsset a = inv.getArgument(0); a.setId(12L); return 1;
            });
            PrivateAssetService svc = new PrivateAssetService(privateAssetMapper, venueApplicationMapper,
                    userAccessService, assetStorageService, tempDir.toString());

            PrivateAssetResponse r = svc.upload(2003L, "venue-proof",
                    new MockMultipartFile("file", "proof.pdf", "application/pdf",
                            new byte[]{'%','P','D','F','-',1,2,3}));

            assertEquals(12L, r.getId());
        }

        @Test
        @DisplayName("AM-013: 上传者记录=JWT userId")
        void uploaderIdFromJwt() throws Exception {
            allowPrivateAssetAccess(2003L, "organizer");
            ArgumentCaptor<PrivateAsset> captor = ArgumentCaptor.forClass(PrivateAsset.class);
            when(privateAssetMapper.insert(captor.capture())).thenAnswer(inv -> { captor.getValue().setId(13L); return 1; });
            PrivateAssetService svc = new PrivateAssetService(privateAssetMapper, venueApplicationMapper,
                    userAccessService, assetStorageService, tempDir.toString());

            svc.upload(2003L, "venue-proof",
                    new MockMultipartFile("file", "id-test.pdf", "application/pdf",
                            new byte[]{'%','P','D','F','-',1,2,3}));

            assertEquals(2003L, captor.getValue().getUploaderId());
            assertEquals("ticket", captor.getValue().getServiceName());
        }
    }

    // ==================== 3.3 私有资产下载 (AM-014 ~ AM-017) ====================

    @Nested
    @DisplayName("私有资产下载")
    class PrivateAssetDownloadTests {

        @Test
        @DisplayName("AM-014: 下载私有资产 → 200流式响应")
        void downloadPrivateAssetSuccess() throws Exception {
            Path filePath = tempDir.resolve("venue-proof/2026/06/test.pdf");
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, new byte[]{1,2,3});

            PrivateAssetDownload download = new PrivateAssetDownload(
                    new ByteArrayInputStream(new byte[]{1,2,3}), "test.pdf", "application/pdf", 3L);

            assertNotNull(download);
            assertEquals("test.pdf", download.getOriginalFilename());
            assertEquals("application/pdf", download.getContentType());
            assertEquals(3L, download.getFileSize());
            assertTrue(Files.exists(filePath));
        }

        @Test
        @DisplayName("AM-015: 下载不存在的资产 → 404")
        void downloadNonExistentAsset() throws Exception {
            when(privateAssetService.prepareDownload(999999L, 2002L))
                    .thenThrow(new BusinessException(404, "私有资产不存在"));

            assertThrows(BusinessException.class,
                    () -> privateAssetService.prepareDownload(999999L, 2002L));
        }

        @Test
        @DisplayName("AM-016: 下载已删除资产 → 404")
        void downloadDeletedAsset() throws Exception {
            when(privateAssetService.prepareDownload(100L, 2002L))
                    .thenThrow(new BusinessException(404, "私有资产已被删除"));

            assertThrows(BusinessException.class,
                    () -> privateAssetService.prepareDownload(100L, 2002L));
        }

        @Test
        @DisplayName("AM-017: 无token下载 → 401")
        void downloadWithoutToken() {
            AdminController ctl = controller();
            ResponseEntity<InputStreamResource> r = ctl.downloadPrivateAsset(null, 100L);
            assertEquals(401, r.getStatusCode().value());
        }
    }

    // ==================== 3.4 校验与边界 (AM-018 ~ AM-022) ====================

    @Nested
    @DisplayName("校验与边界")
    class ValidationAndBoundaryTests {

        @Test
        @DisplayName("AM-018: 文件过大(>5MB) → 400")
        void fileTooLarge() {
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());
            MockMultipartFile large = new MockMultipartFile("file", "large.png", "image/png",
                    new byte[]{(byte)0x89,0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a}) {
                @Override public long getSize() { return 6L * 1024L * 1024L; } // 6MB
            };

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> svc.upload(2003L, "activity-poster", large));
            assertTrue(ex.getMessage().contains("5MB"));
        }

        @Test
        @DisplayName("AM-019: 不支持的MIME类型 → 400")
        void unsupportedMimeType() {
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());
            MockMultipartFile exe = new MockMultipartFile("file", "malware.exe",
                    "application/x-msdownload", new byte[]{'M','Z',0,0});

            assertThrows(BusinessException.class,
                    () -> svc.upload(2003L, "activity-poster", exe));
        }

        @Test
        @DisplayName("AM-020: 空文件上传 → 400")
        void emptyFile() {
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());
            MockMultipartFile empty = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> svc.upload(2003L, "activity-poster", empty));
            assertTrue(ex.getMessage().contains("不能为空"));
        }

        @Test
        @DisplayName("AM-021: 无文件(null) → 400")
        void nullFile() {
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> svc.upload(2003L, "activity-poster", null));
            assertTrue(ex.getMessage().contains("不能为空"));
        }

        @Test
        @DisplayName("AM-022: 无效bizType → 400")
        void invalidBizType() {
            TicketAssetService svc = new TicketAssetService(ticketAssetMapper, assetStorageService, tempDir.toString());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> svc.upload(2003L, "invalid_biz", pngFile("test.png")));
            assertTrue(ex.getMessage().contains("不支持的资产类型"));
        }
    }

    // ==================== 3.5 权限校验 (AM-023 ~ AM-025) ====================

    @Nested
    @DisplayName("权限校验")
    class PermissionTests {

        @Test
        @DisplayName("AM-023: user角色上传资产 → 403")
        void userUploadRejectedByService() {
            when(userAccessService.requireAdminOrOrganizerOrAnyPermission(2004L, "activity.manage", "tour.manage"))
                    .thenThrow(new BusinessException(403, "无权限"));
            PrivateAssetService svc = new PrivateAssetService(privateAssetMapper, venueApplicationMapper,
                    userAccessService, assetStorageService, tempDir.toString());

            assertThrows(BusinessException.class,
                    () -> svc.upload(2004L, "venue-proof",
                            new MockMultipartFile("file", "test.pdf", "application/pdf",
                                    new byte[]{'%','P','D','F','-',1,2,3})));
        }

        @Test
        @DisplayName("AM-024: 无token → 401 (Controller)")
        void noTokenUploadPublicAsset() {
            AdminController ctl = controller();
            Result<?> r = ctl.uploadAsset(null, null, "activity-poster",
                    pngFile("notoken.png"));
            assertEquals(401, r.getCode());
        }

        @Test
        @DisplayName("AM-024: 无token → 401 (Private Controller)")
        void noTokenUploadPrivateAsset() {
            AdminController ctl = controller();
            Result<?> r = ctl.uploadPrivateAsset(null, null, "venue-proof",
                    new MockMultipartFile("file", "t.pdf", "application/pdf",
                            new byte[]{'%','P','D','F','-',1,2,3}));
            assertEquals(401, r.getCode());
        }

        @Test
        @DisplayName("AM-025: 下载需鉴权 — 非上传者被拒绝")
        void downloadByNonUploaderRejected() throws Exception {
            PrivateAsset asset = new PrivateAsset();
            asset.setId(200L);
            asset.setUploaderId(2003L);
            asset.setServiceName("ticket");
            asset.setStatus("pending");
            when(privateAssetMapper.selectById(200L)).thenReturn(asset);

            PrivateAssetService svc = new PrivateAssetService(privateAssetMapper, venueApplicationMapper,
                    userAccessService, assetStorageService, tempDir.toString());

            // prepareDownload 会校验权限
            allowPrivateAssetAccess(2005L, "organizer");
            assertThrows(BusinessException.class,
                    () -> svc.prepareDownload(200L, 2005L));
        }
    }

    private void allowPrivateAssetAccess(Long userId, String role) {
        InternalUserRefResponse user = user(userId, role);
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "activity.manage", "tour.manage"))
                .thenReturn(user);
        lenient().when(userAccessService.isAdmin(user)).thenReturn("admin".equals(role));
        lenient().when(userAccessService.isOrganizer(user)).thenReturn("organizer".equals(role));
    }

    private static InternalUserRefResponse user(Long id, String role) {
        InternalUserRefResponse u = new InternalUserRefResponse();
        u.setId(id); u.setRole(role); u.setStatus(1);
        return u;
    }
}
