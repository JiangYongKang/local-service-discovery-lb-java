package com.github.highcumontoa.localservicediscoverylbjava.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 纯内存本地注册中心：注册、心跳、注销与过期清理均在进程内完成，无任何外部依赖。
 *
 * <p>失败原因（{@link RegistryException.Reason}）互不混淆：
 * <ul>
 *   <li>{@code ALREADY_REGISTERED}：对仍在册（健康或暂时不健康）的实例重复注册；</li>
 *   <li>{@code UNKNOWN_INSTANCE}：心跳/注销指向一个从未注册或已被过期清理的实例；</li>
 *   <li>{@code DEREGISTERED_INSTANCE}：实例已主动注销（终态），心跳/重复注册/重复注销均被拒；</li>
 *   <li>{@code STALE_HEARTBEAT}：心跳时间戳早于已接受的最后心跳（迟到的旧心跳）。</li>
 * </ul>
 *
 * <p>加锁顺序始终为「注册表分段锁 -> 实例内置锁」，避免锁序倒置；所有统计/状态读写经实例锁串行化。
 */
public class ServiceRegistry {

    private final RegistryConfig config;
    private final Map<String, ServiceInstance> instances = new ConcurrentHashMap<>();

    public ServiceRegistry(RegistryConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config 不能为 null");
        }
        this.config = config;
    }

    /**
     * 注册新实例。重复注册在册实例抛 ALREADY_REGISTERED；
     * 对已主动注销的同 ID 实例重新注册抛 DEREGISTERED_INSTANCE（必须先显式处理，不允许静默覆盖）。
     */
    public void register(ServiceInstance instance) {
        if (instance == null) {
            throw new RegistryException(RegistryException.Reason.INVALID_ARGUMENT, null, "instance 不能为 null");
        }
        instances.compute(instance.instanceId(), (id, existing) -> {
            if (existing != null) {
                if (existing.status() == InstanceStatus.DEREGISTERED) {
                    throw new RegistryException(RegistryException.Reason.DEREGISTERED_INSTANCE, id,
                            "实例 " + id + " 已主动注销，不允许静默覆盖注册，请使用新的 instanceId");
                }
                throw new RegistryException(RegistryException.Reason.ALREADY_REGISTERED, id,
                        "实例 " + id + " 已在册（状态=" + existing.status() + "），重复注册被拒绝");
            }
            instance.markRegistered(config.ticker().nowMillis());
            return instance;
        });
    }

    /**
     * 发送心跳。
     *
     * @param heartbeatAt 心跳时间戳（通常取自测试时间源），早于最后接受心跳则视为迟到旧心跳
     */
    public void heartbeat(String instanceId, long heartbeatAt) {
        ServiceInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw new RegistryException(RegistryException.Reason.UNKNOWN_INSTANCE, instanceId,
                    "实例 " + instanceId + " 不存在或已被过期清理，心跳被拒绝");
        }
        synchronized (instance) {
            // 终态优先：注销后任何引用都必须得到明确的注销原因
            if (instance.status() == InstanceStatus.DEREGISTERED) {
                throw new RegistryException(RegistryException.Reason.DEREGISTERED_INSTANCE, instanceId,
                        "实例 " + instanceId + " 已主动注销，心跳被拒绝");
            }
            if (heartbeatAt < instance.lastHeartbeatAt()) {
                throw new RegistryException(RegistryException.Reason.STALE_HEARTBEAT, instanceId,
                        "迟到的旧心跳：heartbeatAt=" + heartbeatAt
                                + " 早于最后心跳=" + instance.lastHeartbeatAt());
            }
            instance.acceptHeartbeat(heartbeatAt);
        }
    }

    /** 使用注册中心时间源发送心跳（本地演示便捷方法）。 */
    public void heartbeat(String instanceId) {
        heartbeat(instanceId, config.ticker().nowMillis());
    }

    /** 主动注销：进入 DEREGISTERED 终态，记录保留以便后续引用得到明确失败原因。 */
    public void deregister(String instanceId) {
        ServiceInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw new RegistryException(RegistryException.Reason.UNKNOWN_INSTANCE, instanceId,
                    "实例 " + instanceId + " 不存在或已被过期清理，注销被拒绝");
        }
        synchronized (instance) {
            if (instance.status() == InstanceStatus.DEREGISTERED) {
                throw new RegistryException(RegistryException.Reason.DEREGISTERED_INSTANCE, instanceId,
                        "实例 " + instanceId + " 已处于注销终态，重复注销被拒绝");
            }
            instance.markDeregistered();
        }
    }

    /** 返回当前健康候选（先按 ttl 刷新状态），按 instanceId 排序以保证确定性。 */
    public List<ServiceInstance> healthyInstances(String serviceName) {
        long now = config.ticker().nowMillis();
        List<ServiceInstance> result = new ArrayList<>();
        for (ServiceInstance instance : instances.values()) {
            if (!instance.serviceName().equals(serviceName)) {
                continue;
            }
            synchronized (instance) {
                if (instance.refreshHealth(now, config.heartbeatTtlMillis())) {
                    result.add(instance);
                }
            }
        }
        result.sort(Comparator.comparing(ServiceInstance::instanceId));
        return result;
    }

    /** 返回该服务全部在册实例（含暂时不健康与已注销），用于观察/测试。 */
    public List<ServiceInstance> allInstances(String serviceName) {
        long now = config.ticker().nowMillis();
        List<ServiceInstance> result = new ArrayList<>();
        for (ServiceInstance instance : instances.values()) {
            if (!instance.serviceName().equals(serviceName)) {
                continue;
            }
            synchronized (instance) {
                instance.refreshHealth(now, config.heartbeatTtlMillis());
                result.add(instance);
            }
        }
        result.sort(Comparator.comparing(ServiceInstance::instanceId));
        return result;
    }

    /**
     * 扫描并清理「心跳丢失超过 evictAfterMillis」的实例：它们从注册表彻底消失，
     * 之后再收到心跳会得到 UNKNOWN_INSTANCE。已主动注销的记录保留（终态可追溯）。
     *
     * @return 本次被清理的实例数
     */
    public int sweepExpired() {
        long now = config.ticker().nowMillis();
        List<String> evictable = new ArrayList<>();
        for (ServiceInstance instance : instances.values()) {
            synchronized (instance) {
                instance.refreshHealth(now, config.heartbeatTtlMillis());
                if (instance.isEvictable(now, config.heartbeatTtlMillis(), config.evictAfterMillis())) {
                    evictable.add(instance.instanceId());
                }
            }
        }
        int removed = 0;
        for (String id : evictable) {
            // compute 内二次确认：心跳可能刚刚把实例救活
            boolean[] didRemove = {false};
            instances.computeIfPresent(id, (key, instance) -> {
                synchronized (instance) {
                    if (instance.isEvictable(config.ticker().nowMillis(), config.heartbeatTtlMillis(), config.evictAfterMillis())) {
                        didRemove[0] = true;
                        return null;
                    }
                }
                return instance;
            });
            if (didRemove[0]) {
                removed++;
            }
        }
        return removed;
    }
}
