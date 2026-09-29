package com.github.highcumontoa.localservicediscoverylbjava.loadbalancer;

import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * 随机化轮询。
 *
 * <p>游标记录的是“上次服务的实例 ID”，下次选择排序候选中它的后继。
 * 相比数字下标，这种方式在实例动态增减（失败转不健康、过期清理）时不会错位：
 * a 失败后候选变成 [b,c]，后继仍确定是 b，不会跳过从未试过的实例。
 * 第一次选择的起点随机（生产），也可显式指定起始下标（测试确定性）。
 */
public class RandomRoundRobinLoadBalancer implements LoadBalancer {

    private final ServiceRegistry registry;
    private final LongSupplier firstPickIndex;
    private final AtomicReference<String> lastServed = new AtomicReference<>();
    private volatile boolean fixedStart = false;

    public RandomRoundRobinLoadBalancer(ServiceRegistry registry) {
        this.registry = registry;
        this.firstPickIndex = () -> ThreadLocalRandom.current().nextLong(1L << 20);
    }

    /** 指定首次选择在候选列表中的起始下标（测试用确定性起点，0 = 第一个）。 */
    public RandomRoundRobinLoadBalancer(ServiceRegistry registry, long startOffset) {
        this.registry = registry;
        this.firstPickIndex = () -> startOffset;
    }

    @Override
    public LoadBalanceStrategy strategy() {
        return LoadBalanceStrategy.RANDOM_ROUND_ROBIN;
    }

    @Override
    public synchronized void reset() {
        lastServed.set(null);
        fixedStart = false;
    }

    @Override
    public synchronized void resetToFirst() {
        lastServed.set(null);
        fixedStart = true;
    }

    @Override
    public synchronized InstanceSnapshot select(String serviceName, Set<String> excludedInstanceIds) {
        List<InstanceSnapshot> candidates = healthyCandidates(serviceName, excludedInstanceIds);
        if (candidates.isEmpty()) {
            return null;
        }
        String last = lastServed.get();
        int index;
        if (last == null) {
            index = fixedStart
                    ? 0
                    : (int) Math.floorMod(firstPickIndex.getAsLong(), candidates.size());
        } else {
            // 上次服务实例在候选中的位置的后继；它已不在候选时，按 ID 顺序找后继
            int lastIndex = indexOf(candidates, last);
            if (lastIndex >= 0) {
                index = (lastIndex + 1) % candidates.size();
            } else {
                index = successorIndex(candidates, last);
            }
        }
        InstanceSnapshot picked = candidates.get(index);
        lastServed.set(picked.instanceId());
        return picked;
    }

    private int indexOf(List<InstanceSnapshot> candidates, String instanceId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).instanceId().equals(instanceId)) {
                return i;
            }
        }
        return -1;
    }

    /** 排序候选中第一个 ID 大于 lastId 的位置；没有则回到 0（环形）。 */
    private int successorIndex(List<InstanceSnapshot> candidates, String lastId) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).instanceId().compareTo(lastId) > 0) {
                return i;
            }
        }
        return 0;
    }

    private List<InstanceSnapshot> healthyCandidates(String serviceName, Set<String> excluded) {
        List<InstanceSnapshot> candidates = new ArrayList<>();
        for (InstanceSnapshot snapshot : registry.listHealthy(serviceName)) {
            if (snapshot.status() == InstanceStatus.HEALTHY
                    && !excluded.contains(snapshot.instanceId())) {
                candidates.add(snapshot);
            }
        }
        candidates.sort(Comparator.comparing(InstanceSnapshot::instanceId));
        return candidates;
    }
}
