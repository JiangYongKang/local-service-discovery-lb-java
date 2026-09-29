package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

/** 实例侧的本地传输抽象（测试用内存实现，生产可换成回环 HTTP）。 */
public interface Transport {

    /** 向指定实例发起一次本机调用；实例不可达/处理失败时抛 {@link InstanceUnavailableException}。 */
    String send(ServiceInstance instance, String payload);
}
