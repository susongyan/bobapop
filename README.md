# BobaPop

一个不依赖 Redisson 的轻量 Redis 分布式锁组件，支持：

- Spring Data Redis、原生 Jedis、原生 Lettuce；
- Spring Boot 1.x、2.x、3.x；
- 固定租约、对象级重入和显式 watchdog；
- Redis Standalone、主从和 Cluster 的单 key 锁场景。

## 项目命名

- 展示名：`BobaPop`
- GitHub：`git@github.com:susongyan/boba-pop.git`
- Maven parent：`boba-pop-parent`
- 模块：`boba-pop-*`
- Java 包名：`io.github.susongyan.bobapop`

## 先了解边界

本组件是“单 Redis key + token + TTL”的互斥租约，不是跨故障域共识协议。

- Redis 主从复制是异步的，故障切换窗口可能出现两个 owner；
- 网络分区、进程暂停或机器挂起可能使旧业务继续执行；
- 不执行 `WAIT`，不生成 fencing token；
- 不支持公平锁、读写锁、联锁、红锁或 Pub/Sub 唤醒；
- watchdog 只能自动续租，不能替代 `unlock()` 或 fencing token。

涉及数据库、消息或文件的关键写入，应结合幂等键、唯一约束、版本号 CAS 或 fencing token。

## 快速开始：Spring Boot + Spring Data Redis

假设项目已经是 Spring Boot 应用，并且已有正常工作的 `StringRedisTemplate`。

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.susongyan</groupId>
    <artifactId>boba-pop-spring-data-redis</artifactId>
    <version>${boba-pop.version}</version>
</dependency>
```

将 `${boba-pop.version}` 替换为团队发布的实际版本。当前源码版本为 `0.1.0-SNAPSHOT`；如果尚未发布到 Maven 私服，
可以在项目根目录执行 `mvn -DskipTests install` 安装到本地仓库后使用该版本。

Spring Data Redis 和底层 Redis 客户端版本由业务项目或 Spring Boot BOM 管理。

### 2. 注册锁客户端

```java
@Configuration
public class LockConfiguration {

    @Bean(destroyMethod = "close")
    public RedisLockClient redisLockClient(StringRedisTemplate template) {
        return new RedisLockClient(
                new RedisTemplateLockBackend(template),
                "order-service:lock:");
    }
}
```

`RedisLockClient` 和 backend 可以作为 Spring 单例 Bean。`RedisLock` 必须按请求或调用链创建。
`destroyMethod = "close"` 用于关闭 watchdog 调度器；不会关闭由 Spring 管理的 `StringRedisTemplate`。

### 3. 普通业务加锁

```java
@Service
public class OrderService {
    private final RedisLockClient lockClient;

    public OrderService(RedisLockClient lockClient) {
        this.lockClient = lockClient;
    }

