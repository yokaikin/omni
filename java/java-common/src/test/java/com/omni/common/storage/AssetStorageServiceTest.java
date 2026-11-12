package com.omni.common.storage;

import com.omni.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssetStorageServiceTest {

    @TempDir
    Path localRoot;

    private static final class FakeObjectStorageClient implements ObjectStorageClient {
        final Map<String, byte[]> objects = new HashMap<>();
        boolean enabled;

        @Override
        public String putObject(String key, InputStream inputStream, long size, String contentType) {
            try {
                objects.put(key, inputStream.readAllBytes());
                return key;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public InputStream getObject(String key) {
            byte[] data = objects.get(key);
            return data == null ? null : new ByteArrayInputStream(data);
        }

        @Override
        public String presignedGetUrl(String key, int expirySeconds) {
            return "http://presigned/" + key + "?e=" + expirySeconds;
        }

        @Override
        public void removeObject(String key) {
            objects.remove(key);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }
    }

    private MockMultipartFile file(String content) {
        return new MockMultipartFile("file", "test.png", "image/png", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void storeWritesLocalFileWhenObjectStorageDisabled() throws Exception {
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);

        StoredFile stored = service.store(localRoot, "", "user/avatar/a.png", file("hello"));

        assertFalse(stored.isStoredInObjectStorage());
        assertEquals(64, stored.getSha256().length());
        assertEquals("hello", Files.readString(localRoot.resolve("user/avatar/a.png"), StandardCharsets.UTF_8));
    }

    @Test
    void storeWritesObjectStorageWhenEnabledAndSkipsLocal() {
        FakeObjectStorageClient client = new FakeObjectStorageClient();
        client.enabled = true;
        AssetStorageService service = new AssetStorageService(client, new DisabledVirusScanner(), true);

        StoredFile stored = service.store(localRoot, "private/ticket", "venue-proof/a.pdf", file("pdf-content"));

        assertTrue(stored.isStoredInObjectStorage());
        assertEquals("pdf-content", new String(client.objects.get("private/ticket/venue-proof/a.pdf"), StandardCharsets.UTF_8));
        assertEquals(0, countLocalFiles());
    }

    @Test
    void storeRejectsInfectedFile() {
        VirusScanner infectedScanner = new VirusScanner() {
            @Override
            public VirusScanResult scan(byte[] content, String filename) {
                return VirusScanResult.infected("EICAR-Test-File");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), infectedScanner, true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.store(localRoot, "", "user/avatar/a.png", file("infected")));
        assertEquals("文件未通过病毒扫描，上传已拒绝", exception.getMessage());
        assertEquals(0, countLocalFiles());
    }

    @Test
    void storeFailsOpenWhenScannerUnavailable() {
        VirusScanner brokenScanner = new VirusScanner() {
            @Override
            public VirusScanResult scan(byte[] content, String filename) {
                throw new IllegalStateException("clamd down");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), brokenScanner, true);

        StoredFile stored = service.store(localRoot, "", "user/avatar/a.png", file("clean"));
        assertNotNull(stored.getSha256());
        assertEquals(1, countLocalFiles());
    }

    @Test
    void storeFailsClosedWhenScannerUnavailableAndFailOpenDisabled() {
        VirusScanner brokenScanner = new VirusScanner() {
            @Override
            public VirusScanResult scan(byte[] content, String filename) {
                throw new IllegalStateException("clamd down");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }
        };
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), brokenScanner, false);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.store(localRoot, "", "user/avatar/a.png", file("clean")));
        assertEquals("病毒扫描服务暂不可用，请稍后重试", exception.getMessage());
        assertEquals(0, countLocalFiles());
    }

    @Test
    void openPrefersLocalFileThenFallsBackToObjectStorage() throws Exception {
        FakeObjectStorageClient client = new FakeObjectStorageClient();
        client.enabled = true;
        AssetStorageService service = new AssetStorageService(client, new DisabledVirusScanner(), true);

        Files.createDirectories(localRoot.resolve("user/avatar"));
        Files.write(localRoot.resolve("user/avatar/a.png"), "local".getBytes(StandardCharsets.UTF_8));
        client.objects.put("user/avatar/a.png", "remote".getBytes(StandardCharsets.UTF_8));
        client.objects.put("user/avatar/b.png", "remote-only".getBytes(StandardCharsets.UTF_8));

        assertEquals("local", new String(service.open(localRoot, "", "user/avatar/a.png").readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("remote-only", new String(service.open(localRoot, "", "user/avatar/b.png").readAllBytes(), StandardCharsets.UTF_8));
        assertNull(service.open(localRoot, "", "user/avatar/missing.png"));
    }

    @Test
    void deleteRemovesBothLocalAndObjectStorage() throws Exception {
        FakeObjectStorageClient client = new FakeObjectStorageClient();
        client.enabled = true;
        AssetStorageService service = new AssetStorageService(client, new DisabledVirusScanner(), true);
        Files.createDirectories(localRoot.resolve("user/avatar"));
        Files.write(localRoot.resolve("user/avatar/a.png"), "local".getBytes(StandardCharsets.UTF_8));
        client.objects.put("user/avatar/a.png", "remote".getBytes(StandardCharsets.UTF_8));

        service.delete(localRoot, "", "user/avatar/a.png");

        assertFalse(Files.exists(localRoot.resolve("user/avatar/a.png")));
        assertFalse(client.objects.containsKey("user/avatar/a.png"));
    }

    @Test
    void presignedGetUrlReturnsNullWhenObjectStorageDisabled() {
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);

        assertNull(service.presignedGetUrl("", "user/avatar/a.png", 300));
    }

    @Test
    void openRejectsPathTraversal() {
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.open(localRoot, "", "../../etc/passwd"));
        assertEquals(403, exception.getCode());
    }

    @Test
    void storeComputesSha256() {
        AssetStorageService service = new AssetStorageService(new DisabledObjectStorageClient(), new DisabledVirusScanner(), true);
        MockMultipartFile file = new MockMultipartFile("file", "test.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        StoredFile stored = service.store(localRoot, "", "user/avatar/a.txt", file);

        // SHA-256("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", stored.getSha256());
        assertEquals("hello", new String(readAllBytes(localRoot.resolve("user/avatar/a.txt")), StandardCharsets.UTF_8));
    }

    private int countLocalFiles() {
        try {
            return (int) Files.walk(localRoot).filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] readAllBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
