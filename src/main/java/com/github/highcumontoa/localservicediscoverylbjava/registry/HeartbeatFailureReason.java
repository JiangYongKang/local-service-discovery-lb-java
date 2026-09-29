package com.github.highcumontoa.localservicediscoverylbjava.registry;

/** 心跳被拒绝的可区分原因。 */
public enum HeartbeatFailureReason {
    /** 注册表里根本不存在该实例（可能已被过期清理）。 */
    UNKNOWN_INSTANCE,
    /** 实例已主动注销，心跳不允许复活它。 */
    DEREGISTERED_INSTANCE,
    /** 迟到的旧心跳：序列号不大于已接受的序列号。 */
    STALE_HEARTBEAT
}
