package com.omni.common.mq;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MQ 统一配置：重试队列 TTL 与死信前的最大重试次数。
 */
@Component
@ConfigurationProperties(prefix = "omni.mq")
public class MqProperties {

    /** 是否启用统一 MQ 拓扑声明与消费者基座。 */
    private boolean enabled = true;

    /** 重试队列消息 TTL（毫秒），到期后回投主队列。 */
    private long retryTtlMillis = 10000L;

    /** 主队列消费失败达到该重试次数后进入死信队列。 */
    private long maxRetryCount = 3L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRetryTtlMillis() {
        return retryTtlMillis;
    }

    public void setRetryTtlMillis(long retryTtlMillis) {
        this.retryTtlMillis = retryTtlMillis;
    }

    public long getMaxRetryCount() {
        return maxRetryCount;
    }

    public void setMaxRetryCount(long maxRetryCount) {
        this.maxRetryCount = maxRetryCount;
    }
}
