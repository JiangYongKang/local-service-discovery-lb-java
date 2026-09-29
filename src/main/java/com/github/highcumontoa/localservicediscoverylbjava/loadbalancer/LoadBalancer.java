package com.github.highcumontoa.localservicediscoverylbjava.loadbalancer;

import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;

import java.util.Set;

public interface LoadBalancer {

    LoadBalanceStrategy strategy();

    /**
     * 在健康实例中选一个；excluded 中的实例（例如刚失败过的）不会再被选中。
     * 没有候选返回 null（由调用方转换为明确的拒绝结果）。
     */
    InstanceSnapshot select(String serviceName, Set<String> excludedInstanceIds);

    /** 重置内部游标到初始状态（仅供本地验证/测试获得确定性起点）。 */
    default void reset() {
    }

    /** 重置后首次选择固定落在排序后的第一个候选（仅供本地验证/测试）。 */
    default void resetToFirst() {
        reset();
    }
}
