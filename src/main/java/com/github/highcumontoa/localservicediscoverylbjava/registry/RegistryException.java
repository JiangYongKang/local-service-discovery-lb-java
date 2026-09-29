package com.github.highcumontoa.localservicediscoverylbjava.registry;

/**
 * 注册/心跳/注销操作的可区分失败原因。
 */
public class RegistryException extends RuntimeException {

    public enum Reason {
        ALREADY_REGISTERED,
        UNKNOWN_INSTANCE,
        DEREGISTERED_INSTANCE,
        STALE_HEARTBEAT,
        INVALID_ARGUMENT
    }

    private final Reason reason;
    private final String instanceId;

    public RegistryException(Reason reason, String instanceId, String message) {
        super(message);
        this.reason = reason;
        this.instanceId = instanceId;
    }

    public Reason reason() {
        return reason;
    }

    public String instanceId() {
        return instanceId;
    }
}
