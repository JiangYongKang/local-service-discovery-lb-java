package com.github.highcumontoa.localservicediscoverylbjava.config;

import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.client.Transport;
import com.github.highcumontoa.localservicediscoverylbjava.fake.FakeTransport;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LeastConnectionsLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.loadbalancer.RandomRoundRobinLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.InMemoryServiceRegistry;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 本地注册发现与负载均衡的装配：全部组件仅使用内存与回环，无外部依赖。 */
@Configuration
@EnableScheduling
public class DiscoveryConfig {

    private final Set<String> knownServices = ConcurrentHashMap.newKeySet();

    @Bean
    public Clock discoveryClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ServiceRegistry serviceRegistry(
            Clock clock,
            @Value("${discovery.heartbeat-ttl:10s}") Duration heartbeatTtl,
            @Value("${discovery.evict-after:30s}") Duration evictAfter) {
        return new InMemoryServiceRegistry(clock, heartbeatTtl, evictAfter);
    }

    @Bean
    public FakeTransport fakeTransport() {
        return new FakeTransport();
    }

    @Bean
    public Transport transport(FakeTransport fakeTransport) {
        return fakeTransport;
    }

    @Bean(name = "randomRoundRobinLoadBalancer")
    public LoadBalancer randomRoundRobinLoadBalancer(ServiceRegistry registry) {
        return new RandomRoundRobinLoadBalancer(registry);
    }

    @Bean(name = "leastConnectionsLoadBalancer")
    public LoadBalancer leastConnectionsLoadBalancer(ServiceRegistry registry) {
        return new LeastConnectionsLoadBalancer(registry);
    }

    @Bean(name = "randomRoundRobinClient")
    public ServiceClient randomRoundRobinClient(
            ServiceRegistry registry, Transport transport,
            @org.springframework.beans.factory.annotation.Qualifier("randomRoundRobinLoadBalancer")
            LoadBalancer loadBalancer,
            @Value("${discovery.max-attempts:3}") int maxAttempts) {
        return new ServiceClient(registry, loadBalancer, transport, maxAttempts);
    }

    @Bean(name = "leastConnectionsClient")
    public ServiceClient leastConnectionsClient(
            ServiceRegistry registry, Transport transport,
            @org.springframework.beans.factory.annotation.Qualifier("leastConnectionsLoadBalancer")
            LoadBalancer loadBalancer,
            @Value("${discovery.max-attempts:3}") int maxAttempts) {
        return new ServiceClient(registry, loadBalancer, transport, maxAttempts);
    }

    /** 周期性把过期实例转不健康、最终清理。仅扫描出现过的服务名。 */
    @Bean
    public org.springframework.scheduling.TaskScheduler discoveryTaskScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("discovery-evict-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public EvictionScheduler evictionScheduler(ServiceRegistry registry) {
        return new EvictionScheduler(registry, this);
    }

    /** 由 Controller 在注册时调用，登记需要扫描的服务。 */
    public void trackService(String serviceName) {
        knownServices.add(serviceName);
    }

    Set<String> trackedServices() {
        return knownServices;
    }
}
