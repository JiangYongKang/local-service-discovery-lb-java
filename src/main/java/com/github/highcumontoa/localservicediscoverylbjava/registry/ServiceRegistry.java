package com.github.highcumontoa.localservicediscoverylbjava.registry;

import java.util.List;

/**
 * 本地内存注册中心。所有方法线程安全。
 */
public interface ServiceRegistry {

    /** 注册一个新实例；重复注册或复用已注销 ID 会以 {@link RegistrationFailureReason} 拒绝。 */
    void register(ServiceInstance instance);

    /** 主动注销：实例彻底下线，之后任何心跳、引用都必须被识别并拒绝。 */
    void deregister(String serviceName, String instanceId);

    /**
     * 上报心跳。单调递增的 seq 用于拒绝迟到的旧心跳。
     * 心跳可能让已过期的实例恢复为健康。
     */
    void heartbeat(String serviceName, String instanceId, long seq);

    /** 列出某服务全部实例（含非健康/墓碑）的当前快照，供观察与测试打印。 */
    List<InstanceSnapshot> listSnapshots(String serviceName);

    /** 仅返回当前健康的实例快照（按活跃连接数升序）。 */
    List<InstanceSnapshot> listHealthy(String serviceName);

    /** 取单个实例快照；不存在（被过期清理或从未注册）返回 null。 */
    InstanceSnapshot snapshot(String serviceName, String instanceId);

    /**
     * 活跃连接 +1，返回新值；仅健康实例可增加。
     * 实例不存在或当前非健康（并发下可能刚注销/过期）时返回 -1，调用方应换实例。
     */
    long acquireConnection(String serviceName, String instanceId);

    /** 活跃连接 -1（请求结束，无论成功失败都归还）；实例已被清理则忽略。 */
    void releaseConnection(String serviceName, String instanceId);

    /** 把实例标记为暂时不健康（调用失败时使用），区别于主动注销。 */
    void markUnhealthy(String serviceName, String instanceId);

    /**
     * 推进时钟：把心跳过期的健康实例转成暂时不健康；
     * 暂时不健康且超过最终清理时限的实例被彻底清理（之后允许同 ID 重新注册）。
     * 返回本轮被清理的实例 ID。
     */
    List<String> evictExpired(String serviceName);
}
