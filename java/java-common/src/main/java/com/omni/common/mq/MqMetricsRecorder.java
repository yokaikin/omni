package com.omni.common.mq;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * MQ 消费/重试/死信统一指标记录。
 * 未接入 Micrometer（无 MeterRegistry Bean）时全部 no-op，不影响业务。
 */
@Component
public class MqMetricsRecorder {

    public static final String METRIC_CONSUMED = "omni.mq.consumed";
    public static final String METRIC_RETRY = "omni.mq.retry";
    public static final String METRIC_DEAD_LETTER = "omni.mq.dead.letter";
    public static final String METRIC_REPLAY = "omni.mq.replay";

    private final MeterRegistry meterRegistry;

    public MqMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    public void recordConsumed(String queue) {
        increment(METRIC_CONSUMED, queue);
    }

    public void recordRetry(String queue) {
        increment(METRIC_RETRY, queue);
    }

    public void recordDeadLetter(String queue) {
        increment(METRIC_DEAD_LETTER, queue);
    }

    public void recordReplay(String queue) {
        increment(METRIC_REPLAY, queue);
    }

    private void increment(String name, String queue) {
        if (meterRegistry == null) {
            return;
        }
        meterRegistry.counter(name, "queue", queue).increment();
    }
}
