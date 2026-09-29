package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.lb.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.List;

/** 测试专用：总选候选列表第一个（注册中心已按 instanceId 排序），结果确定。 */
public class OrderedLoadBalancer implements LoadBalancer {
    @Override
    public String name() {
        return "ORDERED_FOR_TEST";
    }

    @Override
    public ServiceInstance choose(List<ServiceInstance> healthyCandidates) {
        return healthyCandidates.get(0);
    }
}
