package com.github.highcumontoa.localservicediscoverylbjava.local;

import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceCallException;
import com.github.highcumontoa.localservicediscoverylbjava.client.InstanceEndpoint;
import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存回环传输：根据 instanceId 把调用路由到同进程的伪造节点。
 *
 * <p>模拟真实连接语义：
 * <ul>
 *   <li>调用开始时该实例 activeConnections +1，结束（成功/失败）时 -1，统计始终配平；</li>
 *   <li>节点未注册 / 已移除（模拟实例彻底消失）抛 CONNECTION_FAILURE；</li>
 *   <li>节点存在但行为=CONN_DOWN 抛 CONNECTION_FAILURE（实例不可用，值得换实例）；</li>
 *   <li>行为=BUSINESS_ERROR 抛 BUSINESS_FAILURE（业务失败，按请求可重试标志决定是否换实例）。</li>
 * </ul>
 *
 * <p>所有通信只在内存中完成，无 socket、无远程地址、无凭据。
 */
public class LocalTransport implements InstanceEndpoint {

    private final Map<String, LocalServiceNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, Integer> activeConnections = new ConcurrentHashMap<>();

    public void registerNode(LocalServiceNode node) {
        if (node == null) {
            throw new IllegalArgumentException("node 不能为 null");
        }
        if (nodes.putIfAbsent(node.instanceId(), node) != null) {
            throw new IllegalStateException("节点已存在: " + node.instanceId());
        }
        activeConnections.put(node.instanceId(), 0);
    }

    public void removeNode(String instanceId) {
        nodes.remove(instanceId);
        activeConnections.remove(instanceId);
    }

    public LocalServiceNode node(String instanceId) {
        return nodes.get(instanceId);
    }

    public int activeConnections(String instanceId) {
        return activeConnections.getOrDefault(instanceId, 0);
    }

    @Override
    public Object call(ServiceInstance instance, Object request) {
        LocalServiceNode node = nodes.get(instance.instanceId());
        if (node == null) {
            throw new InstanceCallException(InstanceCallException.Kind.CONNECTION_FAILURE,
                    "无本机节点 " + instance.instanceId() + "（可能已彻底下线）");
        }
        // 模拟连接建立：活跃连接数 +1；merge 保证并发统计不错乱
        activeConnections.merge(instance.instanceId(), 1, Integer::sum);
        try {
            return node.handle(request);
        } catch (RuntimeException e) {
            if (node.behavior() == LocalServiceNode.Behavior.CONN_DOWN
                    || node.behavior() == null) {
                throw new InstanceCallException(InstanceCallException.Kind.CONNECTION_FAILURE,
                        "连接失败: " + instance.instanceId(), e);
            }
            if (node.behavior() == LocalServiceNode.Behavior.BUSINESS_ERROR) {
                throw new InstanceCallException(InstanceCallException.Kind.BUSINESS_FAILURE,
                        "业务失败: " + instance.instanceId(), e);
            }
            throw new InstanceCallException(InstanceCallException.Kind.BUSINESS_FAILURE,
                    "处理异常: " + instance.instanceId(), e);
        } finally {
            activeConnections.merge(instance.instanceId(), -1, Integer::sum);
        }
    }
}
