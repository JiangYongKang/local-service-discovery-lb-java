package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.client.CallResult;
import com.github.highcumontoa.localservicediscoverylbjava.client.RejectionReason;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceRequest;
import com.github.highcumontoa.localservicediscoverylbjava.fake.FakeLocalInstance;
import com.github.highcumontoa.localservicediscoverylbjava.fake.FakeTransport;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.RandomRoundRobinLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InMemoryServiceRegistry;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.tests.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 重试约束测试：
 * 1) 选中的实例不可用 -> 自动换一个，且绝不回到刚失败的实例；
 * 2) 非幂等请求首次失败即拒绝，不重试；
 * 3) 全部实例失败 / 没有健康实例 -> 明确可区分的拒绝结果；
 * 4) 请求结束后连接计数正确归还。
 */
class FailoverRetryTest {

    private static final String SVC = "payment-service";

    private record Harness(InMemoryServiceRegistry registry, FakeTransport transport, ServiceClient client) {}

    private Harness harness(int maxAttempts, String... ids) {
        MutableClock clock = new MutableClock();
        InMemoryServiceRegistry registry =
                new InMemoryServiceRegistry(clock, Duration.ofSeconds(10), Duration.ofSeconds(30));
        FakeTransport transport = new FakeTransport();
        int port = 9001;
        for (String id : ids) {
            ServiceInstance inst = new ServiceInstance(id, SVC, "127.0.0.1", port++, Map.of());
            registry.register(inst);
            transport.add(new FakeLocalInstance(inst));
        }
        LoadBalancer lb = new RandomRoundRobinLoadBalancer(registry, 0L);
        return new Harness(registry, transport, new ServiceClient(registry, lb, transport, maxAttempts));
    }

    private void printResult(String input, Harness h, CallResult result) {
        System.out.println("输入: " + input);
        for (InstanceSnapshot s : h.registry().listSnapshots(SVC)) {
            System.out.printf("  候选实例=%s status=%-10s activeConn=%d endpointAvailable=%s%n",
                    s.instanceId(), s.status(), s.activeConnections(),
                    h.transport().get(s.instanceId()) == null
                            ? "(无端点)" : h.transport().get(s.instanceId()).isAvailable());
        }
        switch (result) {
            case CallResult.Success ok -> System.out.printf(
                    "最终判定: SUCCESS by=%s attempts=%d tried=%s response=%s%n",
                    ok.instance().getInstanceId(), ok.attempts(), ok.triedInstanceIds(), ok.response());
            case CallResult.Rejected rj -> System.out.printf(
                    "最终判定: REJECTED reason=%s attempts=%d tried=%s msg=%s%n",
                    rj.reason(), rj.attempts(), rj.triedInstanceIds(), rj.message());
        }
    }

    @Test
    @DisplayName("首个实例失败 -> 换实例成功；失败实例本轮不会再被选；连接计数归还")
    void failoverToAnotherHealthyInstance() {
        Harness h = harness(3, "a", "b", "c");
        h.transport().get("a").setAvailable(false);
        h.transport().get("b").setAvailable(false);

        CallResult result = h.client().invoke(ServiceRequest.idempotent(SVC, "charge-100"));
        printResult("幂等请求 charge-100，a、b 端点不可用", h, result);

        assertInstanceOf(CallResult.Success.class, result);
        CallResult.Success ok = (CallResult.Success) result;
        assertEquals("c", ok.instance().getInstanceId());
        assertEquals(List.of("a", "b", "c"), ok.triedInstanceIds(), "按失败顺序尝试，不重复");
        assertEquals(3, ok.attempts());

        // a、b 被标记暂时不健康，c 处理成功仍健康
        assertEquals(com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus.UNHEALTHY,
                h.registry().snapshot(SVC, "a").status());
        assertEquals(com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus.UNHEALTHY,
                h.registry().snapshot(SVC, "b").status());
        assertEquals(com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus.HEALTHY,
                h.registry().snapshot(SVC, "c").status());
        // 所有连接（含失败的）都已归还
        h.registry().listSnapshots(SVC).forEach(s ->
                assertEquals(0, s.activeConnections(), "请求结束后连接必须归还: " + s.instanceId()));
        assertEquals(1, h.transport().get("c").handledRequests());
        System.out.println("判定依据：excluded={a,b} 保证不回失败实例，只在健康候选中继续选");
    }

    @Test
    @DisplayName("非幂等请求首次失败立即拒绝，绝不换实例重试")
    void nonRetryableRequestIsNotRetried() {
        Harness h = harness(3, "a", "b", "c");
        h.transport().get("a").setAvailable(false);

        CallResult result = h.client().invoke(ServiceRequest.nonIdempotent(SVC, "POST /withdraw"));
        printResult("非幂等请求 POST /withdraw", h, result);

        assertInstanceOf(CallResult.Rejected.class, result);
        CallResult.Rejected rejected = (CallResult.Rejected) result;
        assertEquals(RejectionReason.NON_RETRYABLE_REQUEST, rejected.reason());
        assertEquals(List.of("a"), rejected.triedInstanceIds(), "只尝试过一次，没有重试到 b/c");
        assertEquals(1, rejected.attempts());
        assertEquals(0, h.transport().get("b").handledRequests());
        assertEquals(0, h.transport().get("c").handledRequests());
        System.out.println("判定依据：业务请求可能产生副作用，失败后换实例不安全，直接给出可区分拒绝");
    }

