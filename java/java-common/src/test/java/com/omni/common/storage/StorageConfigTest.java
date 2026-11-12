package com.omni.common.storage;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对象存储与病毒扫描条件装配测试。
 */
class StorageConfigTest {

    @Test
    void defaultProfileWiresDisabledImplementations() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(new MockEnvironment());
            context.register(ObjectStorageConfig.class, VirusScannerConfig.class);
            context.refresh();

            ObjectStorageClient client = context.getBean(ObjectStorageClient.class);
            VirusScanner scanner = context.getBean(VirusScanner.class);

            assertTrue(client instanceof DisabledObjectStorageClient);
            assertFalse(client.isEnabled());
            assertTrue(scanner instanceof DisabledVirusScanner);
            assertFalse(scanner.isEnabled());
        }
    }

    @Test
    void clamdProviderWiresClamdScanner() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ConfigurableEnvironment environment = new MockEnvironment()
                    .withProperty("omni.virus-scanner.provider", "clamd");
            context.setEnvironment(environment);
            context.register(VirusScannerConfig.class);
            context.refresh();

            VirusScanner scanner = context.getBean(VirusScanner.class);

            assertTrue(scanner instanceof ClamdVirusScanner);
            assertTrue(scanner.isEnabled());
        }
    }

    @Test
    void minioProviderWiresMinioClient() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            ConfigurableEnvironment environment = new MockEnvironment()
                    .withProperty("omni.object-storage.provider", "minio")
                    .withProperty("omni.object-storage.minio.endpoint", "http://localhost:9000")
                    .withProperty("omni.object-storage.minio.access-key", "omni")
                    .withProperty("omni.object-storage.minio.secret-key", "secret");
            context.setEnvironment(environment);
            context.register(ObjectStorageConfig.class);
            context.refresh();

            ObjectStorageClient client = context.getBean(ObjectStorageClient.class);

            assertTrue(client instanceof MinioObjectStorageClient);
            assertTrue(client.isEnabled());
            assertNotNull(context.getBean(ObjectStorageClient.class));
        }
    }
}
