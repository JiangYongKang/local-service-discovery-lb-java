package com.github.highcumontoa.localservicediscoverylbjava.client;

/** 一次服务调用的描述。retryable=false 时失败不允许换实例重试。 */
public record ServiceRequest(
        String serviceName,
        String payload,
        boolean retryable
) {
    public static ServiceRequest idempotent(String serviceName, String payload) {
        return new ServiceRequest(serviceName, payload, true);
    }

    public static ServiceRequest nonIdempotent(String serviceName, String payload) {
        return new ServiceRequest(serviceName, payload, false);
    }
}
