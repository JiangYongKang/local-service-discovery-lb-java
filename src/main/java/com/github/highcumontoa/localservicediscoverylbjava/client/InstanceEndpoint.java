package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

/**
 * 实例调用端点抽象：生产可换成 HTTP 回环，本地验证用内存伪造实现。
 */
@FunctionalInterface
public interface InstanceEndpoint {

    /**
     * 调用实例；失败时抛出 {@link InstanceCallException}，供调用方判定是否换实例。
     */
    Object call(ServiceInstance instance, Object request);
}
