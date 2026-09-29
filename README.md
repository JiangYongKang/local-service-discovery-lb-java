# 本地注册发现与负载均衡（local-service-discovery-lb-java）

一套**纯本地、进程内**的服务注册 / 发现 / 负载均衡 / 调用重试能力。注册信息只存内存，
被调实例是同进程的伪造节点，调用走“内存回环”（无 socket、无远程地址、无凭据），
调用方与被调实例全部由本工程自己提供，可直接做确定性集成验证，不依赖任何外部注册中心。

- Java 21 / Spring Boot 3.4（仅借用其装配与测试体系，能力本身不依赖 Web 容器）
- 包结构
  - `registry` —— 纯内存注册中心、实例状态机、可区分的失败原因
  - `lb` —— 随机洗牌式轮询、最少连接两种策略
  - `client` —— 调用方：健康过滤、失败换实例重试、明确拒绝结果
  - `local`  —— 本机伪造节点、内存回环传输、心跳代理、本地演示装配/入口
  - `tests`  —— 场景化集成测试（打印输入 / 候选实例状态 / 判定依据）

---

## 1. 注册协议（内存、无外部依赖）

实例标识：`instanceId` 全局唯一；注册时携带 `serviceName / host / port`
（本地约定 host=`127.0.0.1`，port 仅作描述，不真正监听）。

| 操作 | 语义 | 失败时抛出的原因（`RegistryException.Reason`） |
|---|---|---|
| `register(instance)` | 注册新实例，初始 `HEALTHY`，注册时刻记为首次心跳 | `ALREADY_REGISTERED`：实例仍在册（健康或暂时不健康），重复注册**不覆盖**；`DEREGISTERED_INSTANCE`：同 id 处于注销终态，禁止静默顶替；`INVALID_ARGUMENT`：参数非法 |
| `heartbeat(id, at)` | 上报心跳（`at` 为时间戳） | `STALE_HEARTBEAT`：`at` 早于已接受的最后心跳（迟到的旧心跳）；`DEREGISTERED_INSTANCE`：实例已主动注销，心跳不可救活；`UNKNOWN_INSTANCE`：从未注册或已被过期清理 |
| `deregister(id)` | 主动注销，进入**终态** | `DEREGISTERED_INSTANCE`：重复注销；`UNKNOWN_INSTANCE`：不存在/已被清理 |
| `sweepExpired()` | 清理长期心跳丢失的实例（记录消失） | 正常返回清理数量；与注销不同，被清理后同 id 可重新注册 |

要点：

- **重复注册、迟到旧心跳、注销后再引用都不会被静默接受或覆盖**，每种情况给出上面可区分的原因。
- 主动注销的记录会保留为 `DEREGISTERED`（终态可追溯，后续引用一律得到 `DEREGISTERED_INSTANCE`）；
  过期清理则是把记录彻底移除（之后引用得到 `UNKNOWN_INSTANCE`，同 id 可重新注册）。
- 并发安全：实例状态全部在实例内置锁下读写，注册表用 `ConcurrentHashMap#compute`
  保证“检查 + 放入”原子；加锁顺序恒为「注册表 → 实例」，无锁序倒置。

## 2. 健康判定与过期规则

配置：`RegistryConfig.ofMillis(heartbeatTtlMillis, evictAfterMillis, ticker)`（默认 500 / 1500，时间源可替换）。

状态机：

```
注册 → HEALTHY ──(now - 最后心跳) > ttl──▶ UNHEALTHY
              ▲                            │
              └──────── 新鲜心跳 ──────────┘   （可反复恢复）
                                           │
              任 意 状 态 ── deregister ──▶ DEREGISTERED（终态，心跳不可救活）
                                           │
                  UNHEALTHY 且 (now - 最后心跳) ≥ ttl + evictAfter
                                           ▼
                                    sweepExpired 清理（记录消失）
```

- **暂时不健康（心跳过期）**：超过 `ttl` 没心跳 → `UNHEALTHY`，不纳入健康候选，但记录保留；
  一条新鲜心跳立刻恢复 `HEALTHY`。
- **彻底下线（主动注销）**：`deregister` → `DEREGISTERED`，永不自动恢复。
- **最终过期清理**：`UNHEALTHY` 持续到距最后一次心跳达到 `ttl + evictAfter` 时，
  被 `sweepExpired()` 移除。清理线以**最后一次心跳**为基准（而非进入 UNHEALTHY 的时刻），
  因此“过期→救活→再过期”的实例不会被提前误清，其宽限期随每次心跳顺延。
- 每次查询健康候选 / 清理扫描时都会先用当前时间惰性刷新健康状态，时间源 `Ticker` 可在测试中完全控制。

## 3. 两种选择策略

策略接口 `LoadBalancer` 只在**调用方传入的健康候选**中选择，健康过滤由注册中心保证
（`UNHEALTHY` / `DEREGISTERED` 永不进入候选）。

