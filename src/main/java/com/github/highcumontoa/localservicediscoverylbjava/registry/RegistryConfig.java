package com.github.highcumontoa.localservicediscoverylbjava.registry;

/**
 * 注册中心健康判定与过期配置。
 */
public record RegistryConfig(long heartbeatTtlMillis, long evictAfterMillis, Ticker ticker) {

    public static RegistryConfig ofMillis(long heartbeatTtlMillis, long evictAfterMillis, Ticker ticker) {
        if (heartbeatTtlMillis <= 0 || evictAfterMillis <= 0) {
            throw new IllegalArgumentException("heartbeatTtlMillis 与 evictAfterMillis 必须为正数");
        }
        if (evictAfterMillis < heartbeatTtlMillis) {
            throw new IllegalArgumentException("evictAfterMillis 不能小于 heartbeatTtlMillis");
        }
        if (ticker == null) {
            throw new IllegalArgumentException("ticker 不能为 null");
        }
        return new RegistryConfig(heartbeatTtlMillis, evictAfterMillis, ticker);
    }
}
