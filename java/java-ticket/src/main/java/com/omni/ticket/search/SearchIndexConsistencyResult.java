package com.omni.ticket.search;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索索引与数据库一致性校验结果（口径与 rebuildAll 完全一致）。
 */
public class SearchIndexConsistencyResult {

    /** 数据库基准文档数（去重后 itemType:id） */
    private Integer dbCount;
    /** 索引实际文档数 */
    private Integer indexCount;
    /** 数据库存在但索引缺失的文档 ID（itemType:id） */
    private List<String> missingInIndex;
    /** 索引存在但数据库已不满足收录口径的文档 ID */
    private List<String> staleInIndex;
    /** 是否完全一致 */
    private Boolean consistent;
    private LocalDateTime checkedAt;

    public Integer getDbCount() { return dbCount; }
    public void setDbCount(Integer dbCount) { this.dbCount = dbCount; }
    public Integer getIndexCount() { return indexCount; }
    public void setIndexCount(Integer indexCount) { this.indexCount = indexCount; }
    public List<String> getMissingInIndex() { return missingInIndex; }
    public void setMissingInIndex(List<String> missingInIndex) { this.missingInIndex = missingInIndex; }
    public List<String> getStaleInIndex() { return staleInIndex; }
    public void setStaleInIndex(List<String> staleInIndex) { this.staleInIndex = staleInIndex; }
    public Boolean getConsistent() { return consistent; }
    public void setConsistent(Boolean consistent) { this.consistent = consistent; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}
