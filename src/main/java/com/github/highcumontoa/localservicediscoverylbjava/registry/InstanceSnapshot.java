package com.github.highcumontoa.localservicediscoverylbjava.registry;

/** 某一时刻实例的完整状态快照（供选择与打印验证使用）。 */
public record InstanceSnapshot(
        ServiceInstance instance,
        InstanceStatus status,
        long registeredAtNanos,
        long lastHeartbeatNanos,
        long lastHeartbeatSeq,
        long activeConnections
) {
    public String instanceId() { return instance.getInstanceId(); }
    public String serviceName() { return instance.getServiceName(); }
}
