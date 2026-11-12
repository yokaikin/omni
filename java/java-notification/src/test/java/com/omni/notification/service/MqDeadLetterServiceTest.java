package com.omni.notification.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.omni.common.mq.MqConstants;
import com.omni.common.mq.MqMetricsRecorder;
import com.omni.exception.BusinessException;
import com.omni.notification.entity.MqDeadLetter;
import com.omni.notification.mapper.MqDeadLetterMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
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

class MqDeadLetterServiceTest {

    private MqDeadLetterMapper mqDeadLetterMapper;
    private RabbitTemplate rabbitTemplate;
    private MqMetricsRecorder metricsRecorder;
    private MqDeadLetterService service;

    @BeforeAll
    static void mybatis() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), MqDeadLetter.class);
    }

    @BeforeEach
    void setUp() {
        mqDeadLetterMapper = mock(MqDeadLetterMapper.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        metricsRecorder = mock(MqMetricsRecorder.class);
        service = new MqDeadLetterService(mqDeadLetterMapper, rabbitTemplate, metricsRecorder);
    }

    @Test
    void recordPersistsPendingAuditWithRawPayload() {
        Message rawMessage = new Message("{\"userId\":2004}".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        MqDeadLetter record = service.record(MqConstants.Q_NOTIFICATION_SEND, MqConstants.Q_NOTIFICATION_SEND_DLQ, rawMessage);

        ArgumentCaptor<MqDeadLetter> captor = ArgumentCaptor.forClass(MqDeadLetter.class);
        verify(mqDeadLetterMapper).insert(captor.capture());
        MqDeadLetter saved = captor.getValue();
        assertEquals(MqConstants.Q_NOTIFICATION_SEND, saved.getSourceQueue());
        assertEquals(MqConstants.Q_NOTIFICATION_SEND_DLQ, saved.getDeadLetterQueue());
        assertEquals("{\"userId\":2004}", saved.getPayload());
        assertEquals(MqDeadLetter.STATUS_PENDING, saved.getStatus());
        assertEquals(0, saved.getReplayCount());
        assertNotNull(saved.getCreatedTime());
    }

    @Test
    void listDelegatesToMapper() {
        when(mqDeadLetterMapper.selectList(any())).thenReturn(List.of());

        service.list(MqDeadLetter.STATUS_PENDING, 20);

        verify(mqDeadLetterMapper).selectList(any());
    }

    @Test
    void replaySendsCleanMessageToOriginalQueueAndMarksReplayed() {
        MqDeadLetter record = pendingRecord(MqConstants.Q_NOTIFICATION_SEND, "{\"userId\":2004}");
        when(mqDeadLetterMapper.selectById(7L)).thenReturn(record);

        MqDeadLetter replayed = service.replay(7L);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(MqConstants.NOTIFICATION_EXCHANGE), eq(MqConstants.RK_NOTIFICATION_SEND), messageCaptor.capture());
        assertEquals("{\"userId\":2004}", new String(messageCaptor.getValue().getBody(), StandardCharsets.UTF_8));
        assertEquals(MqDeadLetter.STATUS_REPLAYED, replayed.getStatus());
        assertEquals(1, replayed.getReplayCount());
        assertNotNull(replayed.getReplayedAt());
        verify(mqDeadLetterMapper).updateById(record);
        verify(metricsRecorder).recordReplay(MqConstants.Q_NOTIFICATION_SEND);
    }

    @Test
    void replayEventQueueRoutesToEventRoutingKey() {
        MqDeadLetter record = pendingRecord(MqConstants.Q_NOTIFICATION_EVENT, "{\"eventId\":\"e-1\"}");
        when(mqDeadLetterMapper.selectById(9L)).thenReturn(record);

        service.replay(9L);

        verify(rabbitTemplate).send(eq(MqConstants.NOTIFICATION_EXCHANGE), eq(MqConstants.RK_NOTIFICATION_EVENT), any(Message.class));
    }

    @Test
    void replayThrowsWhenRecordMissing() {
        when(mqDeadLetterMapper.selectById(404L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.replay(404L));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void replayThrowsWhenSourceQueueUnsupported() {
        MqDeadLetter record = pendingRecord("unknown.queue", "{}");
        when(mqDeadLetterMapper.selectById(11L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.replay(11L));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void replayThrowsWhenPayloadBlank() {
        MqDeadLetter record = pendingRecord(MqConstants.Q_NOTIFICATION_SEND, " ");
        when(mqDeadLetterMapper.selectById(12L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> service.replay(12L));
        verify(rabbitTemplate, never()).send(anyString(), anyString(), any(Message.class));
    }

    @Test
    void recordWithNullRawMessageStillPersists() {
        assertDoesNotThrow(() -> service.record(MqConstants.Q_NOTIFICATION_SEND, MqConstants.Q_NOTIFICATION_SEND_DLQ, null));
        verify(mqDeadLetterMapper).insert(any(MqDeadLetter.class));
    }

    private MqDeadLetter pendingRecord(String sourceQueue, String payload) {
        MqDeadLetter record = new MqDeadLetter();
        record.setId(7L);
        record.setSourceQueue(sourceQueue);
        record.setDeadLetterQueue(MqConstants.Q_NOTIFICATION_SEND_DLQ);
        record.setPayload(payload);
        record.setStatus(MqDeadLetter.STATUS_PENDING);
        record.setReplayCount(0);
        return record;
    }
}
