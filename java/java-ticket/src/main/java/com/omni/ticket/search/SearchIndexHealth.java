package com.omni.ticket.search;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 搜索索引健康检查结果。
 */
public class SearchIndexHealth {

    /** Elasticsearch 是否可达且索引别名可用 */
    private Boolean available;
    /** 索引别名（读流量入口） */
    private String aliasName;
    /** 别名当前指向的物理索引列表 */
    private List<String> indexNames;
    /** 别名下文档总数 */
    private Long docCount;
    /** 不可用原因说明 */
    private String message;
    private LocalDateTime checkedAt;

    public Boolean getAvailable() { return available; }
    public void setAvailable(Boolean available) { this.available = available; }
    public String getAliasName() { return aliasName; }
    public void setAliasName(String aliasName) { this.aliasName = aliasName; }
    public List<String> getIndexNames() { return indexNames; }
    public void setIndexNames(List<String> indexNames) { this.indexNames = indexNames; }
    public Long getDocCount() { return docCount; }
    public void setDocCount(Long docCount) { this.docCount = docCount; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}
