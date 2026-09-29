package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceEndpoint;
import com.github.highcumontoa.localservicediscoverylbjava.client.InvocationOutcome;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.local.LocalServiceNode;
import com.github.highcumontoa.localservicediscoverylbjava.local.LocalTransport;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：
 * 1) 选中实例连接失败时自动换另一个健康实例重试，且绝不回到已失败实例；
 * 2) 业务失败时，不可重试请求只试一次，可重试请求才换实例；
 * 3) 全部失败 / 达到上限 / 无注册 / 无健康实例分别给出可区分拒绝结果；
 * 4) 重试间隙实例被注销/过期时，不会再选到它。
 */
public class RetryFailoverTest {

    private ServiceRegistry newRegistry(AtomicLong clock) {
        return new ServiceRegistry(RegistryConfig.ofMillis(5_000, 15_000, clock::get));
    }

    private void seed(ServiceRegistry registry, LocalTransport transport, String svc, int n) {
        for (int i = 1; i <= n; i++) {
            String id = "i" + i;
            registry.register(new ServiceInstance(id, svc, "127.0.0.1", 9000 + i));
            transport.registerNode(new LocalServiceNode(id, LocalServiceNode.Behavior.HEALTHY));
        }
    }

    @Test
    void connectionFailureFailsOverToAnotherInstance() {
        ScenarioPrinter.scenario("连接失败 -> 换实例重试且不回头");
        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry = newRegistry(clock);
        LocalTransport transport = new LocalTransport();
        String svc = "svc";
        seed(registry, transport, svc, 3);

        // 前两个健康实例（按 id 排序候选为 i1,i2,i3）模拟连接失败
        transport.node("i1").behavior(LocalServiceNode.Behavior.CONN_DOWN);
        transport.node("i2").behavior(LocalServiceNode.Behavior.CONN_DOWN);

        ServiceClient client = new ServiceClient(registry, new OrderedLoadBalancer(), transport, 5);
        InvocationOutcome outcome = client.invoke(svc, "req", true);

        ScenarioPrinter.input("节点状态", "i1=CONN_DOWN, i2=CONN_DOWN, i3=HEALTHY");;
        ScenarioPrinter.info("尝试序列=" + outcome.attemptedInstanceIds());
        assertTrue(outcome.success(), "第三次换到 i3 应成功");
        assertEquals("i3", outcome.selectedInstance().instanceId());
        assertEquals(3, outcome.attemptCount());
        assertEquals(3, outcome.attemptedInstanceIds().size());
        assertEquals(outcome.attemptedInstanceIds().stream().distinct().count(),
                outcome.attemptedInstanceIds().size(), "失败实例不得被再次选择");
        ScenarioPrinter.verdict("i1/i2 连接失败后换到 i3 成功，尝试序列无重复实例");
        ScenarioPrinter.pass("自动故障转移成立，且不回到刚失败的实例");
    }

    @Test
    void nonRetryableBusinessFailureIsNotRetried() {
        ScenarioPrinter.scenario("不可重试请求不反复重试");
        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry = newRegistry(clock);
        LocalTransport transport = new LocalTransport();
        String svc = "svc";
        seed(registry, transport, svc, 3);
        transport.node("i1").behavior(LocalServiceNode.Behavior.BUSINESS_ERROR);

        ServiceClient client = new ServiceClient(registry, new OrderedLoadBalancer(), transport, 5);

        InvocationOutcome nonRetryable = client.invoke(svc, "req", false);
        assertFalse(nonRetryable.success());
        assertEquals(InvocationOutcome.RejectReason.NON_RETRYABLE_REQUEST, nonRetryable.rejectReason());
        assertEquals(1, nonRetryable.attemptCount(), "不可重试请求只允许尝试一次");
        ScenarioPrinter.verdict("业务失败 + retryable=false -> " + nonRetryable.rejectReason()
                + "，仅尝试 " + nonRetryable.attemptedInstanceIds());

        InvocationOutcome retryable = client.invoke(svc, "req", true);
        // i1 业务失败后换 i2（健康正常），应当成功，且 i1 不被回头重试
        ScenarioPrinter.info("可重试请求尝试序列=" + retryable.attemptedInstanceIds());
        assertTrue(retryable.success());
        assertEquals("i2", retryable.selectedInstance().instanceId());
        assertEquals(List.of("i1", "i2"), retryable.attemptedInstanceIds());
        ScenarioPrinter.verdict("业务失败 + retryable=true 时换到 i2 成功");
        ScenarioPrinter.pass("不可重试请求首次失败即拒绝，可重试请求才换实例");
    }

