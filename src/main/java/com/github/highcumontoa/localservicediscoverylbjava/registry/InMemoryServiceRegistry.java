package com.github.highcumontoa.localservicediscoverylbjava.registry;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯本机内存注册中心实现。
 *
 * <p>关键规则：
 * <ul>
 *   <li>每个服务一张实例表；每个实例用独立 monitor 串行化它的注册/心跳/注销/连接计数，
 *       因此同一实例在并发操作下状态和统计不会错乱。</li>
 *   <li>心跳超过 ttl 没到达：HEALTHY -> UNHEALTHY（暂时不健康，可恢复）。</li>
 *   <li>暂时不健康超过 evictAfter：彻底从注册表清理，之后允许同 ID 重新注册。</li>
 *   <li>主动注销：置为 DEREGISTERED 墓碑且永不清理，同 ID 不允许再注册、心跳不能复活。</li>
 *   <li>心跳携带单调递增 seq，迟到的旧心跳被拒绝，不能刷新最后心跳时间。</li>
 * </ul>
 */
public class InMemoryServiceRegistry implements ServiceRegistry {

    private final Clock clock;
    private final Duration heartbeatTtl;
    private final Duration evictAfter;
    private final Map<String, Map<String, Entry>> services = new ConcurrentHashMap<>();

    public InMemoryServiceRegistry(Clock clock, Duration heartbeatTtl, Duration evictAfter) {
        this.clock = clock;
        this.heartbeatTtl = heartbeatTtl;
        this.evictAfter = evictAfter;
    }

    private Map<String, Entry> tableOf(String serviceName) {
        return services.computeIfAbsent(serviceName, k -> new ConcurrentHashMap<>());
    }

    @Override
    public void register(ServiceInstance instance) {
        Entry entry = new Entry(instance, clock.millis());
        Entry previous = tableOf(instance.getServiceName())
                .putIfAbsent(instance.getInstanceId(), entry);
        if (previous == null) {
            return;
        }
        synchronized (previous) {
            switch (previous.status) {
                case DEREGISTERED -> throw new RegistrationException(
                        RegistrationFailureReason.DEREGISTERED_INSTANCE,
                        "instance " + instance.getInstanceId()
                                + " was explicitly deregistered and cannot be re-registered (tombstone kept)");
                default -> throw new RegistrationException(
                        RegistrationFailureReason.ALREADY_REGISTERED,
                        "instance " + instance.getInstanceId()
                                + " is already registered with status " + previous.status);
            }
        }
    }

    @Override
    public void deregister(String serviceName, String instanceId) {
        // 幂等：已注销/不存在都不报错；墓碑留在表中保证后续引用可被识别并拒绝。
        Entry entry = tableOf(serviceName).get(instanceId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            entry.status = InstanceStatus.DEREGISTERED;
            entry.activeConnections = 0;
            entry.deregisteredAtNanos = clock.millis();
        }
    }

    @Override
    public void heartbeat(String serviceName, String instanceId, long seq) {
        Entry entry = tableOf(serviceName).get(instanceId);
        if (entry == null) {
            throw new HeartbeatException(HeartbeatFailureReason.UNKNOWN_INSTANCE,
                    "heartbeat for unknown instance " + instanceId
                            + " (never registered or expired and evicted)");
        }
        long now = clock.millis();
        synchronized (entry) {
            switch (entry.status) {
                case DEREGISTERED -> throw new HeartbeatException(
                        HeartbeatFailureReason.DEREGISTERED_INSTANCE,
                        "heartbeat rejected: instance " + instanceId + " was explicitly deregistered");
                default -> {
                    if (seq <= entry.lastHeartbeatSeq) {
                        throw new HeartbeatException(HeartbeatFailureReason.STALE_HEARTBEAT,
                                "stale heartbeat seq=" + seq + " for instance " + instanceId
                                        + ", last accepted seq=" + entry.lastHeartbeatSeq);
                    }
                    entry.lastHeartbeatSeq = seq;
                    entry.lastHeartbeatAtNanos = now;
                    // 心跳过期只是暂时不健康：重新收到心跳即恢复
                    entry.status = InstanceStatus.HEALTHY;
                }
            }
        }
    }

