package com.github.highcumontoa.localservicediscoverylbjava.client;

/** 请求被拒绝（未得到业务响应）的可区分原因。 */
public enum RejectionReason {
    /** 该服务当前没有任何健康实例。 */
    NO_HEALTHY_INSTANCE,
    /** 重试换遍了所有健康实例，每一个都不可用。 */
    ALL_INSTANCES_FAILED,
    /** 请求被判定为不可重试（非幂等），首次失败后不再换实例。 */
    NON_RETRYABLE_REQUEST,
    /** 已达到允许的最大尝试次数。 */
    MAX_ATTEMPTS_REACHED
}
