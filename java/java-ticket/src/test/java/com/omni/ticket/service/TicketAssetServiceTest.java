package com.omni.ticket.service;

import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.DisabledObjectStorageClient;
import com.omni.common.storage.DisabledVirusScanner;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.AssetUploadResponse;
import com.omni.ticket.entity.TicketAsset;
import com.omni.ticket.mapper.TicketAssetMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TicketAssetServiceTest {

    private static final Long USER_ID = 2003L;

    private final TicketAssetMapper ticketAssetMapper = mock(TicketAssetMapper.class);

    @Test
    void uploadActivityPosterStoresImageAsset() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        when(ticketAssetMapper.insert(any(TicketAsset.class))).thenAnswer(invocation -> {
            TicketAsset asset = invocation.getArgument(0);
            asset.setId(9101L);
            return 1;
        });
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "poster.png",
                "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3}
        );

        AssetUploadResponse response = service.upload(USER_ID, "activity-poster", file);

        assertEquals(9101L, response.getId());
        assertEquals("activity-poster", response.getBizType());
        assertEquals("poster.png", response.getOriginalName());
        assertEquals("image/png", response.getMimeType());
        assertEquals(file.getSize(), response.getSizeBytes());
        assertNotNull(response.getPublicUrl());
        assertTrue(response.getPublicUrl().startsWith("/uploads/ticket/activity-poster/"));
        assertTrue(response.getPublicUrl().endsWith(".png"));
        assertTrue(Files.exists(uploadRoot.resolve(response.getPublicUrl().substring("/uploads/".length()))));
        verify(ticketAssetMapper).insert(any(TicketAsset.class));
    }

    @Test
    void uploadRejectsVenueProofBizType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "proof.png",
                "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "venue-proof", file));

        assertEquals("不支持的资产类型", exception.getMessage());
        verifyNoInteractions(ticketAssetMapper);
    }

    @Test
    void uploadRejectsUnsupportedBizType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "poster.png",
                "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "activity-banner", file));

        assertEquals("不支持的资产类型", exception.getMessage());
        verifyNoInteractions(ticketAssetMapper);
    }

    @Test
    void uploadRejectsInvalidFileType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "poster.txt",
                "text/plain",
                "plain text".getBytes()
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "activity-poster", file));

        assertEquals("该资产类型仅支持 JPG、PNG、WEBP 或 GIF 图片", exception.getMessage());
        verify(ticketAssetMapper, never()).insert(any());
    }

    @Test
    void uploadRejectsForgedImageMimeContent() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "poster.png",
                "image/png",
                "not-an-image".getBytes()
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "activity-poster", file));

        assertEquals("文件内容不是有效图片", exception.getMessage());
        verifyNoInteractions(ticketAssetMapper);
    }

    @Test
    void uploadRejectsForgedPdfMimeContent() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "proof.pdf",
                "application/pdf",
                "not-a-pdf".getBytes()
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "venue-proof", file));

        assertEquals("不支持的资产类型", exception.getMessage());
        verifyNoInteractions(ticketAssetMapper);
    }

    @Test
    void uploadRejectsPdfForPosterBizType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "poster.pdf",
                "application/pdf",
                "%PDF-1.7\ncontent".getBytes()
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "activity-poster", file));

        assertEquals("该资产类型仅支持 JPG、PNG、WEBP 或 GIF 图片", exception.getMessage());
        verifyNoInteractions(ticketAssetMapper);
    }

    @Test
    void uploadRejectsPdfForVenueProofBizType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "proof.pdf",
                "application/pdf",
                "%PDF-1.7\ncontent".getBytes()
        );

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(USER_ID, "venue-proof", file));

        assertEquals("不支持的资产类型", exception.getMessage());
        verifyNoInteractions(ticketAssetMapper);
    }

    @Test
    void uploadDeletesStoredFileWhenDatabaseInsertFails() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-ticket-asset-test");
        when(ticketAssetMapper.insert(any(TicketAsset.class))).thenThrow(new RuntimeException("db down"));
        TicketAssetService service = new TicketAssetService(ticketAssetMapper, new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true), uploadRoot.toString());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "poster.gif",
                "image/gif",
                new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 2, 3}
        );

        assertThrows(RuntimeException.class, () -> service.upload(USER_ID, "activity-poster", file));

        assertEquals(0L, Files.walk(uploadRoot).filter(Files::isRegularFile).count());
    }
}
