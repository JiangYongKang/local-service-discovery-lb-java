package com.github.highcumontoa.localservicediscoverylbjava.fake;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceUnavailableException;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 本机内存里的“被调用实例”，逻辑上绑定在 127.0.0.1 回环地址，不做真实网络 IO。
 * 可被切换为不可用，用来模拟实例在被选中后失败、换实例重试等场景。
 */
public class FakeLocalInstance {

    private final ServiceInstance instance;
    private volatile boolean available = true;
    private volatile Function<String, String> handler = payload -> "echo:" + payload;
    private final AtomicLong handledRequests = new AtomicLong();

    public FakeLocalInstance(ServiceInstance instance) {
        this.instance = instance;
    }

    public ServiceInstance getInstance() { return instance; }
    public boolean isAvailable() { return available; }
    public long handledRequests() { return handledRequests.get(); }

    public void setAvailable(boolean available) { this.available = available; }

    public void setHandler(Function<String, String> handler) {
        this.handler = handler;
    }

    /** 处理一次本机调用；不可用时抛出与真实连接失败等价的异常。 */
    public String handle(String payload) {
        if (!available) {
            throw new InstanceUnavailableException(instance,
                    "fake instance " + instance.getInstanceId() + " is unavailable (simulated loopback failure)");
        }
        handledRequests.incrementAndGet();
        return handler.apply(payload);
    }
}
