package com.github.highcumontoa.localservicediscoverylbjava.fake;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceUnavailableException;
import com.github.highcumontoa.localservicediscoverylbjava.client.Transport;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 instanceId 把请求路由到本机内存端点。
 * 端点不存在（实例已注销/从未提供）或端点主动不可用，都等价于回环连接失败，
 * 抛 {@link InstanceUnavailableException}，由调用方决定换实例重试。
 */
public class FakeTransport implements Transport {

    private final ConcurrentHashMap<String, FakeLocalInstance> endpoints = new ConcurrentHashMap<>();

    public FakeLocalInstance add(FakeLocalInstance endpoint) {
        endpoints.put(endpoint.getInstance().getInstanceId(), endpoint);
        return endpoint;
    }

    public FakeLocalInstance remove(String instanceId) {
        return endpoints.remove(instanceId);
    }

    public FakeLocalInstance get(String instanceId) {
        return endpoints.get(instanceId);
    }

    public Collection<FakeLocalInstance> all() {
        return Collections.unmodifiableCollection(endpoints.values());
    }

    @Override
    public String send(ServiceInstance instance, String payload) {
        FakeLocalInstance endpoint = endpoints.get(instance.getInstanceId());
        if (endpoint == null) {
            throw new InstanceUnavailableException(instance,
                    "no local loopback endpoint for instance " + instance.getInstanceId()
                            + " (instance appears deregistered)");
        }
        return endpoint.handle(payload);
    }
}
