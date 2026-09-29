package com.github.highcumontoa.localservicediscoverylbjava.local;

import com.github.highcumontoa.localservicediscoverylbjava.client.InvocationOutcome;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.lb.LeastConnectionsLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.lb.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.lb.RandomRoundRobinLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 本地演示入口（local.demo.enabled=true 时运行）：
 * 全部在同一进程、内存与回环概念内完成，确定性可控时钟驱动，无需等待真实时间。
 * 演示结束后关闭 Spring 容器，使进程自动退出（exit code 0），方便脚本化本地验证。
 */
public class LocalDemoRunner implements CommandLineRunner {

    private final ConfigurableApplicationContext applicationContext;

    public LocalDemoRunner(ConfigurableApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(String... args) {
        try {
            runDemo();
        } finally {
            // 演示模式下 Web 容器仍在监听随机端口；主动关闭以便本地一键验证后进程正常退出
            applicationContext.close();
        }
    }

    private void runDemo() {
        AtomicLong clock = new AtomicLong(0);
        RegistryConfig config = RegistryConfig.ofMillis(500, 1_500, clock::get);
        ServiceRegistry registry = new ServiceRegistry(config);
        LocalTransport transport = new LocalTransport();

        String svc = "order-service";
        ServiceInstance i1 = new ServiceInstance("inst-1", svc, "127.0.0.1", 9101);
        ServiceInstance i2 = new ServiceInstance("inst-2", svc, "127.0.0.1", 9102);
        ServiceInstance i3 = new ServiceInstance("inst-3", svc, "127.0.0.1", 9103);
        registry.register(i1);
        registry.register(i2);
        registry.register(i3);
        transport.registerNode(new LocalServiceNode("inst-1", LocalServiceNode.Behavior.HEALTHY));
        transport.registerNode(new LocalServiceNode("inst-2", LocalServiceNode.Behavior.HEALTHY));
        transport.registerNode(new LocalServiceNode("inst-3", LocalServiceNode.Behavior.HEALTHY));

        System.out.println("==== [1] 随机洗牌式轮询，连续 9 次选择 ====");
        RandomRoundRobinLoadBalancer rr = new RandomRoundRobinLoadBalancer();
        for (int k = 0; k < 9; k++) {
            ServiceInstance picked = rr.choose(registry.healthyInstances(svc));
            System.out.println("  第" + (k + 1) + "次 -> " + picked.instanceId());
        }

        System.out.println("==== [2] 最少连接：inst-1/inst-2 挂起不同在途连接数 ====");
        LeastConnectionsLoadBalancer lc =
                new LeastConnectionsLoadBalancer(transport::activeConnections);
        LocalServiceNode n1 = transport.node("inst-1");
        LocalServiceNode n2 = transport.node("inst-2");
        n1.blockNext(3);
        n2.blockNext(1);
        List<Thread> hangThreads = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            Thread th = new Thread(() -> transport.call(i1, "hang"), "hang-i1");
            th.start();
            hangThreads.add(th);
        }
        Thread hang2 = new Thread(() -> transport.call(i2, "hang"), "hang-i2");
        hang2.start();
        hangThreads.add(hang2);
        waitUntil(transport, "inst-1", 3);
        waitUntil(transport, "inst-2", 1);
        System.out.println("  活跃连接: inst-1=" + transport.activeConnections("inst-1")
                + ", inst-2=" + transport.activeConnections("inst-2")
                + ", inst-3=" + transport.activeConnections("inst-3")
                + " -> 选中 " + lc.choose(registry.healthyInstances(svc)).instanceId());
        n1.releaseBlocked();
        n2.releaseBlocked();
        for (Thread th : hangThreads) {
            try {
                th.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        System.out.println("  放行后活跃连接全部归零: inst-1=" + transport.activeConnections("inst-1")
                + ", inst-2=" + transport.activeConnections("inst-2"));

        System.out.println("==== [3] 心跳过期 -> 暂时不健康 -> 心跳救活 ====");
        clock.addAndGet(600);
        System.out.println("  t=600 健康候选: " + ids(registry.healthyInstances(svc)));
        registry.heartbeat("inst-2", clock.get());
        registry.heartbeat("inst-3", clock.get());
        System.out.println("  inst-2/inst-3 补心跳后健康候选: " + ids(registry.healthyInstances(svc)));

        System.out.println("==== [4] 最终过期被清理 ====");
        clock.addAndGet(1_500);
        int removed = registry.sweepExpired();
        System.out.println("  sweep 清理数量=" + removed + "（inst-1 最后心跳停在 t=0，达到 ttl+evictAfter=2000）"
                + "，剩余实例: "
                + registry.allInstances(svc).stream()
                .map(x -> x.instanceId() + "=" + x.status()).collect(Collectors.joining(", ")));

        System.out.println("==== [5] 主动注销后再被引用（与“被清理”严格区分）====");
        // inst-2 记录仍在册（UNHEALTHY）：重复注册被拒 ALREADY_REGISTERED
        try {
            registry.register(i2);
        } catch (RegistryException e) {
            System.out.println("  在册实例重复注册被拒: " + e.reason());
        }
        // inst-1 已被过期清理（记录消失）：同 id 可作为全新实例重新注册，不与注销终态混淆
        registry.register(i1);
        System.out.println("  被清理的 inst-1 同 id 重新注册成功（清理=记录消失；区别于注销终态），状态="
                + registry.allInstances(svc).stream().filter(x -> x.instanceId().equals("inst-1"))
                .findFirst().orElseThrow().status());
        // 主动注销 inst-2：终态，心跳/重复注销/重新注册都被 DEREGISTERED_INSTANCE 拒绝
        registry.deregister("inst-2");
        try {
            registry.heartbeat("inst-2", clock.get());
        } catch (RegistryException e) {
            System.out.println("  注销后心跳被拒: " + e.reason());
        }
        try {
            registry.register(i2);
        } catch (RegistryException e) {
            System.out.println("  对注销终态重新注册被拒: " + e.reason());
        }

        System.out.println("==== [6] 失败后自动换实例重试（不回头）====");
        registry.heartbeat("inst-3", clock.get()); // inst-3 恢复健康
        transport.node("inst-1").behavior(LocalServiceNode.Behavior.CONN_DOWN);
        transport.node("inst-3").behavior(LocalServiceNode.Behavior.HEALTHY);
        // 演示用确定性策略：候选按 id 排序后总取第一个，先命中故障的 inst-1，再换到 inst-3
        LoadBalancer ordered = new LoadBalancer() {
            @Override
            public String name() {
                return "ORDERED_FOR_DEMO";
            }

            @Override
            public ServiceInstance choose(java.util.List<ServiceInstance> healthyCandidates) {
                return healthyCandidates.get(0);
            }
        };
        ServiceClient client = new ServiceClient(registry, ordered, transport, 5);
        InvocationOutcome outcome = client.invoke(svc, "hello", true);
        System.out.println("  success=" + outcome.success()
                + " selected=" + (outcome.selectedInstance() == null ? "-" : outcome.selectedInstance().instanceId())
                + " 尝试序列=" + outcome.attemptedInstanceIds()
                + (outcome.success() ? " 响应=" + outcome.response() : " 拒绝原因=" + outcome.rejectReason()));

        System.out.println("==== [7] 无可用实例时的明确拒绝 ====");
        transport.node("inst-1").behavior(LocalServiceNode.Behavior.HEALTHY);
        registry.deregister("inst-1");
        registry.deregister("inst-3");
        InvocationOutcome none = client.invoke(svc, "hello", true);
        System.out.println("  全部注销: success=" + none.success() + " 拒绝原因=" + none.rejectReason()
                + " 说明=" + none.message());

        InvocationOutcome never = client.invoke("ghost-service", "x", true);
        System.out.println("  从未注册的服务: success=" + never.success()
                + " 拒绝原因=" + never.rejectReason() + " 说明=" + never.message());
        System.out.println("==== 演示结束 ====");
    }

    /** 等待某实例的活跃连接数达到期望值（挂起调用已真正建立）。 */
    private static void waitUntil(LocalTransport transport, String id, int expected) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (transport.activeConnections(id) < expected && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (transport.activeConnections(id) < expected) {
            throw new IllegalStateException("等待 " + id + " 活跃连接达到 " + expected + " 超时");
        }
    }

    private static String ids(List<ServiceInstance> list) {
        return list.stream().map(ServiceInstance::instanceId).collect(Collectors.joining(",", "[", "]"));
    }
}
