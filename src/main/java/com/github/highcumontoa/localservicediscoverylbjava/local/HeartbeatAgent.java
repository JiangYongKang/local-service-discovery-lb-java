package com.github.highcumontoa.localservicediscoverylbjava.local;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceRegistry;
import com.github.highcumontoa.localservicediscoverylbjava.registry.Ticker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 心跳代理：周期性向内存注册中心发送心跳，并周期性触发过期扫描。
 * 仅用于本地演示；测试中直接手动调用 registry.heartbeat / sweepExpired 以保证确定性。
 */
public class HeartbeatAgent {

    private final ServiceRegistry registry;
    private final Ticker ticker;
    private final ScheduledExecutorService executor;
    private final long heartbeatIntervalMillis;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private volatile ScheduledFuture<?> sweepTask;

    public HeartbeatAgent(ServiceRegistry registry, Ticker ticker,
                          ScheduledExecutorService executor, long heartbeatIntervalMillis) {
        this.registry = registry;
        this.ticker = ticker;
        this.executor = executor;
        this.heartbeatIntervalMillis = heartbeatIntervalMillis;
    }

    /** 开始为某实例周期发送心跳（带注册中心当前时间戳）。 */
    public void start(String instanceId) {
        ScheduledFuture<?> task = executor.scheduleAtFixedRate(
                () -> registry.heartbeat(instanceId, ticker.nowMillis()),
                0, heartbeatIntervalMillis, TimeUnit.MILLISECONDS);
        if (tasks.put(instanceId, task) != null) {
            task.cancel(false);
            throw new IllegalStateException("实例 " + instanceId + " 的心跳任务已存在");
        }
    }

    public void stop(String instanceId) {
        ScheduledFuture<?> task = tasks.remove(instanceId);
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 启动周期过期扫描。 */
    public void startSweeper(long intervalMillis) {
        this.sweepTask = executor.scheduleAtFixedRate(
                registry::sweepExpired, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stopSweeper() {
        if (sweepTask != null) {
            sweepTask.cancel(false);
        }
    }
}
