package com.github.highcumontoa.localservicediscoverylbjava.registry;

public class HeartbeatException extends RuntimeException {
    private final HeartbeatFailureReason reason;

    public HeartbeatException(HeartbeatFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public HeartbeatFailureReason getReason() {
        return reason;
    }
}
