package com.github.highcumontoa.localservicediscoverylbjava.lb;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 随机洗牌式轮询（randomized round robin）：
 * 对每个服务维护一份「随机洗牌后的实例队列」，依次出队；
 * 队列耗尽后对同一批成员重新洗牌。这样每个洗牌周期内每个实例恰好被选中一次，
 * 长期在同一批实例上趋于均匀分布（轮询语义），起点与批次顺序随机（随机语义）。
 *
 * <p>当健康候选成员集合发生变化（实例恢复/过期/注销导致进出）时，队列立即重建。
 * 本策略不做健康判定，健康过滤由调用方保证。
 */
public class RandomRoundRobinLoadBalancer implements LoadBalancer {

    private static final class Deck {
        private List<String> members = List.of();
        private List<String> order = List.of();
        private int pos;
    }

    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, Deck> decks = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "RANDOM_ROUND_ROBIN";
    }

    @Override
    public ServiceInstance choose(List<ServiceInstance> healthyCandidates) {
        if (healthyCandidates == null || healthyCandidates.isEmpty()) {
            throw new IllegalArgumentException("健康候选列表不能为空");
        }
        String serviceName = healthyCandidates.get(0).serviceName();
        List<String> members = healthyCandidates.stream()
                .map(ServiceInstance::instanceId)
                .sorted()
                .toList();
        Object lock = locks.computeIfAbsent(serviceName, k -> new Object());
        synchronized (lock) {
            Deck deck = decks.computeIfAbsent(serviceName, k -> new Deck());
            if (!deck.members.equals(members)) {
                deck.members = members;
                deck.order = shuffled(members);
                deck.pos = 0;
            } else if (deck.pos >= deck.order.size()) {
                deck.order = shuffled(members);
                deck.pos = 0;
            }
            String chosenId = deck.order.get(deck.pos++);
            for (ServiceInstance instance : healthyCandidates) {
                if (instance.instanceId().equals(chosenId)) {
                    return instance;
                }
            }
            // 理论不可达：成员与候选同源
            throw new IllegalStateException("洗牌结果不在候选列表中: " + chosenId);
        }
    }

    private static List<String> shuffled(List<String> members) {
        List<String> order = new ArrayList<>(members);
        Collections.shuffle(order);
        return order;
    }
}