    @Override
    public InstanceSnapshot snapshot(String serviceName, String instanceId) {
        Entry entry = tableOf(serviceName).get(instanceId);
        return entry == null ? null : entry.toSnapshot();
    }

    @Override
    public List<InstanceSnapshot> listSnapshots(String serviceName) {
        List<InstanceSnapshot> result = new ArrayList<>();
        for (Entry entry : tableOf(serviceName).values()) {
            result.add(entry.toSnapshot());
        }
        return result;
    }

    @Override
    public List<InstanceSnapshot> listHealthy(String serviceName) {
        List<InstanceSnapshot> result = new ArrayList<>();
        for (Entry entry : tableOf(serviceName).values()) {
            InstanceSnapshot snapshot = entry.toSnapshot();
            if (snapshot.status() == InstanceStatus.HEALTHY) {
                result.add(snapshot);
            }
        }
        result.sort(Comparator
                .comparingLong(InstanceSnapshot::activeConnections)
                .thenComparing(InstanceSnapshot::instanceId));
        return result;
    }

    @Override
    public long acquireConnection(String serviceName, String instanceId) {
        Entry entry = tableOf(serviceName).get(instanceId);
        if (entry == null) {
            return -1;
        }
        synchronized (entry) {
            if (entry.status != InstanceStatus.HEALTHY) {
                return -1;
            }
            return ++entry.activeConnections;
        }
    }

    @Override
    public void releaseConnection(String serviceName, String instanceId) {
        Entry entry = tableOf(serviceName).get(instanceId);
        if (entry == null) {
            return; // 实例可能已被过期清理，归还直接忽略
        }
        synchronized (entry) {
            if (entry.activeConnections > 0) {
                entry.activeConnections--;
            }
        }
    }

    @Override
    public void markUnhealthy(String serviceName, String instanceId) {
        Entry entry = tableOf(serviceName).get(instanceId);
        if (entry == null) {
            return;
        }
        synchronized (entry) {
            // 主动注销是终态，调用失败标记不得覆盖墓碑
            if (entry.status != InstanceStatus.DEREGISTERED) {
                entry.status = InstanceStatus.UNHEALTHY;
            }
        }
    }

    @Override
    public List<String> evictExpired(String serviceName) {
        List<String> evicted = new ArrayList<>();
        Map<String, Entry> table = tableOf(serviceName);
        for (Map.Entry<String, Entry> mapEntry : table.entrySet()) {
            Entry entry = mapEntry.getValue();
            synchronized (entry) {
                long now = clock.millis();
                if (entry.status == InstanceStatus.HEALTHY
                        && now - entry.lastHeartbeatAtNanos >= heartbeatTtl.toMillis()) {
                    entry.status = InstanceStatus.UNHEALTHY;
                    entry.unhealthySinceNanos = now;
                }
                if (entry.status == InstanceStatus.UNHEALTHY
                        && entry.unhealthySinceNanos > 0
                        && now - entry.unhealthySinceNanos >= evictAfter.toMillis()) {
                    table.remove(entry.instance.getInstanceId(), entry);
                    evicted.add(entry.instance.getInstanceId());
                }
                // DEREGISTERED 墓碑永不清理
            }
        }
        return evicted;
    }

    /** 单条实例记录；所有可变字段只在持有自身 monitor 时读写。 */
    private static final class Entry {
        private final ServiceInstance instance;
        private final long registeredAtNanos;
        private volatile InstanceStatus status = InstanceStatus.HEALTHY;
        private long lastHeartbeatAtNanos;
        private long lastHeartbeatSeq;
        private long unhealthySinceNanos;
        private long deregisteredAtNanos;
        private long activeConnections;

        Entry(ServiceInstance instance, long now) {
            this.instance = instance;
            this.registeredAtNanos = now;
            this.lastHeartbeatAtNanos = now; // 注册即视为一次心跳
        }

        InstanceSnapshot toSnapshot() {
            synchronized (this) {
                return new InstanceSnapshot(instance, status, registeredAtNanos,
                        lastHeartbeatAtNanos, lastHeartbeatSeq, activeConnections);
            }
        }
    }
}
