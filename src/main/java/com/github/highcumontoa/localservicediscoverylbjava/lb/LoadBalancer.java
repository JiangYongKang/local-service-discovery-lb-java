package com.github.highcumontoa.localservicediscoverylbjava.lb;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.List;

/**
 * 负载均衡策略：只在给定的健康候选实例中做选择。
 */
public interface LoadBalancer {

    String name();

    /**
     * @param healthyCandidates 保证为非空且全部健康的候选
     * @return 选中的实例；骨架阶段返回 null
     */
    ServiceInstance choose(List<ServiceInstance> healthyCandidates);
}
