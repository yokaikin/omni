package com.omni.common.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 对象存储统一装配：
 * - omni.object-storage.provider=minio 时装配 MinIO（S3 兼容）实现；
 * - 其余情况装配 DisabledObjectStorageClient 降级。
 */
@Configuration
public class ObjectStorageConfig {

    @Bean
    @ConditionalOnProperty(name = "omni.object-storage.provider", havingValue = "minio")
    public ObjectStorageClient minioObjectStorageClient(
            @Value("${omni.object-storage.minio.endpoint:}") String endpoint,
            @Value("${omni.object-storage.minio.public-endpoint:}") String publicEndpoint,
            @Value("${omni.object-storage.minio.access-key:}") String accessKey,
            @Value("${omni.object-storage.minio.secret-key:}") String secretKey,
            @Value("${omni.object-storage.minio.bucket:omni-assets}") String bucket) {
        return new MinioObjectStorageClient(endpoint, publicEndpoint, accessKey, secretKey, bucket);
    }

    @Bean
    @ConditionalOnProperty(name = "omni.object-storage.provider", havingValue = "disabled", matchIfMissing = true)
    public ObjectStorageClient disabledObjectStorageClient() {
        return new DisabledObjectStorageClient();
    }
}
