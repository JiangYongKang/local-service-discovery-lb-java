package com.github.highcumontoa.localservicediscoverylbjava.registry;

/**
 * 服务实例记录。所有状态读写均通过内置锁保护，保证并发注册/心跳/注销期间状态一致。
 *
 * <p>状态机：
 * <pre>
 *   注册 -> HEALTHY ──心跳超过 ttl──> UNHEALTHY ──新鲜心跳──> HEALTHY（可反复恢复）
 *                                    │
 *                                    └──超过 evictAfter──> （由注册中心从表中清理，记录消失）
 *   HEALTHY/UNHEALTHY ──主动注销──> DEREGISTERED（终态，心跳不可救活）
 * </pre>
 *
 * <p>每条心跳携带严格递增的版本号（由注册中心发放），迟到的旧心跳会被识别并拒绝。
 */
public final class ServiceInstance {

    private final String instanceId;
    private final String serviceName;
    private final String host;
    private final int port;

    private InstanceStatus status;
    private long registeredAt;
    private long lastHeartbeatAt;
    private long becameUnhealthyAt;
    private long heartbeatVersion;

    public ServiceInstance(String instanceId, String serviceName, String host, int port) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName 不能为空");
        }
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host 不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("port 必须在 1..65535 之间");
        }
        this.instanceId = instanceId;
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
    }

    public String instanceId() { return instanceId; }
    public String serviceName() { return serviceName; }
    public String host() { return host; }
    public int port() { return port; }

    public synchronized InstanceStatus status() { return status; }
    public synchronized long registeredAt() { return registeredAt; }
    public synchronized long lastHeartbeatAt() { return lastHeartbeatAt; }
    public synchronized long becameUnhealthyAt() { return becameUnhealthyAt; }
    public synchronized long heartbeatVersion() { return heartbeatVersion; }

    /** 是否还会出现在候选列表里（含暂时不健康）；已注销/尚未注册为 false。 */
    public synchronized boolean isPresent() {
        return status == InstanceStatus.HEALTHY || status == InstanceStatus.UNHEALTHY;
    }

    public synchronized boolean isHealthy() {
        return status == InstanceStatus.HEALTHY;
    }

    /** 注册中心接受注册后调用。 */
    synchronized void markRegistered(long now) {
        this.status = InstanceStatus.HEALTHY;
        this.registeredAt = now;
        this.lastHeartbeatAt = now;
        this.becameUnhealthyAt = 0L;
        this.heartbeatVersion = 1L;
    }

    /**
     * 接收一条新鲜心跳。
     *
     * @throws RegistryException 若实例已被主动注销（DEREGISTERED_INSTANCE）
     */
    synchronized void acceptHeartbeat(long now) {
        if (status == InstanceStatus.DEREGISTERED) {
            throw new RegistryException(RegistryException.Reason.DEREGISTERED_INSTANCE, instanceId,
                    "实例 " + instanceId + " 已主动注销，心跳被拒绝");
        }
        if (status == InstanceStatus.UNHEALTHY) {
            becameUnhealthyAt = 0L;
        }
        status = InstanceStatus.HEALTHY;
        lastHeartbeatAt = now;
        heartbeatVersion++;
    }

    /** 主动注销，进入不可恢复终态。返回是否发生了状态迁移。 */
    synchronized boolean markDeregistered() {
        if (status == InstanceStatus.DEREGISTERED) {
            return false;
        }
        status = InstanceStatus.DEREGISTERED;
        return true;
    }

    /**
     * 依据当前时间刷新健康状态：
     * 超过 ttl 未收到心跳则 HEALTHY -> UNHEALTHY；已经 UNHEALTHY 时保持。
     *
     * @return 刷新后是否健康
     */
    synchronized boolean refreshHealth(long now, long heartbeatTtlMillis) {
        if (status == InstanceStatus.HEALTHY && now - lastHeartbeatAt > heartbeatTtlMillis) {
            status = InstanceStatus.UNHEALTHY;
            becameUnhealthyAt = now;
        }
        return status == InstanceStatus.HEALTHY;
    }

    /**
     * 是否已经超过清理时限：必须处于 UNHEALTHY，且距最后一次心跳已达到
     * 「ttl + evictAfterMillis」。以最后心跳为基准，避免实例曾被救活后被提前误清。
     */
    synchronized boolean isEvictable(long now, long heartbeatTtlMillis, long evictAfterMillis) {
        return status == InstanceStatus.UNHEALTHY
                && becameUnhealthyAt > 0
                && now - lastHeartbeatAt >= heartbeatTtlMillis + evictAfterMillis;
    }

    @Override
    public String toString() {
        return instanceId + "(" + serviceName + "@" + host + ":" + port + ")";
    }
}
