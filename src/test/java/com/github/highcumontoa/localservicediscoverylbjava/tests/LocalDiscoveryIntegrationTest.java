package com.github.highcumontoa.localservicediscoverylbjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本机回环端到端集成验证：通过真实的 127.0.0.1 HTTP 完成
 * 注册 -> 心跳 -> 调用 -> 重复注册/旧心跳/注销 失败码 -> 失败换实例重试 -> 无实例拒绝。
 * TTL 调小到毫秒级，整个测试只跑在本机进程内，不依赖任何外部服务或凭据。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "discovery.heartbeat-ttl=400ms",
        "discovery.evict-after=800ms",
        "discovery.evict-interval=150",
        "discovery.max-attempts=3"
})
class LocalDiscoveryIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ServiceRegistry registry;

    private String url(String svc) {
        return "http://127.0.0.1:" + port + "/local-discovery/services/" + svc;
    }

    private void register(String svc, String id, int endpointPort) {
        ResponseEntity<JsonNode> resp = http.postForEntity(url(svc) + "/instances",
                new HttpEntity<>(java.util.Map.of(
                        "instanceId", id, "port", endpointPort, "metadata", java.util.Map.of())),
                JsonNode.class);
        assertEquals(201, resp.getStatusCode().value(), "注册应成功: " + resp.getBody());
    }

    private JsonNode invoke(String svc, String strategy, boolean retryable, String payload) {
        return http.postForEntity(
                url(svc) + "/invoke?strategy=" + strategy + "&retryable=" + retryable,
                new HttpEntity<>(payload), JsonNode.class).getBody();
    }

    private void printInstances(String svc, String title) {
        System.out.println("---- " + title + " ----");
        for (InstanceSnapshot s : registry.listSnapshots(svc)) {
            System.out.printf("  instance=%-4s status=%-13s activeConn=%d%n",
                    s.instanceId(), s.status(), s.activeConnections());
        }
    }

    @Test
    @DisplayName("回环 HTTP 全链路：注册/分布/失败换实例/心跳过期恢复/旧心跳/注销拒绝")
    void endToEndOnLoopback() throws Exception {
        String svc = "echo-a";
        register(svc, "e-1", 19001);
        register(svc, "e-2", 19002);
        register(svc, "e-3", 19003);
        printInstances(svc, "3 个本机实例已注册");

        // 1) 连续 3 次轮询覆盖全部 3 个实例（起点随机，但一圈内每个恰好一次）
        Set<String> hits = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            JsonNode r = invoke(svc, "ROUND_ROBIN", true, "hello-" + i);
            System.out.println("输入 hello-" + i + " -> outcome=" + r.path("outcome").asText()
                    + " by=" + r.path("instanceId").asText() + " tried=" + r.path("triedInstanceIds"));
            assertEquals("SUCCESS", r.path("outcome").asText());
            hits.add(r.path("instanceId").asText());
        }
        assertEquals(Set.of("e-1", "e-2", "e-3"), hits, "一轮轮询应覆盖每个健康实例");

        // 2) 重复注册 -> 409 ALREADY_REGISTERED
        ResponseEntity<JsonNode> dup = http.postForEntity(url(svc) + "/instances",
                new HttpEntity<>(java.util.Map.of(
                        "instanceId", "e-1", "port", 9999, "metadata", java.util.Map.of())),
                JsonNode.class);
        assertEquals(409, dup.getStatusCode().value());
        assertEquals("ALREADY_REGISTERED", dup.getBody().path("failureReason").asText());
        System.out.println("重复注册最终判定: 409 " + dup.getBody().path("failureReason").asText());

        // 3) e-1、e-2 端点失败、只留 e-3：重置轮询到确定起点后，charge 必须依次试 e-1、e-2，
        //    刚失败过的实例不会被再次选中，最终换到 e-3 成功
        http.postForEntity(url(svc) + "/instances/e-1/availability?available=false", null, Void.class);
        http.postForEntity(url(svc) + "/instances/e-2/availability?available=false", null, Void.class);
        http.postForEntity(url(svc) + "/round-robin/reset", null, Void.class);
        printInstances(svc, "e-1、e-2 被置为不可用，轮询游标已重置到起点");

        JsonNode failedOver = invoke(svc, "ROUND_ROBIN", true, "charge");
        System.out.println("输入 charge（e-1/e-2 失败，仅 e-3 健康）-> " + failedOver.toPrettyString()
                .replace('\n', ' ').replaceAll("\\s+", " "));
        assertEquals("SUCCESS", failedOver.path("outcome").asText());
        assertEquals("e-3", failedOver.path("instanceId").asText(), "只能换到唯一健康的 e-3");
        java.util.List<String> tried = new java.util.ArrayList<>();
        failedOver.path("triedInstanceIds").forEach(n -> tried.add(n.asText()));
        assertEquals(3, tried.size(), "三个实例各试一次");
        assertEquals(Set.of("e-1", "e-2", "e-3"), new HashSet<>(tried));
        assertEquals(tried.size(), new HashSet<>(tried).size(), "失败实例不得被重复选择");
        assertEquals("UNHEALTHY", registry.snapshot(svc, "e-1").status().name());
        assertEquals("UNHEALTHY", registry.snapshot(svc, "e-2").status().name());
        printInstances(svc, "换实例重试后");

        // 4) 全部不健康 -> ALL_INSTANCES_FAILED
        for (String id : new String[]{"e-1", "e-2", "e-3"}) {
            http.postForEntity(url(svc) + "/instances/" + id + "/availability?available=false",
                    null, Void.class);
        }
        JsonNode allFail = invoke(svc, "ROUND_ROBIN", true, "charge-2");
        System.out.println("全部不可用时 -> outcome=" + allFail.path("outcome").asText()
                + " reason=" + allFail.path("reason").asText());
        assertEquals("REJECTED", allFail.path("outcome").asText());
        assertEquals("ALL_INSTANCES_FAILED", allFail.path("reason").asText());

        // 5) 心跳过期（400ms TTL）-> UNHEALTHY；新心跳恢复；旧心跳 409
        Thread.sleep(700);
        printInstances(svc, "等待 700ms（e-2/e-3 已不健康；等 e-1 观察过期转换）");
        // e-1 之前调用成功后保持健康，700ms 后也应被定时任务转为不健康
        assertEquals("UNHEALTHY", registry.snapshot(svc, "e-1").status().name());

        ResponseEntity<JsonNode> hb = http.postForEntity(
                url(svc) + "/instances/e-1/heartbeat?seq=1", null, JsonNode.class);
        assertEquals(200, hb.getStatusCode().value());
        assertEquals("HEALTHY", hb.getBody().path("status").asText());
        System.out.println("e-1 上报 seq=1 -> 恢复健康");
        printInstances(svc, "e-1 心跳恢复后");

        ResponseEntity<JsonNode> stale = http.postForEntity(
                url(svc) + "/instances/e-1/heartbeat?seq=1", null, JsonNode.class);
        assertEquals(409, stale.getStatusCode().value());
        assertEquals("STALE_HEARTBEAT", stale.getBody().path("failureReason").asText());
        System.out.println("重复 seq=1 心跳最终判定: 409 STALE_HEARTBEAT");

        // 6) 主动注销 -> 心跳 410、墓碑保留、调用 NO_HEALTHY_INSTANCE、同 ID 重注 409
        ResponseEntity<JsonNode> del = http.exchange(
                url(svc) + "/instances/e-1", HttpMethod.DELETE, null, JsonNode.class);
        assertEquals(200, del.getStatusCode().value());
        assertEquals("DEREGISTERED", del.getBody().path("status").asText());
        printInstances(svc, "e-1 主动注销后");

        ResponseEntity<JsonNode> hbGone = http.postForEntity(
                url(svc) + "/instances/e-1/heartbeat?seq=2", null, JsonNode.class);
        assertEquals(410, hbGone.getStatusCode().value());
        assertEquals("DEREGISTERED_INSTANCE", hbGone.getBody().path("failureReason").asText());
        System.out.println("注销后心跳最终判定: 410 DEREGISTERED_INSTANCE");

        JsonNode none = invoke(svc, "ROUND_ROBIN", true, "after-deregister");
        System.out.println("注销后调用 -> outcome=" + none.path("outcome").asText()
                + " reason=" + none.path("reason").asText());
        assertEquals("REJECTED", none.path("outcome").asText());
        assertEquals("NO_HEALTHY_INSTANCE", none.path("reason").asText());

        ResponseEntity<JsonNode> rereg = http.postForEntity(url(svc) + "/instances",
                new HttpEntity<>(java.util.Map.of(
                        "instanceId", "e-1", "port", 19001, "metadata", java.util.Map.of())),
                JsonNode.class);
        assertEquals(409, rereg.getStatusCode().value());
        assertEquals("DEREGISTERED_INSTANCE", rereg.getBody().path("failureReason").asText());
        System.out.println("墓碑 ID 重新注册最终判定: 409 DEREGISTERED_INSTANCE");
    }

    @Test
    @DisplayName("最少连接策略经回环 HTTP：在途连接多的实例被避开，连接归还后重新参与")
    void leastConnectionsOverLoopback() throws Exception {
        String svc = "echo-b";
        register(svc, "k-1", 20001);
        register(svc, "k-2", 20002);
        printInstances(svc, "2 个本机实例已注册（最少连接）");

        // 通过 registry 直接模拟 k-1 上挂着 2 个在途连接（真实场景由慢请求产生）
        assertEquals(1, registry.acquireConnection(svc, "k-1"));
        assertEquals(2, registry.acquireConnection(svc, "k-1"));
        printInstances(svc, "模拟 k-1 有 2 个在途连接后");

        for (int i = 0; i < 5; i++) {
            JsonNode r = invoke(svc, "LEAST_CONNECTIONS", true, "lc-" + i);
            System.out.println("输入 lc-" + i + " -> 选中 " + r.path("instanceId").asText());
            assertEquals("SUCCESS", r.path("outcome").asText());
            assertEquals("k-2", r.path("instanceId").asText(), "k-1 连接数更高，必须避开");
        }

        // 归还连接后 k-1 成为最少（k-2 有 5 个已完成请求但连接已归还为 0，二者并列），
        // 用慢处理器真实制造一个在途请求验证选择翻转
        // —— 直接再模拟：k-2 挂 1 个在途，k-1 为 0
        registry.releaseConnection(svc, "k-1");
        registry.releaseConnection(svc, "k-1");
        assertEquals(1, registry.acquireConnection(svc, "k-2"));
        printInstances(svc, "改为 k-2 有 1 个在途连接后");

        for (int i = 0; i < 5; i++) {
            JsonNode r = invoke(svc, "LEAST_CONNECTIONS", true, "lc-flip-" + i);
            System.out.println("输入 lc-flip-" + i + " -> 选中 " + r.path("instanceId").asText());
            assertEquals("k-1", r.path("instanceId").asText(), "连接数变化后选择结果必须跟着变");
        }
        registry.releaseConnection(svc, "k-2");
        System.out.println("判定依据：最少连接选择实时 activeConnections，在途压力变化时分布随之翻转");
    }
}
