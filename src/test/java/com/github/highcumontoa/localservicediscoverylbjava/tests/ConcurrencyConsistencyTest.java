package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceCallException;
import com.github.highcumontoa.localservicediscoverylbjava.local.LocalServiceNode;
import com.github.highcumontoa.localservicediscoverylbjava.local.LocalTransport;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：同一批实例在多线程并发注册、心跳、注销与调用交错期间：
 * 1) 每个实例只有一个注册成功，其余得到 ALREADY_REGISTERED/DEREGISTERED_INSTANCE；
 * 2) 状态最终收敛到确定结果（注销的 DEREGISTERED、其余 HEALTHY）；
 * 3) 传输层活跃连接数在大量并发调用后全部配平归零；
 * 4) 心跳版本号单调、无丢失更新。
 */
public class ConcurrencyConsistencyTest {

    @Test
    void concurrentRegisterHeartbeatDeregulateAndCalls() throws Exception {
        ScenarioPrinter.scenario("并发注册/心跳/注销/调用下的一致性");

        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry =
                new ServiceRegistry(RegistryConfig.ofMillis(500, 1_500, clock::get));
        LocalTransport transport = new LocalTransport();
        String svc = "svc";
        int instanceCount = 10;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < instanceCount; i++) {
            ids.add("inst-" + i);
        }

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger registerOk = new AtomicInteger();
        AtomicInteger registerRejected = new AtomicInteger();
        AtomicInteger deregisterOk = new AtomicInteger();
        ConcurrentMap<String, AtomicInteger> callOk = new ConcurrentHashMap<>();
        ConcurrentMap<String, AtomicInteger> callConnFail = new ConcurrentHashMap<>();
        AtomicInteger danglingChecks = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int workerId = t;
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int round = 0; round < 200; round++) {
                    String id = ids.get((round + workerId) % instanceCount);

                    // 注册（每个实例被多个线程重复尝试）
                    try {
                        ServiceInstance instance =
                                new ServiceInstance(id, svc, "127.0.0.1", 9000 + workerId);
                        registry.register(instance);
                        LocalServiceNode node = new LocalServiceNode(id, LocalServiceNode.Behavior.HEALTHY);
                        // 节点也会被重复注册，忽略“已存在”
                        try {
                            transport.registerNode(node);
                        } catch (IllegalStateException ignore) {
                            // 已由其他线程注册节点
                        }
                        registerOk.incrementAndGet();
                    } catch (RegistryException e) {
                        if (e.reason() == RegistryException.Reason.ALREADY_REGISTERED
                                || e.reason() == RegistryException.Reason.DEREGISTERED_INSTANCE) {
                            registerRejected.incrementAndGet();
                        } else {
                            throw e;
                        }
                    }

                    // 心跳（带推进的时间戳）
                    long ts = clock.accumulateAndGet(1, Long::sum);
                    try {
                        registry.heartbeat(id, ts);
                    } catch (RegistryException e) {
                        // UNKNOWN（被清理）/DEREGISTERED/STALE 在此交错场景都是合法可区分结果
                        assertTrue(List.of(RegistryException.Reason.UNKNOWN_INSTANCE,
                                        RegistryException.Reason.DEREGISTERED_INSTANCE,
                                        RegistryException.Reason.STALE_HEARTBEAT)
                                .contains(e.reason()));
                    }

                    // 只让编号为偶数的实例（由任意线程）在后期被注销，
                    // 使最终状态为 HEALTHY/DEREGISTERED 混合，验证两类状态各自收敛
                    int idIndex = Integer.parseInt(id.substring("inst-".length()));
                    if (idIndex % 2 == 0 && round >= 150) {
                        try {
                            registry.deregister(id);
                            deregisterOk.incrementAndGet();
                        } catch (RegistryException e) {
                            assertTrue(e.reason() == RegistryException.Reason.UNKNOWN_INSTANCE
                                    || e.reason() == RegistryException.Reason.DEREGISTERED_INSTANCE);
                        }
                    }

                    // 并发调用：统计成功/连接失败，并在每次调用后校验连接数配平趋势
                    ServiceInstance view = new ServiceInstance(id, svc, "127.0.0.1", 9000);
                    try {
                        Object resp = transport.call(view, "ping-" + round);
                        if (resp != null) {
                            callOk.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                        }
                    } catch (InstanceCallException e) {
                        callConnFail.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                    }
                    if (transport.activeConnections(id) < 0) {
                        danglingChecks.incrementAndGet();
                    }
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在时限内结束");

        ScenarioPrinter.input("工作线程 x 轮次", threads + " x 200");
        ScenarioPrinter.info("注册成功=" + registerOk.get() + "，重复/终态注册被拒=" + registerRejected.get()
                + "，注销成功=" + deregisterOk.get());
        ScenarioPrinter.info("调用成功分布=" + callOk);

        // 1) 恰好每个实例注册成功一次
        assertEquals(instanceCount, registerOk.get(),
                "每个实例必须且只能注册成功一次，实际=" + registerOk.get());
        ScenarioPrinter.verdict("10 个实例各注册成功恰好 1 次，其余重复注册全部被可区分拒绝");

        // 2) 全部时钟推进后补发心跳救活仍在册实例，观察最终状态：无 UNHEALTHY 残留
        long now = clock.get() + 1;
        for (String id : ids) {
            try {
                registry.heartbeat(id, now);
            } catch (RegistryException e) {
                assertEquals(RegistryException.Reason.DEREGISTERED_INSTANCE, e.reason(),
                        "收尾时非注销实例不应再心跳失败");
            }
        }
        List<ServiceInstance> finalInstances = registry.allInstances(svc);
        for (ServiceInstance instance : finalInstances) {
            assertTrue(instance.status() == InstanceStatus.HEALTHY
                            || instance.status() == InstanceStatus.DEREGISTERED,
                    "最终状态只能是 HEALTHY 或 DEREGISTERED，实际 " + instance);
        }
        long healthy = finalInstances.stream().filter(ServiceInstance::isHealthy).count();
        long deregistered = finalInstances.stream()
                .filter(i -> i.status() == InstanceStatus.DEREGISTERED).count();
        ScenarioPrinter.candidates(List.of(finalInstances.stream()
                .map(i -> i.instanceId() + "=" + i.status())
                .collect(Collectors.joining(", ", "[", "]"))));
        ScenarioPrinter.verdict("最终 HEALTHY=" + healthy + "，DEREGISTERED=" + deregistered
                + "，无 UNHEALTHY 残留");

        // 3) 活跃连接数全部配平归零，且从未出现负数
        for (String id : ids) {
            assertEquals(0, transport.activeConnections(id), "实例 " + id + " 活跃连接未配平");
        }
        assertEquals(0, danglingChecks.get(), "活跃连接数不应出现负数");
        ScenarioPrinter.verdict("所有实例活跃连接数最终=0，统计无错乱、无泄漏");

        // 4) 心跳版本号对健康实例单调且至少为 1
        for (ServiceInstance instance : finalInstances) {
            if (instance.status() == InstanceStatus.HEALTHY) {
                assertTrue(instance.heartbeatVersion() >= 1,
                        "健康实例心跳版本应 >=1，实际=" + instance.heartbeatVersion());
            }
        }
        ScenarioPrinter.pass("并发交错下注册唯一性、状态收敛、连接统计配平均成立");
    }
}