    @Test
    void distinctRejectionsWhenNoInstanceAvailable() {
        ScenarioPrinter.scenario("无可用实例时的可区分拒绝结果");
        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry = newRegistry(clock);
        LocalTransport transport = new LocalTransport();
        String svc = "svc";
        seed(registry, transport, svc, 2);

        ServiceClient client = new ServiceClient(registry, new OrderedLoadBalancer(), transport, 5);

        // 从未注册的服务
        InvocationOutcome never = client.invoke("ghost", "x", true);
        assertFalse(never.success());
        assertEquals(InvocationOutcome.RejectReason.NO_INSTANCE_REGISTERED, never.rejectReason());
        ScenarioPrinter.verdict("未注册服务 -> " + never.rejectReason());

        // 全部连接失败 -> 健康实例都试过
        transport.node("i1").behavior(LocalServiceNode.Behavior.CONN_DOWN);
        transport.node("i2").behavior(LocalServiceNode.Behavior.CONN_DOWN);
        InvocationOutcome allFailed = client.invoke(svc, "x", true);
        assertFalse(allFailed.success());
        assertEquals(InvocationOutcome.RejectReason.ALL_INSTANCES_FAILED, allFailed.rejectReason());
        assertEquals(2, allFailed.attemptedInstanceIds().size());
        ScenarioPrinter.verdict("健康实例全部连接失败 -> " + allFailed.rejectReason()
                + "，尝试=" + allFailed.attemptedInstanceIds());

        // 全部注销 -> NO_HEALTHY_INSTANCE
        transport.node("i1").behavior(LocalServiceNode.Behavior.HEALTHY);
        transport.node("i2").behavior(LocalServiceNode.Behavior.HEALTHY);
        registry.deregister("i1");
        registry.deregister("i2");
        InvocationOutcome noHealthy = client.invoke(svc, "x", true);
        assertFalse(noHealthy.success());
        assertEquals(InvocationOutcome.RejectReason.NO_HEALTHY_INSTANCE, noHealthy.rejectReason());
        ScenarioPrinter.verdict("全部注销 -> " + noHealthy.rejectReason());

        // 达到最大尝试次数（5 个实例全部连接失败，只允许尝试 2 次）
        ServiceRegistry r2 = newRegistry(clock);
        LocalTransport t2 = new LocalTransport();
        seed(r2, t2, svc, 5);
        for (int i = 1; i <= 5; i++) {
            t2.node("i" + i).behavior(LocalServiceNode.Behavior.CONN_DOWN);
        }
        ServiceClient limited = new ServiceClient(r2, new OrderedLoadBalancer(), t2, 2);
        InvocationOutcome limit = limited.invoke(svc, "x", true);
        assertFalse(limit.success());
        assertEquals(InvocationOutcome.RejectReason.RETRY_LIMIT_REACHED, limit.rejectReason());
        assertEquals(2, limit.attemptCount());
        ScenarioPrinter.verdict("maxAttempts=2 且持续失败 -> " + limit.rejectReason());
        ScenarioPrinter.pass("四种无可用/失败情形的拒绝原因彼此可区分，均不返回 null 或抛内部异常");
    }

    @Test
    void instanceDeregisteredDuringRetryIsNotReselected() {
        ScenarioPrinter.scenario("重试间隙实例注销 -> 不再选中它");
        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry = newRegistry(clock);
        String svc = "svc";
        ServiceInstance i1 = new ServiceInstance("i1", svc, "127.0.0.1", 9001);
        ServiceInstance i2 = new ServiceInstance("i2", svc, "127.0.0.1", 9002);
        registry.register(i1);
        registry.register(i2);

        // 第一次调用在 i1 上连接失败，并在返回失败前把 i2 注销；随后不应再选中 i2
        InstanceEndpoint endpoint = (instance, request) -> {
            if (instance.instanceId().equals("i1")) {
                registry.deregister("i2");
                throw new com.github.highcumontoa.localservicediscoverylbjava.client.InstanceCallException(
                        com.github.highcumontoa.localservicediscoverylbjava.client.InstanceCallException.Kind.CONNECTION_FAILURE,
                        "i1 不可用");
            }
            return "ok:" + instance.instanceId();
        };
        ServiceClient client = new ServiceClient(registry, new OrderedLoadBalancer(), endpoint, 5);
        InvocationOutcome outcome = client.invoke(svc, "req", true);
        ScenarioPrinter.info("尝试序列=" + outcome.attemptedInstanceIds()
                + "，在册=" + registry.allInstances(svc).stream()
                .map(x -> x.instanceId() + "=" + x.status()).collect(Collectors.joining(",")));
        assertFalse(outcome.success());
        assertEquals(InvocationOutcome.RejectReason.ALL_INSTANCES_FAILED, outcome.rejectReason());
        assertEquals(List.of("i1"), outcome.attemptedInstanceIds(), "i2 在重试前已注销，不应被尝试");
        ScenarioPrinter.verdict("i1 失败、i2 同期被注销 -> 不选 i2，拒绝=" + outcome.rejectReason());
        ScenarioPrinter.pass("重试始终基于新鲜健康快照，失效实例不会被选中");
    }
}