    public void update(String orderId) throws InterruptedException {
        RedisLock lock = lockClient.createLock("order:" + orderId + "");
        if (!lock.tryLock(500, 30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("order is busy");
        }
        try {
            processOrder(orderId);
        } finally {
            lock.unlock();
        }
    }
}
```

代码片段省略常规 `import`。建议业务代码始终使用 `try/finally` 配对释放。

## 三种常用获取方式

```java
// 1. 普通加锁：最多等待 500ms，成功后租约 5s
lock.tryLock(500, 5, TimeUnit.SECONDS);

// 2. 立即尝试：只发起一次获取，不进行轮询等待
lock.tryLockNow(5, TimeUnit.SECONDS);

// 3. watchdog：最多等待 500ms，成功后按客户端配置自动续租
lock.tryLockWithWatchdog(500, TimeUnit.MILLISECONDS);
```

### 固定租约还是 watchdog

| 场景 | 推荐模式 |
| --- | --- |
| 执行时间可以预估 | 固定租约 `tryLock` |
| 不希望等待，抢不到立即返回 | `tryLockNow` |
| 执行时间波动较大 | `tryLockWithWatchdog` |
| 可能忘记 `unlock()` | 不建议 watchdog，优先固定租约 |
| 涉及关键外部写入 | 任意模式都必须配合幂等、CAS 或 fencing token |

## API 参数速查

| API | 参数和语义 | 使用场景 |
| --- | --- | --- |
| `tryLockNow(leaseTime, unit)` | 只尝试一次；`leaseTime > 0` | 不等待，竞争失败立即返回 |
| `tryLockNow(leaseMillis)` | 上一个方法的毫秒重载 | 同上 |
| `tryLock(waitTime, leaseTime, unit)` | 最多等待 `waitTime`；成功后租约为 `leaseTime` | 普通业务加锁 |
| `tryLock(waitMillis, leaseMillis)` | 上一个方法的毫秒重载 | 同上 |
| `tryLockWithWatchdog(waitTime, unit)` | `waitTime > 0`；租约和续租周期来自 `WatchdogConfig` | 执行时间不可预估 |
| `unlock()` | 释放一层重入；计数归零时删除 Redis key | 必须放在 `finally` |
| `isHeldByCurrentThread()` | 只反映本地对象和线程状态 | 判断当前调用链是否持有 |
| `getState()` | `HEALTHY/SUSPECT/LOST/RELEASED` | watchdog 状态排查 |
| `isLeaseAlive()` | 仅本地状态为 `HEALTHY` 时返回 true | 关键写入前做保守检查 |
| `RedisLockClient.close()` | 停止 watchdog 调度器 | Spring 容器销毁或客户端关闭 |

注意：

- `waitTime` 和 `leaseTime` 是不同概念；前者是等待别人释放多久，后者是自己持有多久；
- `waitTime == 0` 或负数非法，立即尝试使用 `tryLockNow`；
- `tryLockNow` 不进行客户端侧轮询，但正在执行的 Redis 命令仍受客户端 command/socket timeout 影响；
- 重入必须复用同一个 `RedisLock` 对象，不能重新 `createLock(sameKey)`；
- `isLeaseAlive()` 是本地状态判断，不等同于对 Redis 当前状态的强一致证明。

## 重入规则

首次加锁和重入必须使用同一个 `RedisLock` 对象：

```java
public void updateOrder(String orderId) throws InterruptedException {
    // 每次请求创建一个新的锁对象，同时生成本次锁会话的 token。
    RedisLock lock = lockClient.createLock("order:{" + orderId + "}");

    // 首次加锁：Redis 中不存在该 key 时写入 token 和 TTL，holdCount = 1。
    if (!lock.tryLock(500, 30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("order is busy");
    }
    try {
        updateOrderDetail(lock);
    } finally {
        // 外层 finally 释放最后一层重入，删除 Redis key。
        lock.unlock();
    }
}

private void updateOrderDetail(RedisLock lock) throws InterruptedException {
    // 重入：必须复用外层传入的同一个对象和同一个线程。
    // 客户端校验 token 后刷新 TTL，holdCount 从 1 增加到 2。
    if (!lock.tryLockNow(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("unexpected contention");
    }
    try {
        writeDatabase();
    } finally {
        // 非最终 unlock 只将 holdCount 从 2 减回 1，Redis key 仍然保留。
        lock.unlock();
    }
}
```

规则总结：

- 首次加锁执行 `SET key token NX PX leaseMillis`；
- 同一个锁对象、同一个 owner 线程重入时复用 token，并增加本地 `holdCount`；
- 非最终 `unlock()` 只减少 `holdCount`，不会删除 Redis key；
- `holdCount` 归零时才校验 token 并删除 Redis key；
- 不要在重入方法中再次 `createLock(sameKey)`，新对象会生成新 token，按新的竞争请求处理；
- 锁对象不能注册为 Spring 单例、按 key 缓存、跨请求或跨线程复用。

## Watchdog 使用提示

watchdog 适用于业务耗时不可准确预估的场景，必须显式开启。默认配置为租约 30 秒、每 10 秒续租一次，最大自动续租时长不限制：

```java
RedisLock lock = lockClient.createLock("order:{" + orderId + "}");
if (!lock.tryLockWithWatchdog(500, TimeUnit.MILLISECONDS)) {
    throw new IllegalStateException("order is busy");
}
try {
    processOrder(orderId);
} finally {
    lock.unlock();
}
```

如果需要调整租约、续租周期或最大自动续租时长，再在创建 `RedisLockClient` 时传入自定义 `WatchdogConfig`。业务接入初期建议先使用默认配置，确认调用链和释放逻辑后再按实际耗时、Redis 延迟和故障恢复时间调整。

例如，将默认租约调整为 60 秒、每 20 秒续租一次，并限制单次 watchdog 最长自动续租 10 分钟：

```java
@Configuration
public class LockConfiguration {

    private final WatchdogConfig watchdogConfig = new WatchdogConfig(
            60, TimeUnit.SECONDS,
            20, TimeUnit.SECONDS,
            10, TimeUnit.MINUTES);

    @Bean(destroyMethod = "close")
    public RedisLockClient redisLockClient(StringRedisTemplate template) {
        return new RedisLockClient(
                new RedisTemplateLockBackend(template),
                "order-service:lock:",
                watchdogConfig);
    }
}
```

三个时间参数依次为 `leaseTime`、`renewInterval` 和 `maxWatchdogDuration`。最大时长传 `0` 时表示不限制；生产环境建议设置一个合理上限，避免忘记解锁导致锁长期占用。

当前 watchdog 只提供带等待的 `tryLockWithWatchdog(waitTime, unit)`；立即尝试仍使用固定租约的 `tryLockNow`，不要用 `waitTime == 0` 代替。

watchdog 行为：

- 首次成功后后台刷新当前 token 的 TTL；
- 同一对象重入不创建新的续租任务；
- Redis 返回 `false` 时确定丢锁；
- Redis 命令异常时进入 `SUSPECT`，只对原 token 做有限重试；
- 续租失败不会重新抢锁；
- 工作线程通过 `getState()`、`isLeaseAlive()` 和最终 `unlock()` 的 `LockLostException` 感知失锁；
- watchdog 不会自动中断正在执行的工作线程，关键写入前仍需主动检查状态；
- 业务仍然必须 `finally { lock.unlock(); }`；
- 生产环境建议设置最大自动续租时长。

完整设计见 [Watchdog 使用场景与设计](docs/watchdog.md)。

## 原生客户端接入

如果不使用 `RedisTemplate`：

- Jedis 2.x/3.x：`boba-pop-jedis-legacy` + `JedisPool`；
- Jedis 4.x+：`boba-pop-jedis-modern` + `JedisPooled`、`JedisCluster` 或 `UnifiedJedis`；
- Lettuce 5.x+：`boba-pop-lettuce` + `StatefulRedisConnection` 或 Cluster connection。

完整配置和连接生命周期说明见[快速开始与客户端接入](docs/getting-started.md)。

## 模块与兼容性

| 模块 | 用途 | 编译基线 |
| --- | --- | --- |
| `boba-pop-core` | 锁算法、watchdog 和公共 API | Java 8 |
| `boba-pop-spring-data-redis` | `StringRedisTemplate` 适配器 | Spring Data Redis 1.8 baseline |
| `boba-pop-jedis-legacy` | Jedis 2.x/3.x standalone、主从、Cluster | Jedis 2.9 baseline |
| `boba-pop-jedis-modern` | Jedis 4.x+ standalone、主从、Cluster | Jedis 4.4 baseline |
| `boba-pop-lettuce` | Lettuce 5.x+ 同步和 Cluster 适配器 | Lettuce 5.3 baseline |
| `demo/boot1`、`demo/boot2`、`demo/boot3` | Boot 1.5、2.x、3.x 编译兼容示例 | 对应 Boot BOM |

## 研发接入检查清单

- [ ] 使用团队发布的固定版本，不直接依赖 SNAPSHOT；
- [ ] 配置业务专属 key 前缀，例如 `order-service:lock:`；
- [ ] `RedisLockClient` 可作为单例，`RedisLock` 每次业务调用创建；
- [ ] 成功加锁后始终在 `finally` 中 `unlock()`；
- [ ] Redis command timeout 小于 leaseTime，并预留网络抖动空间；
- [ ] 捕获 `LockLostException` 后停止依赖独占性的写操作；
- [ ] watchdog 场景配置最大自动续租时长；
- [ ] 关键外部写入配合幂等、CAS、唯一约束或 fencing token；
- [ ] Lettuce 同步锁调用不运行在 Netty 事件循环线程；
- [ ] Cluster 使用单 key 和合理 hash tag，不跨 slot 执行脚本。

## 文档导航

- [快速开始与客户端接入](docs/getting-started.md)
- [API 与锁语义](docs/api-and-semantics.md)
- [Watchdog 使用场景与设计](docs/watchdog.md)
- [Redis Cluster 使用说明](docs/redis-cluster.md)
- [真实 Redis 集成测试](docs/integration-testing.md)
- [macOS + Colima 测试环境](docs/macos-colima.md)
- [主从切换与 WAIT 复制确认](docs/replica-failover-and-wait.md)

## 构建与验证

```bash
mvn clean test
```

普通构建不会连接外部 Redis。真实 Redis、Cluster、Jedis failover 和 Lettuce 测试命令见
[真实 Redis 集成测试](docs/integration-testing.md)。
