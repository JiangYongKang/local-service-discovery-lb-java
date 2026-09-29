package com.github.highcumontoa.localservicediscoverylbjava.lb;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 最少连接策略：选择当前活跃连接数最小的健康实例；
 * 多个实例并列最少时随机挑选一个（避免流量长期粘在同一实例上）。
 * 活跃连接数由 {@link ActiveConnectionCounter} 实时提供，连接增减后下一次选择立即反映。
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {

    /** 活跃连接数查询接口。 */
    @FunctionalInterface
    public interface ActiveConnectionCounter {
        int activeConnections(String instanceId);
    }

    private final ActiveConnectionCounter counter;

    public LeastConnectionsLoadBalancer(ActiveConnectionCounter counter) {
        if (counter == null) {
            throw new IllegalArgumentException("counter 不能为 null");
        }
        this.counter = counter;
    }

    @Override
    public String name() {
        return "LEAST_CONNECTIONS";
    }

    @Override
    public ServiceInstance choose(List<ServiceInstance> healthyCandidates) {
        if (healthyCandidates == null || healthyCandidates.isEmpty()) {
            throw new IllegalArgumentException("健康候选列表不能为空");
        }
        List<ServiceInstance> best = new ArrayList<>();
        int min = Integer.MAX_VALUE;
        for (ServiceInstance instance : healthyCandidates) {
            int active = counter.activeConnections(instance.instanceId());
            if (active < min) {
                min = active;
                best.clear();
                best.add(instance);
            } else if (active == min) {
                best.add(instance);
            }
        }
        if (best.size() == 1) {
            return best.get(0);
        }
        return best.get(ThreadLocalRandom.current().nextInt(best.size()));
    }
}
