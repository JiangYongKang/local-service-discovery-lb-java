package com.github.highcumontoa.localservicediscoverylbjava.registry;

import java.util.Map;

/** 实例的静态描述信息（注册时提交）。 */
public final class ServiceInstance {
    private final String instanceId;
    private final String serviceName;
    private final String host;
    private final int port;
    private final Map<String, String> metadata;

    public ServiceInstance(String instanceId, String serviceName, String host, int port,
                           Map<String, String> metadata) {
        this.instanceId = instanceId;
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
        this.metadata = metadata;
    }

    public String getInstanceId() { return instanceId; }
    public String getServiceName() { return serviceName; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Map<String, String> getMetadata() { return metadata; }
}
