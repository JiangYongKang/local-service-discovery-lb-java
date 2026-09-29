package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.github.highcumontoa.localservicediscoverylbjava.registry.HeartbeatException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.HeartbeatFailureReason;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InMemoryServiceRegistry;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceStatus;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistrationException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistrationFailureReason;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.tests.support.MutableClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 心跳过期与恢复、最终清理、重复注册、旧心跳、注销后引用 的判定测试。
 * 每个场景都打印输入参数、候选实例状态与最终判定依据。
 */
class HeartbeatLifecycleTest {

    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration EVICT_AFTER = Duration.ofSeconds(30);
    private static final String SVC = "order-service";

    private ServiceInstance instance(String id, int port) {
        return new ServiceInstance(id, SVC, "127.0.0.1", port, Map.of());
    }

    private void printState(String title, MutableClock clock, InMemoryServiceRegistry registry) {
        System.out.println("---- " + title + " @ t+" + clock.millis() + "ms ----");
        for (InstanceSnapshot s : registry.listSnapshots(SVC)) {
            System.out.printf("  instance=%-4s status=%-13s lastHbSeq=%d activeConn=%d%n",
                    s.instanceId(), s.status(), s.lastHeartbeatSeq(), s.activeConnections());
        }
    }

    @Test
    @DisplayName("心跳过期 -> 暂时不健康 -> 收到新心跳恢复健康 -> 再过期 -> 最终清理")
    void expireRecoverAndEvict() {
        MutableClock clock = new MutableClock();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock, TTL, EVICT_AFTER);
        registry.register(instance("i-1", 9001));

        printState("注册后", clock, registry);
        assertEquals(InstanceStatus.HEALTHY, registry.snapshot(SVC, "i-1").status());

        clock.advance(Duration.ofSeconds(11)); // 超过 ttl
        registry.evictExpired(SVC);
        printState("超过心跳 TTL 10s 后", clock, registry);
        assertEquals(InstanceStatus.UNHEALTHY, registry.snapshot(SVC, "i-1").status(),
                "心跳丢失只是暂时不健康，不能等同于注销");
        assertTrue(registry.listHealthy(SVC).isEmpty(), "非健康实例不得进入候选");

        // 心跳恢复
        registry.heartbeat(SVC, "i-1", 1);
        printState("收到 seq=1 心跳后", clock, registry);
        assertEquals(InstanceStatus.HEALTHY, registry.snapshot(SVC, "i-1").status(),
                "暂时不健康的实例可通过新心跳恢复健康");

        // 再次过期，再过 evictAfter 后清理
        clock.advance(Duration.ofSeconds(11));
        registry.evictExpired(SVC);
        printState("再次过期转不健康", clock, registry);
        assertEquals(InstanceStatus.UNHEALTHY, registry.snapshot(SVC, "i-1").status());

        clock.advance(Duration.ofSeconds(30));
        List<String> evicted = registry.evictExpired(SVC);
        printState("不健康持续 30s 后", clock, registry);
        assertEquals(List.of("i-1"), evicted, "最终过期应被清理");
        assertNull(registry.snapshot(SVC, "i-1"), "清理后查不到实例");

