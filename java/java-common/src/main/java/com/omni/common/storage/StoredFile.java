package com.omni.common.storage;

/**
 * 上传存储结果。
 */
public class StoredFile {

    private final String sha256;
    private final boolean storedInObjectStorage;

    public StoredFile(String sha256, boolean storedInObjectStorage) {
        this.sha256 = sha256;
        this.storedInObjectStorage = storedInObjectStorage;
    }

    public String getSha256() {
        return sha256;
    }

    /**
     * 是否写入对象存储（false 表示本地磁盘降级）。
     */
    public boolean isStoredInObjectStorage() {
        return storedInObjectStorage;
    }
}
