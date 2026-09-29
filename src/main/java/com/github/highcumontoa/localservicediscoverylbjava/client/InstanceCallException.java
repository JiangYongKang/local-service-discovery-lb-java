package com.github.highcumontoa.localservicediscoverylbjava.client;

/**
 * 调用某实例失败：区分连接级失败（实例已不可用，可换实例重试）
 * 与业务级失败（换实例无意义，不重试）。
 */
public class InstanceCallException extends RuntimeException {

    public enum Kind {
        /** 实例已下线/连不上：允许换另一个健康实例重试。 */
        CONNECTION_FAILURE,
        /** 业务处理返回失败：重试到其他实例通常无意义，按请求可重试标志决定。 */
        BUSINESS_FAILURE
    }

    private final Kind kind;

    public InstanceCallException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public InstanceCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
