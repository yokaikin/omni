package com.omni.common.storage;

import java.io.InputStream;

/**
 * 对象存储客户端抽象（S3 兼容语义）。
 * 接口定义在 java-common，具体实现（MinIO/OSS 等）由各服务按需引入 SDK 提供。
 */
public interface ObjectStorageClient {

    /**
     * 上传对象。
     *
     * @param key          对象键
     * @param inputStream  内容流
     * @param size         内容长度（字节）
     * @param contentType  MIME 类型，可为 null
     * @return 对象存储返回的对象键（可能与入参一致）
     */
    String putObject(String key, InputStream inputStream, long size, String contentType);

    /**
     * 下载对象。
     */
    InputStream getObject(String key);

    /**
     * 生成带签名的临时下载地址；不支持签名的实现返回 null。
     *
     * @param key           对象键
     * @param expirySeconds 有效期（秒）
     */
    String presignedGetUrl(String key, int expirySeconds);

    /**
     * 删除对象；对象不存在时静默成功。
     */
    void removeObject(String key);

    /**
     * 当前实现是否可用（非降级实现）。
     */
    boolean isEnabled();
}
