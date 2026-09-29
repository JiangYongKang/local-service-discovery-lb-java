package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.List;

/**
 * 一次服务调用的明确结果：成功携带响应，失败携带可区分的拒绝原因，
 * 绝不返回 null 响应或向调用方抛出内部异常。
 */
public record InvocationOutcome(
        boolean success,
        Object response,
        RejectReason rejectReason,
        String message,
        ServiceInstance selectedInstance,
        List<String> attemptedInstanceIds,
        int attemptCount) {

    public enum RejectReason {
        /** 没有任何已注册实例。 */
        NO_INSTANCE_REGISTERED,
        /** 有注册实例，但全部处于心跳过期/已注销状态。 */
        NO_HEALTHY_INSTANCE,
        /** 健康实例都尝试过且都失败，且不允许/不值得再重试。 */
        ALL_INSTANCES_FAILED,
        /** 请求类型被策略判定为不可重试，首次失败即拒绝。 */
        NON_RETRYABLE_REQUEST,
        /** 已达到最大尝试次数仍失败。 */
        RETRY_LIMIT_REACHED
    }

    public static InvocationOutcome rejected(RejectReason reason, String message, List<String> attempted) {
        return new InvocationOutcome(false, null, reason, message, null, List.copyOf(attempted), attempted.size());
    }

    public static InvocationOutcome ok(Object response, ServiceInstance instance, List<String> attempted, int attempts) {
        return new InvocationOutcome(true, response, null, "OK", instance, List.copyOf(attempted), attempts);
    }
}
