package com.omni.ticket.service;

import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.DisabledObjectStorageClient;
import com.omni.common.storage.DisabledVirusScanner;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.InternalUserRefResponse;
import com.omni.ticket.dto.PrivateAssetDownload;
import com.omni.ticket.dto.PrivateAssetResponse;
import com.omni.ticket.entity.PrivateAsset;
import com.omni.ticket.entity.VenueApplication;
import com.omni.ticket.mapper.PrivateAssetMapper;
import com.omni.ticket.mapper.VenueApplicationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class PrivateAssetServiceTest {

    private static final Long ADMIN_ID = 2002L;
    private static final Long APPLICANT_ID = 2003L;
    private static final Long OTHER_ORGANIZER_ID = 2005L;

    @Mock
    private PrivateAssetMapper privateAssetMapper;
    @Mock
    private VenueApplicationMapper venueApplicationMapper;
    @Mock
    private UserAccessService userAccessService;

    private final AssetStorageService assetStorageService =
            new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);

    @Test
    void uploadVenueProofStoresPrivatePendingAsset() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        allowAssetAccess(APPLICANT_ID, "organizer");
        when(privateAssetMapper.insert(any(PrivateAsset.class))).thenAnswer(invocation -> {
            PrivateAsset asset = invocation.getArgument(0);
            asset.setId(9101L);
            return 1;
        });
        PrivateAssetService service = service(privateRoot);
        MockMultipartFile file = pdfFile();

        PrivateAssetResponse response = service.upload(APPLICANT_ID, "venue-proof", file);

        assertEquals(9101L, response.getId());
        assertEquals("venue-proof", response.getBizType());
        assertEquals("proof.pdf", response.getOriginalFilename());
        assertEquals("application/pdf", response.getContentType());
        assertEquals(file.getSize(), response.getFileSize());
        assertEquals("pending", response.getStatus());

        ArgumentCaptor<PrivateAsset> captor = ArgumentCaptor.forClass(PrivateAsset.class);
        verify(privateAssetMapper).insert(captor.capture());
        PrivateAsset saved = captor.getValue();
        assertEquals("ticket", saved.getServiceName());
        assertEquals("venue-proof", saved.getBizType());
        assertEquals(APPLICANT_ID, saved.getUploaderId());
        assertEquals("pending", saved.getStatus());
        assertNotNull(saved.getSha256());
        assertTrue(saved.getRelativePath().startsWith("venue-proof/"));
        assertTrue(Files.exists(privateRoot.resolve(saved.getRelativePath())));
    }

    @Test
    void uploadRejectsUnsupportedBizType() throws Exception {
        PrivateAssetService service = service(Files.createTempDirectory("omni-private-asset-test"));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload(APPLICANT_ID, "activity-poster", pngFile()));

        assertEquals(400, error.getCode());
        assertEquals("不支持的私有资产类型", error.getMessage());
        verifyNoInteractions(privateAssetMapper);
    }

    @Test
    void uploadRejectsUnsupportedFileType() throws Exception {
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(Files.createTempDirectory("omni-private-asset-test"));
        MockMultipartFile file = new MockMultipartFile("file", "proof.txt", "text/plain", "plain".getBytes());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload(APPLICANT_ID, "venue-proof", file));

        assertEquals(400, error.getCode());
        assertEquals("场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件", error.getMessage());
        verify(privateAssetMapper, never()).insert(any());
    }

    @Test
    void uploadRejectsMismatchedOriginalExtension() throws Exception {
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(Files.createTempDirectory("omni-private-asset-test"));
        MockMultipartFile file = new MockMultipartFile("file", "proof.txt", "application/pdf", "%PDF-1.7\ncontent".getBytes());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload(APPLICANT_ID, "venue-proof", file));

        assertEquals(400, error.getCode());
        assertEquals("场地证明仅支持 PDF、JPG、PNG 或 WEBP 文件", error.getMessage());
        verify(privateAssetMapper, never()).insert(any());
    }

    @Test
    void uploadRejectsFileLargerThan20Mb() throws Exception {
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(Files.createTempDirectory("omni-private-asset-test"));
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf", new byte[20 * 1024 * 1024 + 1]);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.upload(APPLICANT_ID, "venue-proof", file));

        assertEquals(400, error.getCode());
        assertEquals("场地证明文件不能超过20MB", error.getMessage());
        verify(privateAssetMapper, never()).insert(any());
    }

    @Test
    void bindVenueProofMarksPendingAssetAsBound() throws Exception {
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, Files.createTempDirectory("omni-private-asset-test"));
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        PrivateAssetService service = service(Files.createTempDirectory("omni-private-asset-test"));

        PrivateAssetResponse response = service.bindVenueProof(9101L, 301L, APPLICANT_ID);

        assertEquals(301L, response.getBizId());
        assertEquals("bound", response.getStatus());
        assertEquals(301L, asset.getBizId());
        assertEquals("bound", asset.getStatus());
        assertNotNull(asset.getBindTime());
        verify(privateAssetMapper).updateById(asset);
    }

    @Test
    void getByIdReturnsAssetResponse() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        PrivateAssetService service = service(privateRoot);

        PrivateAssetResponse response = service.getById(9101L);

        assertEquals(9101L, response.getId());
        assertEquals("venue-proof", response.getBizType());
        assertEquals("pending", response.getStatus());
    }

    @Test
    void prepareDownloadAllowsPendingUploader() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(privateRoot);

        PrivateAssetDownload download = service.prepareDownload(9101L, APPLICANT_ID);

        assertEquals("%PDF-1.7\ncontent", new String(download.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("proof.pdf", download.getOriginalFilename());
        assertEquals("application/pdf", download.getContentType());
        assertEquals(asset.getFileSize().longValue(), download.getFileSize());
    }

    @Test
    void prepareDownloadAllowsPendingAdminForReview() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(ADMIN_ID, "admin");
        PrivateAssetService service = service(privateRoot);

        PrivateAssetDownload download = service.prepareDownload(9101L, ADMIN_ID);

        assertEquals("%PDF-1.7\ncontent", new String(download.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void prepareDownloadRejectsPendingNonUploader() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(OTHER_ORGANIZER_ID, "organizer");
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.prepareDownload(9101L, OTHER_ORGANIZER_ID));

        assertEquals(403, error.getCode());
    }

    @Test
    void prepareDownloadAllowsBoundVenueProofAdminOrApplicant() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = boundAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(ADMIN_ID, "admin");
        allowAssetAccess(APPLICANT_ID, "organizer");
        when(venueApplicationMapper.selectById(301L)).thenReturn(venueApplication(301L, APPLICANT_ID));
        PrivateAssetService service = service(privateRoot);

        assertEquals("%PDF-1.7\ncontent", new String(service.prepareDownload(9101L, ADMIN_ID).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("%PDF-1.7\ncontent", new String(service.prepareDownload(9101L, APPLICANT_ID).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void prepareDownloadRejectsBoundVenueProofOtherOrganizer() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = boundAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(OTHER_ORGANIZER_ID, "organizer");
        when(venueApplicationMapper.selectById(301L)).thenReturn(venueApplication(301L, APPLICANT_ID));
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.prepareDownload(9101L, OTHER_ORGANIZER_ID));

        assertEquals(403, error.getCode());
    }

    @Test
    void prepareDownloadAllowsBoundVenueProofApplicantFromVenueApplicationEvenWhenNotUploader() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = boundAsset(9101L, OTHER_ORGANIZER_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(APPLICANT_ID, "organizer");
        when(venueApplicationMapper.selectById(301L)).thenReturn(venueApplication(301L, APPLICANT_ID));
        PrivateAssetService service = service(privateRoot);

        assertEquals("%PDF-1.7\ncontent", new String(service.prepareDownload(9101L, APPLICANT_ID).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void prepareDownloadRejectsBoundVenueProofUploaderWhenNotVenueApplicationApplicant() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = boundAsset(9101L, OTHER_ORGANIZER_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(OTHER_ORGANIZER_ID, "organizer");
        when(venueApplicationMapper.selectById(301L)).thenReturn(venueApplication(301L, APPLICANT_ID));
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.prepareDownload(9101L, OTHER_ORGANIZER_ID));

        assertEquals(403, error.getCode());
    }

    @Test
    void getByIdReturns404ForDeletedStatus() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        asset.setStatus("deleted");
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class, () -> service.getById(9101L));

        assertEquals(404, error.getCode());
    }

    @Test
    void prepareDownloadReturns404WhenDeletedAtIsSet() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        asset.setDeletedAt(LocalDateTime.now());
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.prepareDownload(9101L, APPLICANT_ID));

        assertEquals(404, error.getCode());
    }

    @Test
    void defaultPrivateRootUsesProjectRuntimePrivateUploadsWhenStartedFromServiceDirectory() throws Exception {
        String previousUserDir = System.getProperty("user.dir");
        Path projectRoot = findProjectRoot(Paths.get(previousUserDir).toAbsolutePath().normalize());
        Path expectedRoot = projectRoot.resolve("runtime/private-uploads/ticket");
        try {
            System.setProperty("user.dir", projectRoot.resolve("java/java-ticket").toString());
            allowAssetAccess(APPLICANT_ID, "organizer");
            when(privateAssetMapper.insert(any(PrivateAsset.class))).thenAnswer(invocation -> {
                PrivateAsset asset = invocation.getArgument(0);
                asset.setId(9102L);
                return 1;
            });
            PrivateAssetService service = new PrivateAssetService(privateAssetMapper, venueApplicationMapper, userAccessService, assetStorageService, "");

            PrivateAssetResponse response = service.upload(APPLICANT_ID, "venue-proof", pdfFile());

            ArgumentCaptor<PrivateAsset> captor = ArgumentCaptor.forClass(PrivateAsset.class);
            verify(privateAssetMapper).insert(captor.capture());
            assertEquals(9102L, response.getId());
            assertTrue(Files.exists(expectedRoot.resolve(captor.getValue().getRelativePath())));
        } finally {
            System.setProperty("user.dir", previousUserDir);
        }
    }

    @Test
    void prepareDownloadRejectsPathTraversal() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        Path outside = Files.createTempFile("omni-private-asset-outside", ".pdf");
        Files.write(outside, "%PDF-1.7\ncontent".getBytes());
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        asset.setRelativePath("../" + outside.getFileName());
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.prepareDownload(9101L, APPLICANT_ID));

        assertEquals(403, error.getCode());
    }

    @Test
    void presignedDownloadUrlThrowsWhenObjectStorageDisabled() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        when(privateAssetMapper.selectById(9101L)).thenReturn(asset);
        allowAssetAccess(APPLICANT_ID, "organizer");
        PrivateAssetService service = service(privateRoot);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.presignedDownloadUrl(9101L, APPLICANT_ID, 300));

        assertEquals(500, error.getCode());
        assertEquals("对象存储未启用，无法生成签名下载地址", error.getMessage());
    }

    @Test
    void cleanupExpiredPendingAssetsSoftDeletesAndRemovesFiles() throws Exception {
        Path privateRoot = Files.createTempDirectory("omni-private-asset-test");
        PrivateAsset asset = pendingAsset(9101L, APPLICANT_ID, privateRoot);
        asset.setCreateTime(LocalDateTime.now().minusDays(30));
        Path stored = privateRoot.resolve(asset.getRelativePath());
        assertTrue(Files.exists(stored));
        when(privateAssetMapper.selectList(any())).thenReturn(Collections.singletonList(asset));
        when(privateAssetMapper.updateById(asset)).thenReturn(1);
        PrivateAssetService service = service(privateRoot);

        int cleaned = service.cleanupExpiredPendingAssets(7);

        assertEquals(1, cleaned);
        assertEquals("deleted", asset.getStatus());
        assertNotNull(asset.getDeletedAt());
        assertTrue(!Files.exists(stored));
        verify(privateAssetMapper).updateById(asset);
    }

    private Path findProjectRoot(Path start) {
        Path cursor = start;
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("java")) && Files.isDirectory(cursor.resolve("frontend"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot find project root from " + start);
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.7\ncontent".getBytes());
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "proof.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
    }

    private PrivateAsset pendingAsset(Long id, Long uploaderId, Path privateRoot) throws Exception {
        Path file = privateRoot.resolve("venue-proof/2026/05/proof.pdf");
        Files.createDirectories(file.getParent());
        Files.write(file, "%PDF-1.7\ncontent".getBytes());

        PrivateAsset asset = new PrivateAsset();
        asset.setId(id);
        asset.setServiceName("ticket");
        asset.setBizType("venue-proof");
        asset.setUploaderId(uploaderId);
        asset.setOriginalFilename("proof.pdf");
        asset.setStoredFilename("proof.pdf");
        asset.setRelativePath("venue-proof/2026/05/proof.pdf");
        asset.setContentType("application/pdf");
        asset.setFileSize(Files.size(file));
        asset.setStatus("pending");
        return asset;
    }

    private PrivateAsset boundAsset(Long id, Long uploaderId, Path privateRoot) throws Exception {
        PrivateAsset asset = pendingAsset(id, uploaderId, privateRoot);
        asset.setBizId(301L);
        asset.setStatus("bound");
        return asset;
    }

    private InternalUserRefResponse user(Long id, String role) {
        InternalUserRefResponse user = new InternalUserRefResponse();
        user.setId(id);
        user.setRole(role);
        return user;
    }

    private void allowAssetAccess(Long userId, String role) {
        InternalUserRefResponse ref = user(userId, role);
        when(userAccessService.requireAdminOrOrganizerOrAnyPermission(userId, "activity.manage", "tour.manage"))
                .thenReturn(ref);
        lenient().when(userAccessService.isAdmin(ref)).thenReturn("admin".equals(role));
        lenient().when(userAccessService.isOrganizer(ref)).thenReturn("organizer".equals(role));
    }

    private VenueApplication venueApplication(Long id, Long applicantId) {
        VenueApplication application = new VenueApplication();
        application.setId(id);
        application.setApplicantId(applicantId);
        return application;
    }

    private PrivateAssetService service(Path privateRoot) {
        return new PrivateAssetService(privateAssetMapper, venueApplicationMapper, userAccessService, assetStorageService, privateRoot.toString());
    }
}
