package com.github.highcumontoa.localservicediscoverylbjava.registry;

/**
 * 可替换的时间源，便于测试控制过期。
 */
@FunctionalInterface
public interface Ticker {
    long nowMillis();
}
