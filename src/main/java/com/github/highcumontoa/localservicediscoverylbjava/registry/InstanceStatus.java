package com.github.highcumontoa.localservicediscoverylbjava.registry;

/**
 * 实例生命周期状态。
 */
public enum InstanceStatus {
    /** 已注册且心跳新鲜，可被选择。 */
    HEALTHY,
    /** 心跳过期，暂时不健康；可能被后续心跳救活，也可能最终被清理。 */
    UNHEALTHY,
    /** 主动注销，彻底下线；不可恢复（重新注册必须显式发起）。 */
    DEREGISTERED
}
