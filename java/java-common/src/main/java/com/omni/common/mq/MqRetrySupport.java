package com.omni.common.mq;

import org.springframework.amqp.core.Message;

import java.util.List;
import java.util.Map;

/**
 * 消费重试支持：解析 RabbitMQ x-death 头中的重试次数，判断是否达到死信上限。
 */
public final class MqRetrySupport {

    private MqRetrySupport() {}

    /**
     * 从消息 x-death 头解析经过指定重试队列的死信次数。
     */
    public static long retryCount(Message rawMessage, String retryQueue) {
        if (rawMessage == null || rawMessage.getMessageProperties() == null) {
            return 0L;
        }
        Object deaths = rawMessage.getMessageProperties().getHeaders().get("x-death");
        if (!(deaths instanceof List<?>)) {
            return 0L;
        }
        for (Object item : (List<?>) deaths) {
            if (item instanceof Map<?, ?>) {
                Map<?, ?> death = (Map<?, ?>) item;
                if (retryQueue.equals(String.valueOf(death.get("queue")))) {
                    Object count = death.get("count");
                    if (count instanceof Number) {
                        return ((Number) count).longValue();
                    }
                    try {
                        return Long.parseLong(String.valueOf(count));
                    } catch (NumberFormatException ignored) {
                        return 0L;
                    }
                }
            }
        }
        return 0L;
    }

    /**
     * 判断消息重试次数是否已达到死信上限。
     */
    public static boolean retryExhausted(Message rawMessage, String retryQueue, long maxRetryCount) {
        return retryCount(rawMessage, retryQueue) >= maxRetryCount;
    }
}
