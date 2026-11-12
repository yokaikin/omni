package com.omni.user.controller;

import com.omni.common.storage.AssetStorageService;
import com.omni.common.storage.DisabledObjectStorageClient;
import com.omni.common.storage.DisabledVirusScanner;
import com.omni.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadResourceControllerTest {

    @Test
    void servesLocalFileWithContentType() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-upload-serve-test");
        byte[] content = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3};
        Path target = uploadRoot.resolve("user/avatar/x.png");
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        AssetStorageService storageService = new AssetStorageService(
                new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);
        UploadResourceController controller = new UploadResourceController(storageService, uploadRoot.toString());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/uploads/user/avatar/x.png");
        request.setRequestURI("/uploads/user/avatar/x.png");

        ResponseEntity<InputStreamResource> response = controller.serve(request);

        assertEquals(200, response.getStatusCodeValue());
        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertArrayEquals(content, response.getBody().getInputStream().readAllBytes());
    }

    @Test
    void returnsNotFoundWhenFileMissingAndObjectStorageDisabled() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-upload-serve-test");
        AssetStorageService storageService = new AssetStorageService(
                new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);
        UploadResourceController controller = new UploadResourceController(storageService, uploadRoot.toString());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/uploads/user/avatar/missing.png");
        request.setRequestURI("/uploads/user/avatar/missing.png");

        ResponseEntity<InputStreamResource> response = controller.serve(request);

        assertEquals(404, response.getStatusCodeValue());
    }

    @Test
    void rejectsTraversalPath() throws Exception {
        Path uploadRoot = Files.createTempDirectory("omni-upload-serve-test");
        AssetStorageService storageService = new AssetStorageService(
                new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);
        UploadResourceController controller = new UploadResourceController(storageService, uploadRoot.toString());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/uploads/user/../../etc/passwd");
        request.setRequestURI("/uploads/user/../../etc/passwd");

        assertThrows(BusinessException.class, () -> controller.serve(request));
    }
}