        // 清理后同 ID 可以重新注册
        registry.register(instance("i-1", 9001));
        printState("同 ID 重新注册后", clock, registry);
        assertEquals(InstanceStatus.HEALTHY, registry.snapshot(SVC, "i-1").status());
        System.out.println("判定依据：过期只是暂时不健康，新心跳可恢复；清理后记录消失才允许重新注册");
    }

    @Test
    @DisplayName("重复注册给出 ALREADY_REGISTERED；旧心跳被 STALE_HEARTBEAT 拒绝")
    void duplicateRegistrationAndStaleHeartbeat() {
        MutableClock clock = new MutableClock();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock, TTL, EVICT_AFTER);
        registry.register(instance("i-1", 9001));

        RegistrationException dup = assertThrows(RegistrationException.class,
                () -> registry.register(instance("i-1", 9999)),
                "重复注册不能被静默覆盖");
        System.out.println("重复注册失败原因: " + dup.getReason() + " - " + dup.getMessage());
        assertEquals(RegistrationFailureReason.ALREADY_REGISTERED, dup.getReason());
        // 注册信息不能被覆盖
        assertEquals(9001, registry.snapshot(SVC, "i-1").instance().getPort());

        registry.heartbeat(SVC, "i-1", 10);
        clock.advance(Duration.ofSeconds(2));

        HeartbeatException stale = assertThrows(HeartbeatException.class,
                () -> registry.heartbeat(SVC, "i-1", 5),
                "迟到的旧心跳必须被拒绝，不能刷新心跳时间");
        System.out.println("旧心跳失败原因: " + stale.getReason() + " - " + stale.getMessage());
        assertEquals(HeartbeatFailureReason.STALE_HEARTBEAT, stale.getReason());

        // 旧心跳不得把 seq 改回去
        assertEquals(10, registry.snapshot(SVC, "i-1").lastHeartbeatSeq());
        // 过期判断仍以 seq=10 那次为准
        clock.advance(Duration.ofSeconds(9)); // 距 seq=10 共 11s
        registry.evictExpired(SVC);
        assertEquals(InstanceStatus.UNHEALTHY, registry.snapshot(SVC, "i-1").status());
        System.out.println("判定依据：拒绝 seq=5 后，TTL 仍按已接受的 seq=10 计算，正确转为不健康");
    }

    @Test
    @DisplayName("主动注销后：心跳不能复活、重复注册被拒、仍可查到墓碑并被引用方识别")
    void deregisterIsTerminalButObservable() {
        MutableClock clock = new MutableClock();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock, TTL, EVICT_AFTER);
        registry.register(instance("i-1", 9001));
        registry.deregister(SVC, "i-1");
        printState("主动注销后", clock, registry);

        InstanceSnapshot snapshot = registry.snapshot(SVC, "i-1");
        assertNotNull(snapshot, "主动注销保留墓碑，引用方能查到并识别，而不是查无此人");
        assertEquals(InstanceStatus.DEREGISTERED, snapshot.status());
        assertFalse(registry.listHealthy(SVC).stream()
                .anyMatch(s -> s.instanceId().equals("i-1")), "注销实例不得进入候选");

        HeartbeatException hb = assertThrows(HeartbeatException.class,
                () -> registry.heartbeat(SVC, "i-1", 99));
        System.out.println("注销后心跳失败原因: " + hb.getReason() + " - " + hb.getMessage());
        assertEquals(HeartbeatFailureReason.DEREGISTERED_INSTANCE, hb.getReason());
        assertEquals(InstanceStatus.DEREGISTERED, registry.snapshot(SVC, "i-1").status(),
                "心跳不能让已注销实例复活");

        RegistrationException re = assertThrows(RegistrationException.class,
                () -> registry.register(instance("i-1", 9002)));
        System.out.println("墓碑 ID 重新注册失败原因: " + re.getReason() + " - " + re.getMessage());
        assertEquals(RegistrationFailureReason.DEREGISTERED_INSTANCE, re.getReason());

        // 墓碑永不被过期扫描清理
        clock.advance(Duration.ofMinutes(10));
        registry.evictExpired(SVC);
        assertNotNull(registry.snapshot(SVC, "i-1"));
        assertEquals(InstanceStatus.DEREGISTERED, registry.snapshot(SVC, "i-1").status());
        System.out.println("判定依据：DEREGISTERED 是终态墓碑，与过期清理有本质区别");
    }

    @Test
    @DisplayName("从未注册或已被清理的实例上报心跳 -> UNKNOWN_INSTANCE")
    void unknownInstanceHeartbeat() {
        MutableClock clock = new MutableClock();
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry(clock, TTL, EVICT_AFTER);
        HeartbeatException e = assertThrows(HeartbeatException.class,
                () -> registry.heartbeat(SVC, "ghost", 1));
        System.out.println("未知实例心跳失败原因: " + e.getReason() + " - " + e.getMessage());
        assertEquals(HeartbeatFailureReason.UNKNOWN_INSTANCE, e.getReason());
    }
}
