package com.omni.common.storage;

/**
 * 病毒扫描结果。
 */
public class VirusScanResult {

    private final boolean clean;
    private final String signature;

    private VirusScanResult(boolean clean, String signature) {
        this.clean = clean;
        this.signature = signature;
    }

    public static VirusScanResult clean() {
        return new VirusScanResult(true, null);
    }

    public static VirusScanResult infected(String signature) {
        return new VirusScanResult(false, signature);
    }

    public boolean isClean() {
        return clean;
    }

    /**
     * 检出病毒时的病毒签名名称；干净文件返回 null。
     */
    public String getSignature() {
        return signature;
    }
}
