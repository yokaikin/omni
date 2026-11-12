package com.omni.ticket.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.ActivityVO;
import org.elasticsearch.index.query.QueryBuilders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.index.AliasData;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 搜索索引运维服务：索引健康检查、数据库与索引一致性校验。
 */
@Service
@ConditionalOnProperty(prefix = "omni.search", name = "provider", havingValue = "elasticsearch")
public class SearchIndexOpsService {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexOpsService.class);

    /** 与 ActivitySearchIndexService.rebuildAll 相同的分页口径 */
    private static final int CONSISTENCY_PAGE_SIZE = 100;
    /** 单次扫描索引文档上限（from+size 深度分页限制） */
    private static final int MAX_INDEX_SCAN_SIZE = 10_000;

    private final ActivitySearchIndexService.RebuildPageSource rebuildPageSource;
    private final ElasticsearchOperations operations;
    private final ActivitySearchProperties properties;

    @Autowired
    public SearchIndexOpsService(com.omni.ticket.service.ActivityService activityService,
                                 ElasticsearchOperations operations,
                                 ActivitySearchProperties properties) {
        this(activityService::listActivities, operations, properties);
    }

    public SearchIndexOpsService(ActivitySearchIndexService.RebuildPageSource rebuildPageSource,
                                 ElasticsearchOperations operations,
                                 ActivitySearchProperties properties) {
        this.rebuildPageSource = rebuildPageSource;
        this.operations = operations;
        this.properties = properties == null ? new ActivitySearchProperties() : properties;
    }

    /**
     * 索引健康检查：Elasticsearch 可达性、别名指向、文档数。
     * 任何异常都转为 available=false 的结果返回，不向上抛错。
     */
    public SearchIndexHealth health() {
        String aliasName = resolveAliasName();
        SearchIndexHealth health = new SearchIndexHealth();
        health.setAliasName(aliasName);
        health.setIndexNames(Collections.emptyList());
        health.setCheckedAt(LocalDateTime.now());
        try {
            IndexCoordinates coordinates = IndexCoordinates.of(aliasName);
            IndexOperations indexOperations = operations.indexOps(coordinates);
            if (!indexOperations.exists()) {
                health.setAvailable(false);
                health.setMessage("搜索索引别名不存在，请先执行索引重建");
                return health;
            }
            Map<String, Set<AliasData>> aliases = indexOperations.getAliases(aliasName);
            if (aliases != null && !aliases.isEmpty()) {
                health.setIndexNames(new ArrayList<>(aliases.keySet()));
            }
            health.setDocCount(operations.count(matchAllQuery(), coordinates));
            health.setAvailable(true);
        } catch (Exception e) {
            log.warn("搜索索引健康检查失败: alias={}, message={}", aliasName, e.getMessage());
            health.setAvailable(false);
            health.setMessage("Elasticsearch 访问失败: " + e.getMessage());
        }
        return health;
    }

    /**
     * 数据库与索引一致性校验：以 rebuildAll 同口径的数据库文档 ID 集合为基准，
     * 对比索引实际文档 ID 集合，返回缺失（待补偿）与多余（待清理/重建）清单。
     */
    public SearchIndexConsistencyResult checkConsistency() {
        Set<String> dbIds = collectDatabaseDocumentIds();
        Set<String> indexIds = collectIndexDocumentIds();

        Set<String> missing = new TreeSet<>(dbIds);
        missing.removeAll(indexIds);
        Set<String> stale = new TreeSet<>(indexIds);
        stale.removeAll(dbIds);

        SearchIndexConsistencyResult result = new SearchIndexConsistencyResult();
        result.setDbCount(dbIds.size());
        result.setIndexCount(indexIds.size());
        result.setMissingInIndex(new ArrayList<>(missing));
        result.setStaleInIndex(new ArrayList<>(stale));
        result.setConsistent(missing.isEmpty() && stale.isEmpty());
        result.setCheckedAt(LocalDateTime.now());
        if (result.getConsistent()) {
            log.info("搜索索引一致性校验通过: dbCount={}, indexCount={}", dbIds.size(), indexIds.size());
        } else {
            log.warn("搜索索引一致性校验发现差异: dbCount={}, indexCount={}, missing={}, stale={}",
                    dbIds.size(), indexIds.size(), missing.size(), stale.size());
        }
        return result;
    }

    private Set<String> collectDatabaseDocumentIds() {
        Set<String> ids = new LinkedHashSet<>();
        int pageNumber = 1;
        while (true) {
            Page<ActivityVO> page = rebuildPageSource.listActivities(pageNumber, CONSISTENCY_PAGE_SIZE, null);
            List<ActivityVO> records = page == null || page.getRecords() == null
                    ? Collections.emptyList()
                    : page.getRecords();
            if (records.isEmpty()) {
                break;
            }
            for (ActivityVO activity : records) {
                if (activity == null || activity.getId() == null) {
                    continue;
                }
                String itemType = StringUtils.hasText(activity.getItemType()) ? activity.getItemType() : "activity";
                ids.add(itemType + ":" + activity.getId());
            }
            if (records.size() < CONSISTENCY_PAGE_SIZE) {
                break;
            }
            pageNumber++;
        }
        return ids;
    }

    private Set<String> collectIndexDocumentIds() {
        String aliasName = resolveAliasName();
        IndexCoordinates coordinates = IndexCoordinates.of(aliasName);
        try {
            IndexOperations indexOperations = operations.indexOps(coordinates);
            if (!indexOperations.exists()) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR, "搜索索引别名不存在，请先执行索引重建");
            }
            NativeSearchQuery query = new NativeSearchQueryBuilder()
                    .withQuery(QueryBuilders.matchAllQuery())
                    .withPageable(PageRequest.of(0, MAX_INDEX_SCAN_SIZE))
                    .build();
            SearchHits<ActivitySearchDocument> hits =
                    operations.search(query, ActivitySearchDocument.class, coordinates);
            if (hits.getTotalHits() > MAX_INDEX_SCAN_SIZE) {
                throw new BusinessException(ResultCode.INTERNAL_ERROR,
                        "索引文档数超过扫描上限(" + MAX_INDEX_SCAN_SIZE + ")，请先执行索引重建");
            }
            Set<String> ids = new HashSet<>();
            for (SearchHit<ActivitySearchDocument> hit : hits.getSearchHits()) {
                ActivitySearchDocument document = hit.getContent();
                if (document != null && StringUtils.hasText(document.getId())) {
                    ids.add(document.getId());
                }
            }
            return ids;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("搜索索引一致性校验读取索引失败: alias={}, message={}", aliasName, e.getMessage());
            throw new BusinessException(ResultCode.INTERNAL_ERROR, "Elasticsearch 访问失败: " + e.getMessage());
        }
    }

    private NativeSearchQuery matchAllQuery() {
        return new NativeSearchQueryBuilder().withQuery(QueryBuilders.matchAllQuery()).build();
    }

    private String resolveAliasName() {
        if (StringUtils.hasText(properties.getAliasName())) {
            return properties.getAliasName().trim();
        }
        return "omni_activity_current";
    }
}
