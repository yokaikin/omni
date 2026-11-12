package com.omni.common.storage;

/**
 * 病毒扫描抽象。实现负责扫描文件内容并返回结果。
 */
public interface VirusScanner {

    /**
     * 扫描文件内容。
     *
     * @param content  文件完整内容
     * @param filename 原始文件名（用于日志）
     * @return 扫描结果；实现不可用时应抛出异常由调用方按策略处理
     */
    VirusScanResult scan(byte[] content, String filename);

    /**
     * 当前实现是否真实生效（非降级实现）。
     */
    boolean isEnabled();
}
