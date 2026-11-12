package com.omni.ticket.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.omni.common.result.ResultCode;
import com.omni.exception.BusinessException;
import com.omni.ticket.dto.ActivityVO;
import org.junit.jupiter.api.Test;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchIndexOpsServiceTest {

    private final ActivitySearchIndexService.RebuildPageSource pageSource =
            mock(ActivitySearchIndexService.RebuildPageSource.class);
    private final ElasticsearchOperations operations = mock(ElasticsearchOperations.class);
    private final IndexOperations indexOperations = mock(IndexOperations.class);
    private final ActivitySearchProperties properties = new ActivitySearchProperties();
    private final SearchIndexOpsService opsService =
            new SearchIndexOpsService(pageSource, operations, properties);

    // ===== health =====

    @Test
    void healthReportsAvailableWithAliasIndicesAndDocCount() {
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        when(indexOperations.getAliases("omni_activity_current"))
                .thenReturn(Map.of("omni_activity_v1_20260101", Collections.emptySet()));
        when(operations.count(any(Query.class), any(IndexCoordinates.class))).thenReturn(42L);

        SearchIndexHealth health = opsService.health();

        assertTrue(health.getAvailable());
        assertEquals("omni_activity_current", health.getAliasName());
        assertEquals(List.of("omni_activity_v1_20260101"), health.getIndexNames());
        assertEquals(42L, health.getDocCount());
        assertNotNull(health.getCheckedAt());
    }

    @Test
    void healthReportsUnavailableWhenAliasMissing() {
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(false);

        SearchIndexHealth health = opsService.health();

        assertFalse(health.getAvailable());
        assertTrue(health.getMessage().contains("索引别名不存在"));
    }

    @Test
    void healthReportsUnavailableWhenElasticsearchDown() {
        when(operations.indexOps(any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("connection refused"));

        SearchIndexHealth health = opsService.health();

        assertFalse(health.getAvailable());
        assertTrue(health.getMessage().contains("connection refused"));
    }

    // ===== consistency =====

    @Test
    void consistencyDetectsMissingAndStaleDocuments() {
        mockIndexScan(indexDocument("activity:1"), indexDocument("activity:3"));
        mockDatabasePages(new Page<ActivityVO>(1, 100).setRecords(List.of(activity(1L), activity(2L))));

        SearchIndexConsistencyResult result = opsService.checkConsistency();

        assertFalse(result.getConsistent());
        assertEquals(2, result.getDbCount());
        assertEquals(2, result.getIndexCount());
        assertEquals(List.of("activity:2"), result.getMissingInIndex());
        assertEquals(List.of("activity:3"), result.getStaleInIndex());
        assertNotNull(result.getCheckedAt());
    }

    @Test
    void consistencyPassesWhenDatabaseMatchesIndex() {
        mockIndexScan(indexDocument("activity:1"), indexDocument("activity:2"), indexDocument("tour:5"));
        mockDatabasePages(new Page<ActivityVO>(1, 100)
                .setRecords(List.of(activity(1L), activity(2L), tour(5L))));

        SearchIndexConsistencyResult result = opsService.checkConsistency();

        assertTrue(result.getConsistent());
        assertEquals(3, result.getDbCount());
        assertEquals(3, result.getIndexCount());
        assertTrue(result.getMissingInIndex().isEmpty());
        assertTrue(result.getStaleInIndex().isEmpty());
    }

    @Test
    void consistencyScansDatabaseInPagesUntilShortPage() {
        mockIndexScan();
        Page<ActivityVO> first = new Page<ActivityVO>(1, 100).setRecords(activities(101L, 100));
        Page<ActivityVO> second = new Page<ActivityVO>(2, 100).setRecords(activities(201L, 37));
        when(pageSource.listActivities(1, 100, null)).thenReturn(first);
        when(pageSource.listActivities(2, 100, null)).thenReturn(second);

        SearchIndexConsistencyResult result = opsService.checkConsistency();

        assertEquals(137, result.getDbCount());
        assertEquals(0, result.getIndexCount());
        assertEquals(137, result.getMissingInIndex().size());
    }

    @Test
    void consistencyFailsWhenAliasMissing() {
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(false);
        mockDatabasePages(new Page<ActivityVO>(1, 100).setRecords(List.of(activity(1L))));

        BusinessException exception = assertThrows(BusinessException.class, () -> opsService.checkConsistency());

        assertEquals("搜索索引别名不存在，请先执行索引重建", exception.getMessage());
    }

    @Test
    void consistencyFailsWhenIndexExceedsScanLimit() {
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        SearchHits<ActivitySearchDocument> hits = mock(SearchHits.class);
        when(hits.getTotalHits()).thenReturn(10_001L);
        when(hits.getSearchHits()).thenReturn(Collections.emptyList());
        when(operations.search(any(Query.class), eq(ActivitySearchDocument.class), any(IndexCoordinates.class)))
                .thenReturn(hits);
        mockDatabasePages(new Page<ActivityVO>(1, 100).setRecords(List.of(activity(1L))));

        BusinessException exception = assertThrows(BusinessException.class, () -> opsService.checkConsistency());

        assertEquals(ResultCode.INTERNAL_ERROR.getCode(), exception.getCode());
        assertTrue(exception.getMessage().contains("扫描上限"));
    }

    @Test
    void consistencyFailsWhenElasticsearchDown() {
        when(operations.indexOps(any(IndexCoordinates.class)))
                .thenThrow(new RuntimeException("connection refused"));
        mockDatabasePages(new Page<ActivityVO>(1, 100).setRecords(List.of(activity(1L))));

        BusinessException exception = assertThrows(BusinessException.class, () -> opsService.checkConsistency());

        assertTrue(exception.getMessage().contains("Elasticsearch 访问失败"));
    }

    // ===== helpers =====

    @SuppressWarnings("unchecked")
    private void mockIndexScan(ActivitySearchDocument... documents) {
        when(operations.indexOps(any(IndexCoordinates.class))).thenReturn(indexOperations);
        when(indexOperations.exists()).thenReturn(true);
        SearchHits<ActivitySearchDocument> hits = mock(SearchHits.class);
        List<SearchHit<ActivitySearchDocument>> hitList = documents == null
                ? Collections.emptyList()
                : List.of(documents).stream()
                        .map(document -> (SearchHit<ActivitySearchDocument>) new SearchHit<ActivitySearchDocument>(
                                "omni_activity_current", document.getId(), null, 1.0f, null,
                                Collections.emptyMap(), document))
                        .collect(Collectors.toList());
        when(hits.getSearchHits()).thenReturn(hitList);
        when(hits.getTotalHits()).thenReturn((long) hitList.size());
        when(operations.search(any(Query.class), eq(ActivitySearchDocument.class), any(IndexCoordinates.class)))
                .thenReturn(hits);
    }

    private void mockDatabasePages(Page<ActivityVO> page) {
        when(pageSource.listActivities(1, 100, null)).thenReturn(page);
    }

    private ActivitySearchDocument indexDocument(String id) {
        ActivitySearchDocument document = new ActivitySearchDocument();
        document.setId(id);
        return document;
    }

    private ActivityVO activity(Long id) {
        ActivityVO vo = new ActivityVO();
        vo.setId(id);
        vo.setItemType("activity");
        return vo;
    }

    private ActivityVO tour(Long id) {
        ActivityVO vo = new ActivityVO();
        vo.setId(id);
        vo.setItemType("tour");
        return vo;
    }

    private List<ActivityVO> activities(long startId, int count) {
        return LongStream.range(startId, startId + count)
                .mapToObj(this::activity)
                .collect(Collectors.toList());
    }
}
