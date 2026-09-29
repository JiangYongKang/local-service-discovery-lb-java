package com.github.highcumontoa.localservicediscoverylbjava.loadbalancer;

import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 最少连接：在健康候选中选择 activeConnections 最小的实例。
 * 多个并列最少时在并列者中随机挑选，避免总是压同一个；
 * 因为每次选择都读取实时快照，活跃连接数一变化，选择结果立即跟着变化。
 */
public class LeastConnectionsLoadBalancer implements LoadBalancer {

    private final ServiceRegistry registry;

    public LeastConnectionsLoadBalancer(ServiceRegistry registry) {
        this.registry = registry;
    }

    @Override
    public LoadBalanceStrategy strategy() {
        return LoadBalanceStrategy.LEAST_CONNECTIONS;
    }

    @Override
    public InstanceSnapshot select(String serviceName, Set<String> excludedInstanceIds) {
        long minConnections = Long.MAX_VALUE;
        List<InstanceSnapshot> tied = new ArrayList<>();
        for (InstanceSnapshot snapshot : registry.listHealthy(serviceName)) {
            if (snapshot.status() != InstanceStatus.HEALTHY
                    || excludedInstanceIds.contains(snapshot.instanceId())) {
                continue;
            }
            if (snapshot.activeConnections() < minConnections) {
                minConnections = snapshot.activeConnections();
                tied.clear();
                tied.add(snapshot);
            } else if (snapshot.activeConnections() == minConnections) {
                tied.add(snapshot);
            }
        }
        if (tied.isEmpty()) {
            return null;
        }
        return tied.get(ThreadLocalRandom.current().nextInt(tied.size()));
    }
}
