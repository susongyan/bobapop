# API 与锁语义

## 对象模型

- `RedisLockClient` 是线程安全、可全局复用的工厂，不保存锁状态。
- 每次 `createLock(key)` 创建一个新的 `RedisLock`，并生成独立 UUID token。
- `RedisLock` 保存本次锁会话的 token、持有计数和 owner 线程 ID。
- 不使用 `ThreadLocal`，也不维护按 key 存放锁对象的全局 Map。

同一个线程只有继续使用同一个 `RedisLock` 对象才会重入。再次调用
`createLock` 即使传入相同 key，也会得到新 token 并参与正常竞争。

## Redis 操作

首次获取使用：

```text
SET <key> <token> NX PX <leaseMillis>
```

同一对象重入时，使用单 key Lua 脚本原子校验 token 并刷新 TTL；最终解锁时使用单 key Lua
脚本校验 token 后删除 key。Lua 不保存重入计数，也不维护额外 Redis 数据结构。

默认完整 key 为 `redis-lock:<业务 key>`，生产环境建议配置应用专属前缀：

```java
RedisLockClient lockClient = new RedisLockClient(backend, "order-service:lock:");
RedisLock lock = lockClient.createLock("order:{123}");
```

Redis Cluster 中可使用 `{...}` hash tag；当前脚本只操作一个 key，因此一次脚本调用不能跨多个 slot。

## 获取与解锁

立即只尝试一次：

```java
boolean acquired = lock.tryLockNow(30, TimeUnit.SECONDS);
```

带等待时间的调用：

```java
boolean acquired = lock.tryLock(500, 30_000, TimeUnit.MILLISECONDS);
```

也提供毫秒重载：

```java
boolean acquired = lock.tryLock(500, 30_000);
boolean immediate = lock.tryLockNow(30_000);
```

参数规则：

- `waitTime` 必须大于 0；0 和负数均非法。
- `leaseTime` 必须大于 0。
- `waitTime` 只限制重试截止时间，不会取消已经发出的同步 Redis 命令。
- 带等待的 API 使用轮询，不提供无限等待语义；立即尝试使用 `tryLockNow`。
- 重入不会等待，只校验 token 并刷新本次传入的完整租约。

解锁必须与成功获取一一对应，并放在 `finally` 中：

```java
if (!lock.tryLock(500, 30_000, TimeUnit.MILLISECONDS)) {
    throw new IllegalStateException("order is busy");
}
try {
    processOrder();
} finally {
    lock.unlock();
}
```

未持有、非 owner 线程或最终 token 已失效时会抛出相应异常；`isHeldByCurrentThread()` 只反映
对象本地状态，不证明 Redis 租约仍有效。

## 重入规范

把同一个锁对象沿调用链显式传递：

```java
void handle(RedisLockClient client, String id) throws InterruptedException {
    RedisLock lock = client.createLock("order:{" + id + "}");
    if (!lock.tryLock(500, 30_000, TimeUnit.MILLISECONDS)) {
        throw new IllegalStateException("order is busy");
    }
    try {
        update(lock);
    } finally {
        lock.unlock();
    }
}

void update(RedisLock lock) {
    if (!lock.tryLockNow(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("unexpected contention");
    }
    try {
        writeDatabase();
    } finally {
        lock.unlock();
    }
}
```

`RedisLock` 不应注册为 Spring 单例、按 key 缓存、放入静态字段、跨请求复用或跨线程传递。
`RedisLockClient` 和 backend 可以是单例 Bean，锁对象必须按请求/调用链创建。

## 客户端接入

Spring Data Redis、Jedis 和 Lettuce 的完整示例分别位于 `demo/` 和各适配器模块。核心原则是：

- 客户端连接、连接池、认证、TLS、超时和关闭均由业务项目负责。
- Jedis 2.x/3.x 使用 legacy 模块；Jedis 4.x+ 使用 modern 模块。
- Lettuce 适配器使用同步命令，不要在 Netty 事件循环线程中执行可能等待的锁调用。
- Cluster 适配器只需提供 Cluster 客户端连接；单 key 脚本由客户端按 slot 路由。

## 租约、异常与边界

固定租约模式没有后台自动续期，`leaseTime` 应覆盖业务最长执行时间、GC 暂停和网络抖动，并留出余量。
需要自动续期时必须显式调用 `tryLockWithWatchdog`，并按 [Watchdog 使用场景与设计](watchdog.md)
处理状态和失锁。捕获 `LockLostException` 后应停止依赖独占性的写操作。

Redis 主从复制通常是异步的，主节点确认加锁后、锁数据同步前发生故障切换，可能出现两个 owner。
网络分区、进程暂停或机器挂起也可能使业务在租约到期后继续运行。该锁是单 Redis key 的互斥租约，
不是跨故障域共识协议；不提供公平锁、读写锁、联锁、红锁或 Pub/Sub 唤醒。

业务关键写入建议结合幂等键、数据库唯一约束、版本号 CAS 或 fencing token。当前实现不生成 fencing token，
也不执行 `WAIT` 副本确认，相关分析见[主从切换与 WAIT 复制确认](replica-failover-and-wait.md)。
