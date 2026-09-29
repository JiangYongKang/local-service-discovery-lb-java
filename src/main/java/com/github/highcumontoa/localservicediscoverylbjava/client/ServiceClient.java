package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InstanceSnapshot;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 调用方。
 *
 * <p>重试约束：
 * <ul>
 *   <li>失败的实例立即加入排除集，本轮重试绝不会再选到它（LB 同时只看健康实例）。</li>
 *   <li>{@code retryable=false} 的请求：业务调用一旦失败，直接拒绝，绝不换实例重试。</li>
 *   <li>连接获取失败发生在请求被处理之前（实例在并发下刚过期/注销），对任何请求都安全，
 *       因此允许换实例，但同样受最大尝试次数约束。</li>
 *   <li>总尝试次数受 maxAttempts 限制，不存在无限重试。</li>
 *   <li>没有健康实例、全部实例失败、不可重试、达到上限 —— 四种拒绝结果互不相同，
 *       不返回 null，也不把内部异常抛给调用方。</li>
 * </ul>
 */
public class ServiceClient {

    private final ServiceRegistry registry;
    private final LoadBalancer loadBalancer;
    private final Transport transport;
    private final int maxAttempts;

    public ServiceClient(ServiceRegistry registry, LoadBalancer loadBalancer,
                         Transport transport, int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.transport = transport;
        this.maxAttempts = maxAttempts;
    }

    public CallResult invoke(ServiceRequest request) {
        Set<String> excluded = new LinkedHashSet<>();
        List<String> tried = new ArrayList<>();
        int attempts = 0;

        while (attempts < maxAttempts) {
            InstanceSnapshot chosen = loadBalancer.select(request.serviceName(), excluded);
            attempts++;
            if (chosen == null) {
                if (tried.isEmpty()) {
                    return new CallResult.Rejected(RejectionReason.NO_HEALTHY_INSTANCE,
                            "no healthy instance available for service " + request.serviceName(),
                            attempts, List.copyOf(tried));
                }
                return new CallResult.Rejected(RejectionReason.ALL_INSTANCES_FAILED,
                        "every healthy instance of service " + request.serviceName()
                                + " was tried and failed: " + tried,
                        attempts, List.copyOf(tried));
            }

            String instanceId = chosen.instanceId();
            tried.add(instanceId);

            // 拿连接是最后的健康校验：快照与拿连接之间实例可能刚被注销/过期
            if (registry.acquireConnection(request.serviceName(), instanceId) < 0) {
                excluded.add(instanceId);
                continue; // 请求尚未发出，换实例安全；次数仍计入上限
            }
            try {
                String response = transport.send(chosen.instance(), request.payload());
                return new CallResult.Success(chosen.instance(), response, attempts,
                        List.copyOf(tried));
            } catch (InstanceUnavailableException e) {
                registry.markUnhealthy(request.serviceName(), instanceId);
                excluded.add(instanceId);
                if (!request.retryable()) {
                    return new CallResult.Rejected(RejectionReason.NON_RETRYABLE_REQUEST,
                            "request is not retryable; instance " + instanceId
                                    + " failed: " + e.getMessage(),
                            attempts, List.copyOf(tried));
                }
                // 可重试：回到循环，excluded 保证不会再选到刚失败的实例
            } finally {
                registry.releaseConnection(request.serviceName(), instanceId);
            }
        }

        // 达到上限：还存在没试过的健康实例 -> 次数上限；试遍了全部实例 -> 全部失败
        boolean untriedHealthyExists = registry.listHealthy(request.serviceName()).stream()
                .anyMatch(s -> !excluded.contains(s.instanceId()));
        RejectionReason reason = untriedHealthyExists
                ? RejectionReason.MAX_ATTEMPTS_REACHED
                : RejectionReason.ALL_INSTANCES_FAILED;
        return new CallResult.Rejected(reason,
                "max attempts (" + maxAttempts + ") reached for service "
                        + request.serviceName() + ", tried: " + tried,
                attempts, List.copyOf(tried));
    }
}
