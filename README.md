# 本地服务注册发现与负载均衡（local-service-discovery-lb-java）

一套**完全跑在本机进程内**的服务注册、发现、负载均衡与失败重试能力：

- 注册数据全部在内存（`InMemoryServiceRegistry`），HTTP 端点只绑定 `127.0.0.1` 回环地址；
- 不依赖任何外部注册中心（无 Eureka/Consul/ZooKeeper）、不需要真实凭据、不访问远程服务；
- 调用方（`ServiceClient`）与被调用实例（`FakeLocalInstance` 伪实例）都由本工程自带，可直接集成验证。

## 包结构

| 包 | 关键类 | 职责 |
|---|---|---|
| `registry` | `ServiceRegistry` / `InMemoryServiceRegistry` | 注册、心跳、注销、健康状态、活跃连接计数、过期清理 |
| `loadbalancer` | `RandomRoundRobinLoadBalancer`、`LeastConnectionsLoadBalancer` | 两种实例选择策略，只选健康实例 |
| `client` | `ServiceClient`、`Transport`、`CallResult` | 选实例、维护连接计数、失败换实例重试、明确拒绝 |
| `fake` | `FakeLocalInstance`、`FakeTransport` | 本机内存伪实例与回环传输（不做真实网络 IO） |
| `config` | `DiscoveryConfig`、`EvictionScheduler` | Spring 装配与周期性过期扫描 |
| `web` | `DiscoveryController` | 回环 HTTP 验证入口 |

## 注册协议

所有操作以 `(serviceName, instanceId)` 唯一定位一个实例，HTTP 基路径：

```
POST   /local-discovery/services/{serviceName}/instances                 # 注册
DELETE /local-discovery/services/{serviceName}/instances/{instanceId}    # 主动注销
POST   /local-discovery/services/{serviceName}/instances/{instanceId}/heartbeat?seq=N  # 心跳
GET    /local-discovery/services/{serviceName}/instances                 # 查看全部实例状态
POST   /local-discovery/services/{serviceName}/instances/{instanceId}/availability?available=true|false
POST   /local-discovery/services/{serviceName}/round-robin/reset         # 重置轮询游标（仅验证用）
POST   /local-discovery/services/{serviceName}/invoke?strategy=ROUND_ROBIN|LEAST_CONNECTIONS&retryable=true|false
```

注册请求体示例：

```json
{ "instanceId": "i-1", "port": 9001, "metadata": {} }
```

实例地址一律登记为 `127.0.0.1:<port>`。**注册即视为一次心跳**。

### 明确拒绝（不静默接受、不静默覆盖）

| 操作 | 异常/原因（Java） | HTTP |
|---|---|---|
| 重复注册健康/暂时不健康实例 | `RegistrationException(ALREADY_REGISTERED)` | 409 |
| 复用已主动注销的实例 ID | `RegistrationException(DEREGISTERED_INSTANCE)` | 409 |
| 未知（从未注册或已被清理）实例的心跳 | `HeartbeatException(UNKNOWN_INSTANCE)` | 404 |
| 已主动注销实例的心跳（不允许复活） | `HeartbeatException(DEREGISTERED_INSTANCE)` | 410 |
| 迟到的旧心跳（`seq` 不大于已接受值） | `HeartbeatException(STALE_HEARTBEAT)` | 409 |

## 健康判定与过期规则

实例有三种状态，区分两种“不可用”：

| 状态 | 含义 | 能否恢复 |
|---|---|---|
| `HEALTHY` | 心跳新鲜，可被选择、可增加活跃连接 | — |
| `UNHEALTHY` | **心跳过期暂时不健康**（或一次调用失败后被标记） | 收到更新的心跳即恢复 `HEALTHY` |
| `DEREGISTERED` | **主动注销彻底下线**，保留墓碑 | 终态：心跳不能复活、同 ID 不能重注、永不被清理 |

过期推进由 `EvictionScheduler` 周期性调用 `evictExpired`（也可手动调用，测试用可注入时钟即时推进）：

1. 距最后接受的心跳超过 `discovery.heartbeat-ttl`：`HEALTHY → UNHEALTHY`；
2. `UNHEALTHY` 持续超过 `discovery.evict-after`：从注册表**彻底清理**，此后 `snapshot` 返回不存在、同 ID 允许重新注册；
3. `DEREGISTERED` 墓碑**永不清理**，因此“注销后又被引用”永远能被识别并拒绝。

并发安全：每个实例有独立 monitor，注册/心跳/注销/连接计数对同一实例串行化；跨实例互不阻塞。

## 两种选择策略

