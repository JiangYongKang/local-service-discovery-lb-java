package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.lb.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务调用方：按负载均衡策略在健康实例中选择并发起调用。
 *
 * <p>重试约束：
 * <ul>
 *   <li>每次尝试前都取一次<strong>新鲜健康快照</strong>（可能在重试间隙已过期/被注销）；</li>
 *   <li>已经失败过的实例进入黑名单，绝不回头；也不会选择健康快照之外的实例；</li>
 *   <li>{@code CONNECTION_FAILURE}（实例已不可用）总是值得换另一个实例再试；</li>
 *   <li>{@code BUSINESS_FAILURE}（业务失败）只在请求声明 retryable=true 时才换实例重试；</li>
 *   <li>非可重试请求首次业务失败即拒绝（NON_RETRYABLE_REQUEST），不反复重试；</li>
 *   <li>达到 maxAttempts 或健康实例全部试完仍失败，给出可区分的拒绝结果而非 null/内部异常。</li>
 * </ul>
 */
public class ServiceClient {

    private final ServiceRegistry registry;
    private final LoadBalancer loadBalancer;
    private final InstanceEndpoint endpoint;
    private final int maxAttempts;

    public ServiceClient(ServiceRegistry registry, LoadBalancer loadBalancer,
                         InstanceEndpoint endpoint, int maxAttempts) {
        if (registry == null || loadBalancer == null || endpoint == null) {
            throw new IllegalArgumentException("registry/loadBalancer/endpoint 不能为 null");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts 至少为 1");
        }
        this.registry = registry;
        this.loadBalancer = loadBalancer;
        this.endpoint = endpoint;
        this.maxAttempts = maxAttempts;
    }

    public InvocationOutcome invoke(String serviceName, Object request, boolean retryable) {
        Set<String> attempted = new LinkedHashSet<>();
        List<ServiceInstance> snapshot = registry.healthyInstances(serviceName);

        if (snapshot.isEmpty()) {
            // 区分：从来没注册过 / 有记录但全部不健康（过期或注销）
            if (registry.allInstances(serviceName).isEmpty()) {
                return InvocationOutcome.rejected(InvocationOutcome.RejectReason.NO_INSTANCE_REGISTERED,
                        "服务 " + serviceName + " 没有任何已注册实例", List.copyOf(attempted));
            }
            return InvocationOutcome.rejected(InvocationOutcome.RejectReason.NO_HEALTHY_INSTANCE,
                    "服务 " + serviceName + " 的实例全部不健康（心跳过期或已注销）", List.copyOf(attempted));
        }

        int attempt = 0;
        while (attempt < maxAttempts) {
            snapshot = registry.healthyInstances(serviceName);
            List<ServiceInstance> candidates = new ArrayList<>();
            for (ServiceInstance instance : snapshot) {
                if (!attempted.contains(instance.instanceId())) {
                    candidates.add(instance);
                }
            }
            if (candidates.isEmpty()) {
                return InvocationOutcome.rejected(InvocationOutcome.RejectReason.ALL_INSTANCES_FAILED,
                        "所有健康实例均已尝试且失败（不会回到失败实例）", List.copyOf(attempted));
            }

            ServiceInstance selected = loadBalancer.choose(candidates);
            attempted.add(selected.instanceId());
            attempt++;

            try {
                Object response = endpoint.call(selected, request);
                return InvocationOutcome.ok(response, selected, List.copyOf(attempted), attempt);
            } catch (InstanceCallException e) {
                boolean worthRetry = e.kind() == InstanceCallException.Kind.CONNECTION_FAILURE || retryable;
                if (!worthRetry) {
                    return InvocationOutcome.rejected(InvocationOutcome.RejectReason.NON_RETRYABLE_REQUEST,
                            "请求不可重试且实例 " + selected.instanceId() + " 业务失败: " + e.getMessage(),
                            List.copyOf(attempted));
                }
                // 连接失败或可重试业务失败：换一个没试过的健康实例再来（循环开头取新鲜快照）
                if (attempt >= maxAttempts) {
                    return InvocationOutcome.rejected(InvocationOutcome.RejectReason.RETRY_LIMIT_REACHED,
                            "已达到最大尝试次数 " + maxAttempts + "，最后失败实例=" + selected.instanceId(),
                            List.copyOf(attempted));
                }
            }
        }
        return InvocationOutcome.rejected(InvocationOutcome.RejectReason.RETRY_LIMIT_REACHED,
                "已达到最大尝试次数 " + maxAttempts, List.copyOf(attempted));
    }
}
