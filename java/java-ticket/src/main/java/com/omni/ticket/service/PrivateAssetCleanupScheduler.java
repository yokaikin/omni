package com.omni.ticket.service;

import com.omni.ticket.service.PrivateAssetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 私有资产删除策略：定期清理上传后长期未绑定的 pending 私有资产，
 * 软删除记录并删除本地与对象存储中的物理文件。
 */
@Component
public class PrivateAssetCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrivateAssetCleanupScheduler.class);

    private final PrivateAssetService privateAssetService;
    private final int retentionDays;

    public PrivateAssetCleanupScheduler(PrivateAssetService privateAssetService,
                                        @Value("${omni.private-asset.pending-retention-days:7}") int retentionDays) {
        this.privateAssetService = privateAssetService;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "0 30 * * * *")
    public void cleanupExpiredPendingAssets() {
        try {
            int cleaned = privateAssetService.cleanupExpiredPendingAssets(retentionDays);
            if (cleaned > 0) {
                log.info("私有资产超期清理完成: {} 条 pending 资产已软删除（保留 {} 天）", cleaned, retentionDays);
            }
        } catch (RuntimeException e) {
            log.warn("私有资产超期清理失败: {}", e.getMessage());
        }
    }
}
