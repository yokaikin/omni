package com.omni.common.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.InputStream;

/**
 * MinIO（S3 兼容）对象存储实现。
 * 内部 endpoint 用于服务间读写；publicEndpoint（可选）用于生成浏览器可达的签名下载地址。
 */
public class MinioObjectStorageClient implements ObjectStorageClient {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorageClient.class);

    /** S3 兼容服务的默认 region；显式设置后签名/预签名不再发起 region 网络查询。 */
    private static final String DEFAULT_REGION = "us-east-1";

    private final MinioClient client;
    private final MinioClient presignClient;
    private final String bucket;
    private volatile boolean bucketReady;

    public MinioObjectStorageClient(String endpoint,
                                    String publicEndpoint,
                                    String accessKey,
                                    String secretKey,
                                    String bucket) {
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalStateException("对象存储 endpoint 未配置，请检查 OMNI_OBJECT_STORAGE_ENDPOINT");
        }
        if (!StringUtils.hasText(accessKey) || !StringUtils.hasText(secretKey)) {
            throw new IllegalStateException("对象存储 AccessKey/SecretKey 未配置");
        }
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalStateException("对象存储 bucket 未配置");
        }
        this.client = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .region(DEFAULT_REGION)
                .build();
        this.presignClient = StringUtils.hasText(publicEndpoint)
                ? MinioClient.builder().endpoint(publicEndpoint).credentials(accessKey, secretKey).region(DEFAULT_REGION).build()
                : this.client;
        this.bucket = bucket;
    }

    /**
     * 首次写入时确保 bucket 存在（惰性初始化，避免服务启动期依赖对象存储可用）。
     */
    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (bucketReady) {
                return;
            }
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("对象存储 bucket 已创建: {}", bucket);
                }
                bucketReady = true;
            } catch (Exception e) {
                throw new IllegalStateException("对象存储 bucket 初始化失败: " + e.getMessage(), e);
            }
        }
    }

    @Override
    public String putObject(String key, InputStream inputStream, long size, String contentType) {
        ensureBucket();
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(inputStream, size, -1)
                    .contentType(StringUtils.hasText(contentType) ? contentType : "application/octet-stream")
                    .build());
            return key;
        } catch (Exception e) {
            throw new IllegalStateException("对象存储上传对象失败: " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream getObject(String key) {
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String presignedGetUrl(String key, int expirySeconds) {
        try {
            return presignClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(key)
                    .expiry(Math.max(60, expirySeconds))
                    .build());
        } catch (Exception e) {
            log.warn("对象存储生成签名下载地址失败: key={}, error={}", key, e.getMessage());
            return null;
        }
    }

    @Override
    public void removeObject(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            log.warn("对象存储删除对象失败: key={}, error={}", key, e.getMessage());
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
