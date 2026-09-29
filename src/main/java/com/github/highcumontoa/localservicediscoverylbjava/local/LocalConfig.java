package com.github.highcumontoa.localservicediscoverylbjava.local;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceEndpoint;
import com.github.highcumontoa.localservicediscoverylbjava.client.ServiceClient;
import com.github.highcumontoa.localservicediscoverylbjava.lb.LeastConnectionsLoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.lb.LoadBalancer;
import com.github.highcumontoa.localservicediscoverylbjava.registry.RegistryConfig;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import com.github.highcumontoa.localservicediscoverylbjava.registry.Ticker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 本地内存组件的 Spring 装配：全部基于回环/内存，无外部依赖。
 */
@Configuration
public class LocalConfig {

    @Bean
    public Ticker ticker() {
        return System::currentTimeMillis;
    }

    @Bean
    public RegistryConfig registryConfig(Ticker ticker) {
        return RegistryConfig.ofMillis(500, 1500, ticker);
    }

    @Bean
    public ServiceRegistry serviceRegistry(RegistryConfig config) {
        return new ServiceRegistry(config);
    }

    @Bean
    public LocalTransport localTransport() {
        return new LocalTransport();
    }

    @Bean
    public LoadBalancer loadBalancer(LocalTransport transport) {
        return new LeastConnectionsLoadBalancer(transport::activeConnections);
    }

    @Bean
    public ServiceClient serviceClient(ServiceRegistry registry, LoadBalancer loadBalancer,
                                       LocalTransport transport) {
        return new ServiceClient(registry, loadBalancer, transport, 3);
    }

    @Bean
    @ConditionalOnProperty(name = "local.demo.enabled", havingValue = "true")
    public LocalDemoRunner localDemoRunner(
            org.springframework.context.ConfigurableApplicationContext context) {
        return new LocalDemoRunner(context);
    }
}
