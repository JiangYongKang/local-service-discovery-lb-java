package com.github.highcumontoa.localservicediscoverylbjava.loadbalancer;

/** 负载均衡策略：随机化轮询 / 最少连接。 */
public enum LoadBalanceStrategy {
    RANDOM_ROUND_ROBIN,
    LEAST_CONNECTIONS
}