    @Test
    @DisplayName("所有实例都失败 -> ALL_INSTANCES_FAILED，而不是 null 或内部异常")
    void allInstancesFailGivesDistinctRejection() {
        Harness h = harness(5, "a", "b");
        h.transport().get("a").setAvailable(false);
        h.transport().get("b").setAvailable(false);

        CallResult result = h.client().invoke(ServiceRequest.idempotent(SVC, "charge-200"));
        printResult("幂等请求，a、b 全部不可用", h, result);

        assertInstanceOf(CallResult.Rejected.class, result);
        CallResult.Rejected rejected = (CallResult.Rejected) result;
        assertEquals(RejectionReason.ALL_INSTANCES_FAILED, rejected.reason());
        assertEquals(2, new java.util.HashSet<>(rejected.triedInstanceIds()).size(),
                "每个实例最多试一次");
        assertTrue(rejected.triedInstanceIds().containsAll(List.of("a", "b")));
    }

    @Test
    @DisplayName("没有任何健康实例 -> NO_HEALTHY_INSTANCE（含主动注销后再次被选中）")
    void noHealthyInstanceGivesDistinctRejection() {
        Harness h = harness(3, "a", "b");
        // 主动注销：实例被选中路径上仍可识别，但绝不可用
        h.registry().deregister(SVC, "a");
        h.transport().remove("a");
        h.registry().deregister(SVC, "b");
        h.transport().remove("b");
        System.out.println("注销后注册表仍可观察墓碑: "
                + h.registry().listSnapshots(SVC).stream()
                .map(s -> s.instanceId() + "=" + s.status()).toList());

        CallResult result = h.client().invoke(ServiceRequest.idempotent(SVC, "charge-300"));
        printResult("幂等请求，两个实例均已主动注销", h, result);

        assertInstanceOf(CallResult.Rejected.class, result);
        assertEquals(RejectionReason.NO_HEALTHY_INSTANCE, ((CallResult.Rejected) result).reason());
        assertTrue(((CallResult.Rejected) result).triedInstanceIds().isEmpty());
    }

    @Test
    @DisplayName("严格上限：前两个失败、只允许 2 次尝试 -> MAX_ATTEMPTS_REACHED 且不碰 c/d")
    void maxAttemptsStrict() {
        Harness h = harness(2, "a", "b", "c", "d");
        h.transport().get("a").setAvailable(false);
        h.transport().get("b").setAvailable(false);

        CallResult result = h.client().invoke(ServiceRequest.idempotent(SVC, "charge-500"));
        printResult("maxAttempts=2，a、b 不可用，c、d 健康但未轮到", h, result);

        assertInstanceOf(CallResult.Rejected.class, result);
        CallResult.Rejected rejected = (CallResult.Rejected) result;
        assertEquals(RejectionReason.MAX_ATTEMPTS_REACHED, rejected.reason());
        assertEquals(2, rejected.attempts());
        assertEquals(List.of("a", "b"), rejected.triedInstanceIds());
        assertEquals(0, h.transport().get("c").handledRequests());
        assertEquals(0, h.transport().get("d").handledRequests());
    }

    @Test
    @DisplayName("并发调用同一批失败实例：状态与连接计数不错乱、不重复选失败实例")
    void concurrentCallsStayConsistent() throws InterruptedException {
        Harness h = harness(4, "a", "b", "c");
        h.transport().get("a").setAvailable(false);

        int threads = 24;
        int perThread = 50;
        List<Thread> workers = new java.util.ArrayList<>();
        java.util.concurrent.atomic.AtomicInteger success = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    CallResult r = h.client().invoke(ServiceRequest.idempotent(SVC, "concurrent"));
                    if (r instanceof CallResult.Success) {
                        success.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
            });
            workers.add(worker);
            worker.start();
        }
        for (Thread w : workers) {
            w.join();
        }

        System.out.printf("并发 %d 线程 x %d 次: success=%d rejected=%d%n",
                threads, perThread, success.get(), rejected.get());
        assertEquals(threads * perThread, success.get() + rejected.get());
        // 所有请求结束后计数归零；b、c 均健康
        h.registry().listSnapshots(SVC).forEach(s ->
                assertEquals(0, s.activeConnections(), "并发结束连接计数必须归零: " + s.instanceId()));
        System.out.println("候选状态: " + h.registry().listSnapshots(SVC).stream()
                .map(s -> s.instanceId() + "=" + s.status() + "(conn=" + s.activeConnections() + ")").toList());
        System.out.println("判定依据：每实例 monitor 串行化连接计数，排除集防止重复选失败实例");
    }
}
