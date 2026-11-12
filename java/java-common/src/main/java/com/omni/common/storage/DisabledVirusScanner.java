package com.omni.common.storage;

/**
 * 未配置病毒扫描时的降级实现：不做任何扫描，直接放行。
 */
public class DisabledVirusScanner implements VirusScanner {

    @Override
    public VirusScanResult scan(byte[] content, String filename) {
        return VirusScanResult.clean();
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
