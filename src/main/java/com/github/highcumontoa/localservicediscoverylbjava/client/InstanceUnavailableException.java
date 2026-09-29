package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

/** 选中的实例在调用时已不可用（连接失败/实例被注销/端点拒绝服务）。 */
public class InstanceUnavailableException extends RuntimeException {

    private final transient ServiceInstance instance;

    public InstanceUnavailableException(ServiceInstance instance, String message) {
        super(message);
        this.instance = instance;
    }

    public InstanceUnavailableException(ServiceInstance instance, String message, Throwable cause) {
        super(message, cause);
        this.instance = instance;
    }

    public ServiceInstance getInstance() { return instance; }
}
