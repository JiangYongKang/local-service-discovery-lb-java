package com.github.highcumontoa.localservicediscoverylbjava.web;

import com.github.highcumontoa.localservicediscoverylbjava.client.CallResult;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceRequest;
import com.github.highcumontoa.localservicediscoverylbjava.config.DiscoveryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.fake.FakeLocalInstance;
import com.github.highcumontoa.localservicediscoverylbjava.fake.FakeTransport;
import com.github.highcumontoa.localservicediscoverylbjava.registry.HeartbeatException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistrationException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 通过本机回环 HTTP 暴露注册/心跳/注销/查询/伪实例管理/调用，
 * 仅绑定 127.0.0.1 语义，无外部注册中心、无真实凭据。
 */
@RestController
@RequestMapping("/local-discovery/services/{serviceName}")
public class DiscoveryController {

    private static final String LOOPBACK = "127.0.0.1";

    private final ServiceRegistry registry;
    private final FakeTransport transport;
    private final DiscoveryConfig config;
    private final com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LoadBalancer
            randomRoundRobinLoadBalancer;
    private final ServiceClient randomRoundRobinClient;
    private final ServiceClient leastConnectionsClient;

    public DiscoveryController(ServiceRegistry registry, FakeTransport transport,
                               DiscoveryConfig config,
                               @Qualifier("randomRoundRobinLoadBalancer")
                               com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LoadBalancer
                                       randomRoundRobinLoadBalancer,
                               @Qualifier("randomRoundRobinClient") ServiceClient randomRoundRobinClient,
                               @Qualifier("leastConnectionsClient") ServiceClient leastConnectionsClient) {
        this.registry = registry;
        this.transport = transport;
        this.config = config;
        this.randomRoundRobinLoadBalancer = randomRoundRobinLoadBalancer;
        this.randomRoundRobinClient = randomRoundRobinClient;
        this.leastConnectionsClient = leastConnectionsClient;
    }

    /** 把轮询游标重置为“从排序第一个健康实例开始”，仅用于本地集成验证获得确定性顺序。 */
    @PostMapping("/round-robin/reset")
    public Map<String, String> resetRoundRobin() {
        randomRoundRobinLoadBalancer.resetToFirst();
        return Map.of("message", "random round-robin cursor reset to first candidate");
    }

    public record RegisterRequest(String instanceId, int port, Map<String, String> metadata) {}

    @PostMapping("/instances")
    public ResponseEntity<?> register(@PathVariable String serviceName,
                                      @RequestBody RegisterRequest request) {
        ServiceInstance instance = new ServiceInstance(
                request.instanceId(), serviceName, LOOPBACK, request.port(),
                request.metadata() == null ? Map.of() : request.metadata());
        try {
            registry.register(instance);
            transport.add(new FakeLocalInstance(instance));
            config.trackService(serviceName);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(registry.snapshot(serviceName, request.instanceId()));
        } catch (RegistrationException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("failureReason", e.getReason().name(), "message", e.getMessage()));
        }
    }

    @DeleteMapping("/instances/{instanceId}")
    public ResponseEntity<?> deregister(@PathVariable String serviceName,
                                        @PathVariable String instanceId) {
        registry.deregister(serviceName, instanceId);
        transport.remove(instanceId);
        return ResponseEntity.ok(registry.snapshot(serviceName, instanceId));
    }

    @PostMapping("/instances/{instanceId}/heartbeat")
    public ResponseEntity<?> heartbeat(@PathVariable String serviceName,
                                       @PathVariable String instanceId,
                                       @RequestParam long seq) {
        try {
            registry.heartbeat(serviceName, instanceId, seq);
            return ResponseEntity.ok(registry.snapshot(serviceName, instanceId));
        } catch (HeartbeatException e) {
            HttpStatus status = switch (e.getReason()) {
                case UNKNOWN_INSTANCE -> HttpStatus.NOT_FOUND;
                case DEREGISTERED_INSTANCE -> HttpStatus.GONE;
                case STALE_HEARTBEAT -> HttpStatus.CONFLICT;
            };
            return ResponseEntity.status(status)
                    .body(Map.of("failureReason", e.getReason().name(), "message", e.getMessage()));
        }
    }

    @GetMapping("/instances")
    public List<InstanceSnapshot> instances(@PathVariable String serviceName) {
        return registry.listSnapshots(serviceName);
    }

    /** 切换本机伪实例端点可用性，用于制造“选中后失败、换实例重试”。 */
    @PostMapping("/instances/{instanceId}/availability")
    public ResponseEntity<?> availability(@PathVariable String serviceName,
                                          @PathVariable String instanceId,
                                          @RequestParam boolean available) {
        FakeLocalInstance endpoint = transport.get(instanceId);
        if (endpoint == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "no local endpoint for instance " + instanceId));
        }
        endpoint.setAvailable(available);
        return ResponseEntity.ok(Map.of(
                "instanceId", instanceId, "endpointAvailable", endpoint.isAvailable(),
                "registry", registry.snapshot(serviceName, instanceId)));
    }

    @PostMapping("/invoke")
    public Map<String, Object> invoke(@PathVariable String serviceName,
                                      @RequestParam(defaultValue = "ROUND_ROBIN") String strategy,
                                      @RequestParam(defaultValue = "true") boolean retryable,
                                      @RequestBody String payload) {
        ServiceClient client = "LEAST_CONNECTIONS".equalsIgnoreCase(strategy)
                ? leastConnectionsClient : randomRoundRobinClient;
        ServiceRequest request = retryable
                ? ServiceRequest.idempotent(serviceName, payload)
                : ServiceRequest.nonIdempotent(serviceName, payload);
        CallResult result = client.invoke(request);
        if (result instanceof CallResult.Success ok) {
            return Map.of(
                    "outcome", "SUCCESS",
                    "instanceId", ok.instance().getInstanceId(),
                    "response", ok.response(),
                    "attempts", ok.attempts(),
                    "triedInstanceIds", ok.triedInstanceIds());
        }
        CallResult.Rejected rj = (CallResult.Rejected) result;
        return Map.of(
                "outcome", "REJECTED",
                "reason", rj.reason().name(),
                "message", rj.message(),
                "attempts", rj.attempts(),
                "triedInstanceIds", rj.triedInstanceIds());
    }
}