两种策略的候选集都**只包含当前 `HEALTHY` 实例**，并接受 `excludedInstanceIds`（重试时用来排除刚失败的实例）：

1. **随机化轮询 `RANDOM_ROUND_ROBIN`**
   - 游标记录“上次服务的实例 ID”，下次选排序候选中它的后继；首次起点随机。
   - 基于 ID（而非数字下标）推进，实例动态增减时不会错位或漏选。
   - 固定实例集上严格均匀：N 个实例选 N×k 次，每个恰好 k 次。

2. **最少连接 `LEAST_CONNECTIONS`**
   - 选实时 `activeConnections` 最小的实例；并列时在并列者中随机。
   - 每次都读最新快照，活跃连接一变化（拿连接/归还）选择结果立即跟着变。

两者语义差异：轮询**不看**连接数，始终均匀；最少连接会避开在途请求多的实例（某实例 5 个在途连接时，流量全部落到 0 连接的其他实例）。

## 请求重试约束（`ServiceClient`）

- 选中实例后先 `acquireConnection`（最后的健康校验：快照与拿连接之间实例可能刚过期/注销），
  请求结束在 `finally` 中 `releaseConnection`，成功与失败都归还计数。
- 实例调用抛 `InstanceUnavailableException`：标记 `UNHEALTHY`、加入本轮排除集；
  **绝不回到刚失败过的实例**，每个实例一轮内最多尝试一次。
- `retryable=false`（非幂等）请求：业务调用一旦失败立即返回
  `Rejected(NON_RETRYABLE_REQUEST)`，**绝不换实例重试**。
- 连接获取失败发生在请求处理之前，对任何请求都安全，允许换实例，但同样计入次数上限。
- 总尝试次数受 `discovery.max-attempts` 限制。
- 拒绝结果是**可区分的封闭类型** `CallResult.Rejected`，不返回 null、不抛内部异常：

| `RejectionReason` | 含义 |
|---|---|
| `NO_HEALTHY_INSTANCE` | 当前没有任何健康候选（含全部主动注销） |
| `ALL_INSTANCES_FAILED` | 所有健康实例都试过且都失败 |
| `NON_RETRYABLE_REQUEST` | 非幂等请求首次失败，按约束不重试 |
| `MAX_ATTEMPTS_REACHED` | 达到次数上限，仍有未试过的健康实例 |

## 本地验证方法

### 方式一：自动化测试（推荐，无需真实等待）

```bash
mvn test
```

测试用 `MutableClock` 手动推进时间、用内存伪实例模拟失败，共 16 个用例，覆盖：

- `HeartbeatLifecycleTest`：心跳过期转不健康、新心跳恢复、最终过期清理后同 ID 重注；
  重复注册、迟到旧心跳、注销后心跳/重注/引用的可区分失败原因；
- `LoadBalancerDistributionTest`：轮询均匀分布与健康过滤、最少连接随计数翻转、两种策略分布差异；
- `FailoverRetryTest`：失败后换实例且不回失败实例、非幂等不重试、四种拒绝原因、
  maxAttempts 约束、24 线程并发下状态与连接计数不错乱；
- `LocalDiscoveryIntegrationTest`：通过真实 `127.0.0.1` 随机端口 HTTP 跑完整链路。

每个测试都会打印**输入、候选实例状态（status/activeConnections/端点可用性）与最终判定依据**，
例如：

```
输入 charge（e-1/e-2 失败，仅 e-3 健康）-> { "instanceId" : "e-3", "attempts" : 3,
  "outcome" : "SUCCESS", "triedInstanceIds" : [ "e-1", "e-2", "e-3" ] }
```

### 方式二：手工回环 HTTP

```bash
mvn spring-boot:run
# 另开终端：
curl -s -XPOST localhost:8080/local-discovery/services/demo/instances \
  -H 'Content-Type: application/json' -d '{"instanceId":"i-1","port":9001,"metadata":{}}'
curl -s -XPOST 'localhost:8080/local-discovery/services/demo/invoke?strategy=ROUND_ROBIN' \
  -H 'Content-Type: text/plain' -d 'hello'
```

## 配置项（`application.properties`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `server.address` | `127.0.0.1` | 只监听回环 |
| `discovery.heartbeat-ttl` | `10s` | 心跳过期 -> 暂时不健康 |
| `discovery.evict-after` | `30s` | 暂时不健康超过此时长 -> 彻底清理 |
| `discovery.evict-interval` | `5000`ms | 过期扫描周期 |
| `discovery.max-attempts` | `3` | 单次调用最大尝试次数 |
