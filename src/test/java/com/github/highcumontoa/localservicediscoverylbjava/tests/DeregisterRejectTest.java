package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceEndpoint;
import com.github.highcumontoa.localservicediscoverylbjava.client.InvocationOutcome;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.lb.RandomRoundRobinLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：主动注销（彻底下线）与心跳过期（暂时不健康）严格区分；
 * 重复注册、迟到/终态心跳、注销后又被引用都得到可区分的失败原因。
 */
public class DeregisterRejectTest {

    private static String states(ServiceRegistry registry, String svc) {
        return registry.allInstances(svc).stream()
                .map(i -> i.instanceId() + "=" + i.status())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    @Test
    void deregisterIsTerminalAndRejectionsAreDistinct() {
        ScenarioPrinter.scenario("主动注销终态 / 可区分的拒绝原因");

        AtomicLong clock = new AtomicLong(0);
        ServiceRegistry registry =
                new ServiceRegistry(RegistryConfig.ofMillis(500, 1_500, clock::get));
        String svc = "svc";
        ServiceInstance a = new ServiceInstance("a", svc, "127.0.0.1", 8001);
        ServiceInstance b = new ServiceInstance("b", svc, "127.0.0.1", 8002);
        registry.register(a);
        registry.register(b);

        // 1) 重复注册在册实例（即使它暂时不健康也不允许覆盖）
        RegistryException dup = assertThrows(RegistryException.class,
                () -> registry.register(a));
        assertEquals(RegistryException.Reason.ALREADY_REGISTERED, dup.reason());
        clock.set(600); // 全部过期为 UNHEALTHY
        RegistryException dupUnhealthy = assertThrows(RegistryException.class,
                () -> registry.register(a));
        assertEquals(RegistryException.Reason.ALREADY_REGISTERED, dupUnhealthy.reason());
        ScenarioPrinter.verdict("重复注册：健康/暂时不健康均拒绝 ALREADY_REGISTERED，不覆盖");

        registry.heartbeat("a", 600);
        registry.heartbeat("b", 600);

        // 2) 主动注销 b：进入 DEREGISTERED 终态
        registry.deregister("b");
        assertEquals(InstanceStatus.DEREGISTERED,
                registry.allInstances(svc).stream().filter(i -> i.instanceId().equals("b")).findFirst().get().status());
        ScenarioPrinter.candidates(List.of(states(registry, svc)));
        ScenarioPrinter.verdict("b 主动注销 -> DEREGISTERED（区别于 UNHEALTHY 的暂时不健康）");

        // 3) 注销后再发心跳 -> DEREGISTERED_INSTANCE，不会被“心跳救活”
        RegistryException hb = assertThrows(RegistryException.class,
                () -> registry.heartbeat("b", 1_000));
        assertEquals(RegistryException.Reason.DEREGISTERED_INSTANCE, hb.reason());
        ScenarioPrinter.verdict("注销后的心跳被拒 DEREGISTERED_INSTANCE，不可救活");

        // 4) 重复注销 -> DEREGISTERED_INSTANCE
        RegistryException d2 = assertThrows(RegistryException.class, () -> registry.deregister("b"));
        assertEquals(RegistryException.Reason.DEREGISTERED_INSTANCE, d2.reason());
        ScenarioPrinter.verdict("重复注销被拒 DEREGISTERED_INSTANCE");

        // 5) 用同一实例对象/同一 ID 重新注册 -> DEREGISTERED_INSTANCE，禁止静默覆盖
        RegistryException rereg = assertThrows(RegistryException.class,
                () -> registry.register(b));
        assertEquals(RegistryException.Reason.DEREGISTERED_INSTANCE, rereg.reason());
        ScenarioPrinter.verdict("对终态实例重新注册被拒 DEREGISTERED_INSTANCE");

        // 6) 不存在 / 已被清理的实例引用 -> UNKNOWN_INSTANCE
        RegistryException unknownHb = assertThrows(RegistryException.class,
                () -> registry.heartbeat("ghost", 1_000));
        RegistryException unknownDe = assertThrows(RegistryException.class,
                () -> registry.deregister("ghost"));
        assertEquals(RegistryException.Reason.UNKNOWN_INSTANCE, unknownHb.reason());
        assertEquals(RegistryException.Reason.UNKNOWN_INSTANCE, unknownDe.reason());
        ScenarioPrinter.verdict("未知实例的心跳/注销 -> UNKNOWN_INSTANCE");

        // 7) 注销实例不再进入健康候选；调用方只能选中 a
        List<ServiceInstance> healthy = registry.healthyInstances(svc);
        assertEquals(1, healthy.size());
        assertEquals("a", healthy.get(0).instanceId());
        InstanceEndpoint endpoint = (instance, request) -> "ok:" + instance.instanceId();
        ServiceClient client = new ServiceClient(registry, new RandomRoundRobinLoadBalancer(), endpoint, 3);
        InvocationOutcome outcome = client.invoke(svc, "req", true);
        assertTrue(outcome.success());
        assertEquals("a", outcome.selectedInstance().instanceId());
        ScenarioPrinter.candidates(List.of(states(registry, svc)));
        ScenarioPrinter.verdict("注销后的 b 永不进入候选，调用只落在 a");

        // 8) 全部注销后调用：明确拒绝 NO_HEALTHY_INSTANCE，而非 null/内部异常
        registry.deregister("a");
        InvocationOutcome rejected = client.invoke(svc, "req", true);
        assertFalse(rejected.success());
        assertEquals(InvocationOutcome.RejectReason.NO_HEALTHY_INSTANCE, rejected.rejectReason());
        ScenarioPrinter.verdict("全部注销后调用拒绝原因=" + rejected.rejectReason());
        ScenarioPrinter.pass("主动注销与心跳过期的失败原因全部可区分，注销后不会被再次选中");
    }
}
