package com.github.highcumontoa.localservicediscoverylbjava.tests;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景：心跳过期 -> 暂时不健康 -> 新鲜心跳恢复；迟到旧心跳被拒；最终过期被清理。
 */
public class HeartbeatExpiryRecoveryTest {

    private static String states(ServiceRegistry registry, String svc) {
        return registry.allInstances(svc).stream()
                .map(i -> i.instanceId() + "=" + i.status())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    @Test
    void heartbeatExpiryRecoveryAndEviction() {
        ScenarioPrinter.scenario("心跳过期 / 恢复 / 最终清理");

        AtomicLong clock = new AtomicLong(0);
        RegistryConfig config = RegistryConfig.ofMillis(500, 1_500, clock::get);
        ServiceRegistry registry = new ServiceRegistry(config);
        String svc = "svc";

        ServiceInstance a = new ServiceInstance("a", svc, "127.0.0.1", 8001);
        ServiceInstance b = new ServiceInstance("b", svc, "127.0.0.1", 8002);
        registry.register(a);
        registry.register(b);
        ScenarioPrinter.input("ttlMillis / evictAfterMillis", "500 / 1500");
        ScenarioPrinter.candidates(List.of(states(registry, svc)));

        // t=600：没有新心跳，两个实例都超过 ttl -> UNHEALTHY（暂时不健康，记录仍在）
        clock.set(600);
        assertTrue(registry.healthyInstances(svc).isEmpty(), "超过 ttl 后不应有健康候选");
        assertEquals(InstanceStatus.UNHEALTHY, registry.allInstances(svc).get(0).status());
        ScenarioPrinter.info("t=600 超过 ttl，无健康候选，但记录保留");
        ScenarioPrinter.candidates(List.of(states(registry, svc)));

        // 迟到的旧心跳（时间戳早于最后心跳 t=0 也要被拒；这里 a 最后心跳是注册时刻 0）
        RegistryException stale = assertThrows(RegistryException.class,
                () -> registry.heartbeat("a", -10));
        assertEquals(RegistryException.Reason.STALE_HEARTBEAT, stale.reason());
        ScenarioPrinter.verdict("迟到旧心跳(heartbeatAt=-10) 被拒，原因=" + stale.reason());

        // 新鲜心跳救活 a；b 仍然 UNHEALTHY
        registry.heartbeat("a", 600);
        List<ServiceInstance> healthy = registry.healthyInstances(svc);
        assertEquals(1, healthy.size());
        assertEquals("a", healthy.get(0).instanceId());
        ScenarioPrinter.info("t=600 收到 a 的新鲜心跳，a 恢复 HEALTHY，b 仍 UNHEALTHY");
        ScenarioPrinter.candidates(List.of(states(registry, svc)));
        ScenarioPrinter.pass("心跳可把暂时不健康的实例救回 HEALTHY");

        // b 最后心跳停在 t=0：达到 ttl(500)+evictAfter(1500)=2000 即可被清理；
        // a 在 t=600 被救活，清理窗口随之顺延，此刻仍健康。
        clock.set(2_000);
        int removed = registry.sweepExpired();
        assertEquals(1, removed, "应恰好清理长期不健康的 b");
        ScenarioPrinter.info("t=2000 sweepExpired 清理数量=" + removed);

        RegistryException unknown = assertThrows(RegistryException.class,
                () -> registry.heartbeat("b", 2_000));
        assertEquals(RegistryException.Reason.UNKNOWN_INSTANCE, unknown.reason());
        ScenarioPrinter.verdict("被清理后 b 的迟到心跳被拒，原因=" + unknown.reason());

        // a 最后心跳 t=600：t=2000 时已过 ttl（暂时不健康），但 1400 < ttl+evictAfter=2000，
        // 所以不会被清理；一条新鲜心跳立刻把它救回健康。
        assertEquals(1, registry.allInstances(svc).size(), "a 尚未到清理时限，记录必须保留");
        registry.heartbeat("a", 2_000);
        assertEquals(1, registry.healthyInstances(svc).size());
        ScenarioPrinter.candidates(List.of(states(registry, svc)));
        ScenarioPrinter.pass("过期被清理的 b 再引用得到 UNKNOWN_INSTANCE；a 在清理前可被心跳救回 HEALTHY");
    }
}
