package com.github.highcumontoa.localservicediscoverylbjava.registry;

/** 注册失败的可区分原因。 */
public enum RegistrationFailureReason {
    /** 同一实例 ID 已处于已注册（健康或暂时不健康）状态。 */
    ALREADY_REGISTERED,
    /** 该 ID 曾被主动注销，留有墓碑，禁止再被静默覆盖。 */
    DEREGISTERED_INSTANCE
}
