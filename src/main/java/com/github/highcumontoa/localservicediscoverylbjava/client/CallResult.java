package com.github.highcumontoa.localservicediscoverylbjava.client;

import com.github.highcumontoa.localservicediscoverylbjava.registry.ServiceInstance;

import java.util.List;

/** 调用结果：成功，或者带明确原因的拒绝。 */
public sealed interface CallResult permits CallResult.Success, CallResult.Rejected {

    record Success(ServiceInstance instance, String response, int attempts,
                   List<String> triedInstanceIds) implements CallResult {}

    record Rejected(RejectionReason reason, String message, int attempts,
                    List<String> triedInstanceIds) implements CallResult {}
}
