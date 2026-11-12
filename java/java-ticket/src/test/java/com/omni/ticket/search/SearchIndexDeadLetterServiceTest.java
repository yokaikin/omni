package com.omni.ticket.search;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.exception.BusinessException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import com.omni.ticket.mapper.SearchIndexDeadLetterMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SearchIndexDeadLetterServiceTest {

    private SearchIndexDeadLetterMapper deadLetterMapper;
    private RabbitTemplate rabbitTemplate;
    private MqMetricsRecorder metricsRecorder;
    private SearchIndexDeadLetterService service;

    @BeforeAll
    static void mybatis() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SearchIndexDeadLetter.class);
    }

    @BeforeEach
    void setUp() {
        deadLetterMapper = mock(SearchIndexDeadLetterMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        metricsRecorder = mock(MqMetricsRecorder.class);
        service = new SearchIndexDeadLetterService(deadLetterMapper, rabbitTemplate, metricsRecorder);
    }

    @Test
    void recordPersistsPendingAuditWithRawPayload() {
        Message rawMessage = new Message("{\"activityId\":900001}".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        SearchIndexDeadLetter record = service.record(MqConstants.Q_SEARCH_ACTIVITY_CHANGED,
                MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ, rawMessage);

        ArgumentCaptor<SearchIndexDeadLetter> captor = ArgumentCaptor.forClass(SearchIndexDeadLetter.class);
        verify(deadLetterMapper).insert(captor.capture());
        SearchIndexDeadLetter saved = captor.getValue();
        assertEquals(MqConstants.Q_SEARCH_ACTIVITY_CHANGED, saved.getSourceQueue());
        assertEquals(MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ, saved.getDeadLetterQueue());
        assertEquals("{\"activityId\":900001}", saved.getPayload());
        assertEquals(SearchIndexDeadLetter.STATUS_PENDING, saved.getStatus());
        assertEquals(0, saved.getReplayCount());
        assertNotNull(saved.getCreatedTime());
    }

    @Test
    void listDelegatesToMapper() {
        when(deadLetterMapper.selectList(any())).thenReturn(List.of());

        service.list(SearchIndexDeadLetter.STATUS_PENDING, 20);

        verify(deadLetterMapper).selectList(any());
    }

    @Test
    void replaySendsCleanMessageToSearchQueueAndMarksReplayed() {
        SearchIndexDeadLetter record = pendingRecord("{\"activityId\":900001}");
        when(deadLetterMapper.selectById(7L)).thenReturn(record);

        SearchIndexDeadLetter replayed = service.replay(7L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(MqConstants.SEARCH_INDEX_EXCHANGE), eq(MqConstants.RK_SEARCH_ACTIVITY_CHANGED), messageCaptor.capture());
        assertEquals("{\"activityId\":900001}", new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8));
        assertEquals(SearchIndexDeadLetter.STATUS_REPLAYED, replayed.getStatus());
        assertEquals(1, replayed.getReplayCount());
        assertNotNull(replayed.getReplayedAt());
        verify(deadLetterMapper).updateById(record);
        verify(metricsRecorder).recordReplay(MqConstants.Q_SEARCH_ACTIVITY_CHANGED);
    }

    @Test
    void replayThrowsWhenRecordMissing() {
        when(deadLetterMapper.selectById(404L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.replay(404L));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void replayThrowsWhenSourceQueueUnsupported() {
        SearchIndexDeadLetter record = pendingRecord("{}");
        record.setSourceQueue("unknown.queue");
        when(deadLetterMapper.selectById(11L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.replay(11L));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void replayThrowsWhenPayloadBlank() {
        SearchIndexDeadLetter record = pendingRecord(" ");
        when(deadLetterMapper.selectById(12L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.replay(12L));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void recordWithNullRawMessageStillPersists() {
        assertDoesNotThrow(() -> service.record(MqConstants.Q_SEARCH_ACTIVITY_CHANGED,
                MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ, null));
        verify(deadLetterMapper).insert(any(SearchIndexDeadLetter.class));
    }

    private SearchIndexDeadLetter pendingRecord(String payload) {
        SearchIndexDeadLetter record = new SearchIndexDeadLetter();
        record.setId(7L);
        record.setSourceQueue(MqConstants.Q_SEARCH_ACTIVITY_CHANGED);
        record.setDeadLetterQueue(MqConstants.Q_SEARCH_ACTIVITY_CHANGED_DLQ);
        record.setPayload(payload);
        record.setStatus(SearchIndexDeadLetter.STATUS_PENDING);
        record.setReplayCount(0);
        return record;
    }
}
