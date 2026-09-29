package com.github.highcumontoa.localservicediscoverylbjava.registry;

public class RegistrationException extends RuntimeException {
    private final RegistrationFailureReason reason;

    public RegistrationException(RegistrationFailureReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RegistrationFailureReason getReason() {
        return reason;
    }
}
