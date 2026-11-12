package com.omni.common.storage;

import java.io.InputStream;

/**
 * 未配置对象存储时的降级实现：所有写操作抛出异常，读操作返回 null。
 */
public class DisabledObjectStorageClient implements ObjectStorageClient {

    public static final String DISABLED_REASON = "对象存储未配置";

    @Override
    public String putObject(String key, InputStream inputStream, long size, String contentType) {
        throw new UnsupportedOperationException(DISABLED_REASON);
    }

    @Override
    public InputStream getObject(String key) {
        return null;
    }

    @Override
    public String presignedGetUrl(String key, int expirySeconds) {
        return null;
    }

    @Override
    public void removeObject(String key) {
        // 未配置时删除视为跳过
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
