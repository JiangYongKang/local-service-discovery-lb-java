package com.github.highcumontoa.localservicediscoverylbjava.registry;

/** 实例生命周期状态：健康 / 心跳过期暂时不健康 / 主动注销彻底下线。 */
public enum InstanceStatus {
    HEALTHY,
    UNHEALTHY,
    DEREGISTERED
}
