package com.github.highcumontoa.localservicediscoverylbjava.config;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.springframework.scheduling.annotation.Scheduled;

/** 周期性把心跳过期的实例转不健康、最终清理；只在本机进程内运行。 */
public class EvictionScheduler {

    private final ServiceRegistry registry;
    private final DiscoveryConfig config;

    public EvictionScheduler(ServiceRegistry registry, DiscoveryConfig config) {
        this.registry = registry;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${discovery.evict-interval:5000}")
    public void sweep() {
        for (String serviceName : config.trackedServices()) {
            registry.evictExpired(serviceName);
        }
    }
}
