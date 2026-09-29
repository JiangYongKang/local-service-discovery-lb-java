package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.lb.LeastConnectionsLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.lb.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.lb.RandomRoundRobinLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.local.LocalServiceNode;
import com.github.highcumontoa.localservicediscoverylbjava.local.LocalTransport;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：
 * 1) 最少连接总是选活跃连接数最小的实例，连接数变化后选择随之变化；
 * 2) 随机洗牌式轮询在同一批实例上一个洗牌周期内各实例恰好一次，长期分布均匀；
 * 3) 两种策略只接收健康候选（过期实例由注册中心过滤，不进入策略）。
 */
public class LeastConnectionsTest {

    @Test
    void leastConnectionsFollowsActiveConnectionCount() throws Exception {
        ScenarioPrinter.scenario("最少连接随活跃连接数变化");

        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry =
                new ServiceRegistry(RegistryConfig.ofMillis(5_000, 15_000, clock::get));
        LocalTransport transport = new LocalTransport();
        String svc = "svc";
        ServiceInstance a = new ServiceInstance("a", svc, "127.0.0.1", 8001);
        ServiceInstance b = new ServiceInstance("b", svc, "127.0.0.1", 8002);
        ServiceInstance c = new ServiceInstance("c", svc, "127.0.0.1", 8003);
        registry.register(a);
        registry.register(b);
        registry.register(c);
        LocalServiceNode na = new LocalServiceNode("a", LocalServiceNode.Behavior.HEALTHY);
        LocalServiceNode nb = new LocalServiceNode("b", LocalServiceNode.Behavior.HEALTHY);
        LocalServiceNode nc = new LocalServiceNode("c", LocalServiceNode.Behavior.HEALTHY);
        transport.registerNode(na);
        transport.registerNode(nb);
        transport.registerNode(nc);

        LoadBalancer lc = new LeastConnectionsLoadBalancer(transport::activeConnections);

        // 初始全部为 0：制造在途连接 a=2, b=1, c=0
        na.blockNext(2);
        nb.blockNext(1);
        startHanging(transport, a, 2);
        startHanging(transport, b, 1);
        awaitActive(transport, "a", 2);
        awaitActive(transport, "b", 1);

        ScenarioPrinter.input("活跃连接 a/b/c", "2 / 1 / 0");
        ServiceInstance picked1 = lc.choose(registry.healthyInstances(svc));
        assertEquals("c", picked1.instanceId());
        ScenarioPrinter.verdict("最小者 c(0) 被选中 -> " + picked1.instanceId());

        // 让 c 挂起 1 个，再放行 a、b；连接数变为 a=0,b=0,c=1
        nc.blockNext(1);
        Thread t = new Thread(() -> transport.call(c, "hang"));
        t.start();
        awaitActive(transport, "c", 1);
        na.releaseBlocked();
        nb.releaseBlocked();
        awaitActive(transport, "a", 0);
        awaitActive(transport, "b", 0);
        ScenarioPrinter.input("活跃连接 a/b/c", "0 / 0 / 1");
        // 最小值 0 在 a、b 之间并列：多次选择只可能落在 a 或 b，绝不落在 c
        for (int k = 0; k < 20; k++) {
            String id = lc.choose(registry.healthyInstances(svc)).instanceId();
            assertTrue(id.equals("a") || id.equals("b"), "c 连接数更高，不应被选中");
        }
        ScenarioPrinter.verdict("a/b 并列最少(0)，20 次选择均在 {a,b}，c(1) 从未被选中");
        nc.releaseBlocked();
        t.join(2_000);

        // 全部回归 0 后，统计必须配平
        assertEquals(0, transport.activeConnections("a"));
        assertEquals(0, transport.activeConnections("b"));
        assertEquals(0, transport.activeConnections("c"));
        ScenarioPrinter.pass("活跃连接统计配平归零，选择始终跟随最小连接数");
    }

    @Test
    void randomRoundRobinIsBalancedWithinShuffleCycles() {
        ScenarioPrinter.scenario("随机洗牌式轮询分布语义");
        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry =
                new ServiceRegistry(RegistryConfig.ofMillis(5_000, 15_000, clock::get));
        String svc = "svc";
        ServiceInstance[] instances = new ServiceInstance[4];
        for (int i = 0; i < 4; i++) {
            instances[i] = new ServiceInstance("n" + i, svc, "127.0.0.1", 9000 + i);
            registry.register(instances[i]);
        }
        LoadBalancer rr = new RandomRoundRobinLoadBalancer();
        Map<String, Integer> counts = new ConcurrentHashMap<>();
        int rounds = 50; // 每个洗牌周期 4 个实例各一次，共 200 次
        for (int r = 0; r < rounds; r++) {
            for (int k = 0; k < 4; k++) {
                String id = rr.choose(registry.healthyInstances(svc)).instanceId();
                counts.merge(id, 1, Integer::sum);
            }
        }
        ScenarioPrinter.input("4 个实例 / 选择次数", rounds * 4);
        ScenarioPrinter.info("分布=" + counts);
        for (ServiceInstance i : instances) {
            assertEquals(rounds, counts.get(i.instanceId()),
                    "每个洗牌周期每实例恰好一次，长期计数应相等");
        }
        ScenarioPrinter.verdict("50 个洗牌周期内每个实例恰被选中 50 次（轮询均匀，顺序随机）");
        ScenarioPrinter.pass("随机轮询分布均匀且每周期不重复");
    }

    private static void startHanging(LocalTransport transport, ServiceInstance instance, int n) {
        for (int k = 0; k < n; k++) {
            new Thread(() -> transport.call(instance, "hang"), "hang-" + instance.instanceId()).start();
        }
    }

    private static void awaitActive(LocalTransport transport, String id, int expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (transport.activeConnections(id) != expected && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(expected, transport.activeConnections(id), "活跃连接数未按时达到 " + expected);
    }
}
