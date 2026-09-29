package com.github.highcumontoa.localservicediscoverylbjava.local;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 本机进程内伪造的服务实例（被调用方）。
 * 不监听真实端口，调用直接在内存中执行；可通过 {@link Behavior} 模拟
 * 健康、连接已断（实例不可用）和业务失败三种情形。
 * 也可用 {@link #blockNext(int)} 让下一批请求阻塞，制造真实的“在途连接”以验证最少连接统计。
 */
public class LocalServiceNode {

    public enum Behavior {
        /** 正常处理并返回响应。 */
        HEALTHY,
        /** 模拟实例已不可用（连接失败），调用方应换实例。 */
        CONN_DOWN,
        /** 模拟业务处理失败（换实例无意义）。 */
        BUSINESS_ERROR
    }

    private final String instanceId;
    private volatile Behavior behavior;
    private volatile Function<Object, Object> handler;

    // >0 时，接下来的调用进入阻塞直到 releaseBlocked()，用于制造真实在途连接。
    // 所有访问在 this 监视器下完成，避免设置闸门与放行之间的竞态。
    private int hanging;
    private CountDownLatch releaseLatch = new CountDownLatch(0);

    public LocalServiceNode(String instanceId, Behavior behavior) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        this.instanceId = instanceId;
        this.behavior = behavior == null ? Behavior.HEALTHY : behavior;
        this.handler = req -> "resp[" + instanceId + "]:" + req;
    }

    public String instanceId() { return instanceId; }

    public Behavior behavior() { return behavior; }

    public void behavior(Behavior behavior) {
        this.behavior = behavior == null ? Behavior.HEALTHY : behavior;
    }

    public void handler(Function<Object, Object> handler) {
        this.handler = handler;
    }

    /**
     * 设置新的阻塞窗口：接下来 {@code count} 个请求将阻塞，直到 {@link #releaseBlocked()}
     * 或再次调用本方法。重新设置会先放行旧窗口的等待者并作废剩余名额，避免线程永久挂起。
     * 返回后新闸门已就位。
     */
    public synchronized void blockNext(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count 必须为正数");
        }
        releaseLatch.countDown(); // 先放行旧窗口
        this.releaseLatch = new CountDownLatch(1);
        this.hanging = count;
    }

    /** 释放所有已挂起请求并取消剩余阻塞名额。 */
    public synchronized void releaseBlocked() {
        hanging = 0;
        releaseLatch.countDown();
    }

    /**
     * 若当前在阻塞窗口内，原子地占用一个名额并返回该窗口的 latch；
     * 调用方一直等待这个 latch（即使之后 blockNext 换了新窗口，也不会重复占用名额）。
     */
    private CountDownLatch acquireHangPermit() {
        synchronized (this) {
            if (hanging > 0) {
                hanging--;
                return releaseLatch;
            }
            return null;
        }
    }

    /** 在内存中处理请求；行为不是 HEALTHY 时抛出相应异常。 */
    public Object handle(Object request) {
        CountDownLatch latch = acquireHangPermit();
        if (latch != null) {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("挂起请求被中断", e);
            }
        }
        switch (behavior) {
            case CONN_DOWN -> throw new RuntimeException("connection refused (模拟本机节点已下线)");
            case BUSINESS_ERROR -> throw new RuntimeException("business rejected (模拟业务处理失败)");
            case HEALTHY -> {
                return handler.apply(request);
            }
            default -> throw new IllegalStateException("未知行为: " + behavior);
        }
    }
}