1. **随机洗牌式轮询 `RandomRoundRobinLoadBalancer`**
   - 每个服务维护一份随机洗牌后的实例队列，依次出队；队列耗尽后对同一批成员重新洗牌。
   - 语义：每个洗牌周期内每个实例恰好被选中一次（轮询、均匀），周期起点与批次顺序随机。
   - 健康成员集合变化（恢复 / 过期 / 注销导致进出）时立即重建队列。
2. **最少连接 `LeastConnectionsLoadBalancer`**
   - 实时读取每个实例的**当前活跃连接数**，选最小者；并列时随机打破平局。
   - 活跃连接数由内存传输层在“调用开始 +1 / 结束 -1（finally 配平）”维护，连接数变化后下一次选择立即反映。

## 4. 发起调用与重试约束（`ServiceClient`）

- 每次尝试前都取一次**新鲜健康快照**；重试间隙实例被注销 / 过期时不会再选到它。
- 已失败实例进入黑名单，**绝不回到刚失败过的实例**，也不会选择健康快照之外的实例。
- 失败分两类（`InstanceCallException.Kind`）：
  - `CONNECTION_FAILURE`（实例已不可用 / 连不上）：总是换另一个健康实例重试；
  - `BUSINESS_FAILURE`（业务失败）：仅当请求声明 `retryable=true` 才换实例；
    `retryable=false` 时首次失败即拒绝，**不对不该重试的请求反复重试**。
- 重试受 `maxAttempts` 与“健康实例是否都已试过”双重约束。
- 没有可用实例时返回**明确、可区分的 `InvocationOutcome`（拒绝原因）**，不返回 null、不向调用方抛内部异常：

| 拒绝原因 `RejectReason` | 含义 |
|---|---|
| `NO_INSTANCE_REGISTERED` | 该服务从未注册过任何实例 |
| `NO_HEALTHY_INSTANCE` | 有记录，但全部心跳过期或已注销 |
| `ALL_INSTANCES_FAILED` | 健康实例都已尝试且失败（不回头） |
| `NON_RETRYABLE_REQUEST` | 不可重试请求首次业务失败即拒绝 |
| `RETRY_LIMIT_REACHED` | 达到最大尝试次数仍失败 |

成功结果携带响应、命中实例、完整尝试序列与尝试次数。

## 5. 本地验证方法

所有通信都在内存/回环概念内完成，无需真实网络。

### 5.1 一键演示（确定性可控时钟，自动退出）

```bash
mvn -q package -DskipTests
java -jar target/local-service-discovery-lb-java-1.0-SNAPSHOT.jar --local.demo.enabled=true
```

演示顺序覆盖：随机轮询 9 次选择 → 最少连接随在途连接变化 → 心跳过期/救活 →
最终过期清理 → 主动注销终态与各类可区分拒绝 → 失败换实例重试（不回头）→
无实例时的明确拒绝。进程在演示结束后自动关闭（exit code 0）。

### 5.2 场景化集成测试（打印输入 / 候选状态 / 判定依据）

```bash
mvn test
```

| 测试类 | 覆盖场景 |
|---|---|
| `HeartbeatExpiryRecoveryTest` | 心跳过期→暂时不健康→新鲜心跳恢复；迟到旧心跳拒绝；最终过期清理后再引用=UNKNOWN |
| `DeregisterRejectTest` | 主动注销终态；重复注册/重复注销/注销后心跳/同 id 重注册的可区分原因；注销后不再被选中 |
| `LeastConnectionsTest` | 最少连接随真实在途连接数变化（含并列随机）；随机洗牌轮询每周期均匀分布 |
| `RetryFailoverTest` | 连接失败自动换实例且不回头；不可重试请求只试一次；四种拒绝原因；重试间隙注销不再被选 |
| `ConcurrencyConsistencyTest` | 12 线程并发注册/心跳/注销/调用交错：注册唯一、状态收敛、活跃连接统计配平、心跳版本单调 |

测试输出形如：

```
================ 场景: 心跳过期 / 恢复 / 最终清理 ================
  [输入] ttlMillis / evictAfterMillis = 500 / 1500
  [候选实例状态] [[a=HEALTHY, b=HEALTHY]]
  [过程] t=600 超过 ttl，无健康候选，但记录保留
  [判定依据] 迟到旧心跳(heartbeatAt=-10) 被拒，原因=STALE_HEARTBEAT
  [PASS] 心跳可把暂时不健康的实例救回 HEALTHY
```

### 5.3 作为 Spring Bean 使用

`LocalConfig` 默认装配：`Ticker`(系统时钟) → `RegistryConfig`(500/1500) →
`ServiceRegistry` → `LocalTransport` → 最少连接负载均衡器 → `ServiceClient(maxAttempts=3)`。
默认 `local.demo.enabled` 关闭，不启动演示 Runner，Web 端口随机（`server.port=0`）。
