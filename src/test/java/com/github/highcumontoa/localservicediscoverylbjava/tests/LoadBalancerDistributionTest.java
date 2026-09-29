package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LeastConnectionsLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.RandomRoundRobinLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InMemoryServiceRegistry;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.tests.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证：
 * 1) 随机化轮询在固定实例集上严格均匀轮转，不健康实例不参与；
 * 2) 最少连接始终选活跃连接最少者，连接数变化后选择结果跟着变；
 * 3) 两种策略的分布语义不同：轮询只看轮转位置，最少连接看实时计数。
 */
class LoadBalancerDistributionTest {

    private static final String SVC = "cart-service";

    private InMemoryServiceRegistry newRegistry() {
        return new InMemoryServiceRegistry(new MutableClock(), Duration.ofSeconds(10), Duration.ofSeconds(30));
    }

    private void register(InMemoryServiceRegistry registry, String... ids) {
        int port = 9001;
        for (String id : ids) {
            registry.register(new ServiceInstance(id, SVC, "127.0.0.1", port++, Map.of()));
        }
    }

    private Map<String, Integer> tally(LoadBalancer lb, int times) {
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < times; i++) {
            InstanceSnapshot picked = lb.select(SVC, Set.of());
            counts.merge(picked.instanceId(), 1, Integer::sum);
        }
        return counts;
    }

    private void printTally(String title, Map<String, Integer> counts) {
        System.out.println("---- " + title + " ----");
        counts.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> System.out.printf("  instance=%s 被选次数=%d%n", e.getKey(), e.getValue()));
    }

    @Test
    @DisplayName("随机化轮询：3 实例各选 12 次，每个恰好 12 次；排除/不健康后跳过")
    void randomRoundRobinIsUniformAndSkipsUnhealthy() {
        InMemoryServiceRegistry registry = newRegistry();
        register(registry, "a", "b", "c");
        LoadBalancer lb = new RandomRoundRobinLoadBalancer(registry);

        Map<String, Integer> counts = tally(lb, 36);
        printTally("随机化轮询 36 次选择（3 实例）", counts);
        assertEquals(Set.of("a", "b", "c"), counts.keySet());
        counts.values().forEach(v -> assertEquals(12, v, "轮询语义要求严格均匀，不能偏向某实例"));

        // 刚失败过的实例不会被再次选中
        Set<InstanceSnapshot> pickedWithoutB = new java.util.HashSet<>();
        for (int i = 0; i < 10; i++) {
            pickedWithoutB.add(lb.select(SVC, Set.of("b")));
        }
        assertTrue(pickedWithoutB.stream().noneMatch(s -> s.instanceId().equals("b")),
                "排除集内的实例永不被选中");
        assertTrue(pickedWithoutB.stream().anyMatch(s -> s.instanceId().equals("a")));
        assertTrue(pickedWithoutB.stream().anyMatch(s -> s.instanceId().equals("c")));

        // b 暂时不健康后，轮转只在 a、c 间进行
        registry.markUnhealthy(SVC, "b");
        System.out.println("候选实例状态: " + registry.listSnapshots(SVC).stream()
                .map(s -> s.instanceId() + "=" + s.status()).toList());
        Map<String, Integer> counts2 = tally(lb, 20);
        printTally("b 不健康后轮询 20 次", counts2);
        assertFalse(counts2.containsKey("b"), "非健康实例不得进入候选");
        assertEquals(10, counts2.get("a"));
        assertEquals(10, counts2.get("c"));
        System.out.println("判定依据：轮询只在当前健康候选集内按游标依次轮转，分布严格均匀");
    }

    @Test
    @DisplayName("最少连接：始终选活跃连接最少者；连接变化后结果立刻变化；恢复后重新参与")
    void leastConnectionsFollowsActiveCounts() {
        InMemoryServiceRegistry registry = newRegistry();
        register(registry, "a", "b", "c");
        LoadBalancer lb = new LeastConnectionsLoadBalancer(registry);

        // 初始都是 0 连接，多次选择应覆盖三个实例（并列随机）
        Set<String> initialPicks = new java.util.HashSet<>();
        for (int i = 0; i < 60; i++) {
            initialPicks.add(lb.select(SVC, Set.of()).instanceId());
        }
        System.out.println("0 连接并列时的选择集合: " + initialPicks);
        assertEquals(Set.of("a", "b", "c"), initialPicks, "并列最少时应随机分摊到各实例");

        // 模拟在 a 上挂 2 个在途连接、b 上挂 1 个
        registry.acquireConnection(SVC, "a");
        registry.acquireConnection(SVC, "a");
        registry.acquireConnection(SVC, "b");
        System.out.println("输入连接数: " + registry.listHealthy(SVC).stream()
                .map(s -> s.instanceId() + "=" + s.activeConnections()).toList());

        for (int i = 0; i < 20; i++) {
            assertEquals("c", lb.select(SVC, Set.of()).instanceId(),
                    "c 连接数最少(0)，必须始终选中 c");
        }
        System.out.println("a=2,b=1,c=0 时连续 20 次都选中 c");

        // c 的连接数上升为 3，最少变成 b(1)
        registry.acquireConnection(SVC, "c");
        registry.acquireConnection(SVC, "c");
        registry.acquireConnection(SVC, "c");
        for (int i = 0; i < 20; i++) {
            assertEquals("b", lb.select(SVC, Set.of()).instanceId(),
                    "活跃连接数变化后选择结果必须跟着变");
        }
        System.out.println("c 升到 3 后连续 20 次改选 b(=1)");

        // 归还连接：a=0 时变成最少
        registry.releaseConnection(SVC, "a");
        registry.releaseConnection(SVC, "a");
        for (int i = 0; i < 20; i++) {
            assertEquals("a", lb.select(SVC, Set.of()).instanceId(),
                    "连接归还后 a 回到最少，应被选中");
        }
        System.out.println("判定依据：最少连接每次读取实时 activeConnections，分布随在途压力变化");

        // 不健康实例即便连接数“看起来少”也不参与
        registry.markUnhealthy(SVC, "a");
        System.out.println("候选状态: " + registry.listSnapshots(SVC).stream()
                .map(s -> s.instanceId() + "=" + s.status() + "(conn=" + s.activeConnections() + ")").toList());
        for (int i = 0; i < 10; i++) {
            assertNotEquals("a", lb.select(SVC, Set.of()).instanceId());
        }
    }

    @Test
    @DisplayName("两种策略在同一批实例上的分布差异：轮询均匀 vs 最少连接偏向低连接实例")
    void strategySemanticsDiffer() {
        InMemoryServiceRegistry registry = newRegistry();
        register(registry, "a", "b", "c");
        // a 上长期有 5 个在途连接
        for (int i = 0; i < 5; i++) {
            registry.acquireConnection(SVC, "a");
        }

        Map<String, Integer> rr = tally(new RandomRoundRobinLoadBalancer(registry), 30);
        Map<String, Integer> lc = tally(new LeastConnectionsLoadBalancer(registry), 30);
        printTally("轮询分布（a 有 5 在途连接）", rr);
        printTally("最少连接分布（a 有 5 在途连接）", lc);

        assertEquals(10, rr.get("a"), "轮询不看连接数，仍然均匀分给 a");
        assertEquals(0, lc.getOrDefault("a", 0), "最少连接应完全避开高连接的 a");
        assertTrue(lc.get("b") + lc.get("c") == 30);
        System.out.println("判定依据：轮询分布=均匀；最少连接分布=全部落在连接数为 0 的 b、c");
    }
}
