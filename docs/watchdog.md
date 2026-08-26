# Watchdog 使用场景与设计

## 当前状态

当前版本同时支持固定租约和显式开启的 watchdog。固定租约仍然是默认模式：

```java
if (!lock.tryLock(500, 30, TimeUnit.SECONDS)) {
    throw new IllegalStateException("order is busy");
}
try {
    processOrder();
} finally {
    lock.unlock();
}
```

watchdog 不会改变现有固定租约 API 的语义，也不会在普通 `tryLock` 调用中隐式启动。

## 适用场景

watchdog 适合业务执行时间无法准确预估，但仍然有明确生命周期的场景，例如：

- 批处理、文件处理或远程调用耗时可能波动；
- 业务可能经历短暂 GC、慢 IO 或网络抖动；
- 业务代码能够保证最终执行 `unlock()`；
- 只要求进程和 Redis 正常时锁能够持续持有。

不适合以下场景：

- 历史代码经常忘记 `unlock()`；
- 需要跨 Redis 故障域的强一致互斥；
- 需要保证旧 owner 在网络分区或进程暂停后绝不会继续写入；
- 业务没有可靠的停止、补偿或幂等机制。

watchdog 不能替代 `finally { unlock(); }`，也不能替代 fencing token、版本号 CAS 或数据库约束。

## 预期使用方式

固定租约继续作为默认模式。watchdog 必须通过独立、明确的方法开启，例如：

```java
RedisLock lock = client.createLock("order:{123}");
if (!lock.tryLockWithWatchdog(500, TimeUnit.MILLISECONDS)) {
    throw new IllegalStateException("order is busy");
}
try {
    processOrder();
} finally {
    lock.unlock();
}
```

建议默认租约为 30 秒，每 10 秒续租一次；续租周期应小于租约时间，并可加入少量随机抖动。
可以通过 `RedisLockClient` 传入 `WatchdogConfig` 自定义这些参数，也可以使用
`WatchdogConfig.defaults()` 获取默认配置。
同时支持配置 `maxWatchdogDuration`。默认可以不设上限，但生产环境建议设置上限，避免遗留代码忘记
解锁后永久占用锁。

例如将最大自动续租时间限制为 10 分钟：

```java
WatchdogConfig config = new WatchdogConfig(
        30, TimeUnit.SECONDS,
        10, TimeUnit.SECONDS,
        10, TimeUnit.MINUTES);
RedisLockClient client = new RedisLockClient(backend, "order-service:lock:", config);
```

## 重入与任务生命周期

- 首次加锁成功时创建一个 watchdog 任务；
- 同一个 `RedisLock` 对象重入只增加本地 `holdCount`，不创建新任务；
- 非最后一次 `unlock()` 不停止续租；
- `holdCount` 归零后删除 Redis key 并取消任务；
- 续租失败、确认丢锁或达到最大续租时长后停止任务；
- `RedisLock` 仍然按请求/调用链创建，不能按 key 缓存为全局单例。

watchdog 任务应由 `RedisLockClient` 共享的 `ScheduledExecutorService` 执行，不能为每个锁创建一个线程。
任务不应强引用整个 `RedisLock`，否则忘记 `unlock()` 时可能长期保留锁对象和调度任务。
`RedisLockClient.close()` 时必须取消未完成任务并关闭调度器。

## 续约失败处理

续约失败必须区分“Redis 明确返回失败”和“Redis 命令执行结果未知”。两者不能采用相同策略。

### Redis 明确返回 false

例如续租 Lua 返回 `0`，通常表示：

- key 已不存在；
- token 不匹配；
- 锁已过期并被其他请求获取。

这是确定丢锁，应立即：

1. 将本地状态标记为 `LOST`；
2. 停止 watchdog；
3. 不再重试续租；
4. 后续 `unlock()` 抛出 `LockLostException`。

不能在这里重新执行 `acquire`。重新抢锁会把原锁对象变成新的锁会话，破坏 token 和重入语义。

### Redis 命令抛异常

例如连接断开、Redis 主从切换、网络超时或 Redis 暂时不可用。这属于结果未知：命令可能没有到达 Redis，
也可能已经执行成功但响应丢失。

建议状态流转为：

```text
HEALTHY --命令异常--> SUSPECT
SUSPECT --同一 token 续租成功--> HEALTHY
SUSPECT --重试耗尽或租约安全窗口不足--> LOST
```

进入 `SUSPECT` 后，只允许对同一个 `key + token` 重试 `renewIfOwner`，不允许重新获取锁。
重试应有明确上限，例如 2～3 次，使用 100ms、300ms、500ms 的短退避；总重试时间不能超过当前租约剩余的
安全窗口。达到上限后必须按丢锁处理。

如果 Redis 客户端命令超时时间大于锁租约，续租命令可能在锁已过期后仍处于执行中。业务项目必须将客户端命令
超时配置在租约时间以内，并为网络抖动预留余量。

## 业务侧感知失锁

watchdog 在后台线程运行，不能可靠地中断正在执行的业务线程。当前版本通过锁对象的本地状态让工作线程主动感知：

- `getState()` 返回 `HEALTHY`、`SUSPECT`、`LOST` 或 `RELEASED`；
- `isLeaseAlive()` 只在本地状态为 `HEALTHY` 且当前对象仍持有锁时返回 `true`；
- Redis 明确返回续租失败时立即进入 `LOST`，续租异常时先进入 `SUSPECT`；
- `SUSPECT` 状态下 `isLeaseAlive()` 会保守返回 `false`，如果后续重试成功，状态才恢复为 `HEALTHY`；
- 重试耗尽进入 `LOST` 后 watchdog 停止，不会重新抢锁；
- `LOST` 状态下最终一次 `unlock()` 会抛出 `LockLostException`，且不会删除其他 owner 的锁。

业务应在循环处理、批次边界和关键外部写入前检查状态，并在最终释放时处理失锁异常：

```java
RedisLock lock = client.createLock("order:{123}");
if (!lock.tryLockWithWatchdog(500, TimeUnit.MILLISECONDS)) {
    throw new IllegalStateException("order is busy");
}

try {
    for (OrderItem item : items) {
        // 续租异常期间可能是 SUSPECT；不要继续执行依赖独占性的操作。
        if (!lock.isLeaseAlive()) {
            throw new LockLostException(lock.getKey());
        }
        processItem(item);
    }

    // 关键写入前再次检查，但这仍不是对 Redis 状态的强一致证明。
    if (!lock.isLeaseAlive()) {
        throw new LockLostException(lock.getKey());
    }
    writeResult();
} catch (LockLostException e) {
    // 停止依赖独占性的后续操作，按业务需要重试、补偿或记录告警。
    handleLockLost(e);
} finally {
    try {
        lock.unlock();
    } catch (LockLostException e) {
        // 释放时才发现丢锁同样需要记录，并确保不提交过期 owner 的结果。
        handleLockLost(e);
    }
}
```

状态检查只能缩短工作线程继续执行的窗口，不能保证检查完成后锁仍未过期。涉及数据库、消息或文件的关键写入，
仍必须结合幂等键、版本号 CAS、唯一约束或 fencing token。

## 能保证什么，不能保证什么

watchdog 能保证的是：业务进程、调度线程、客户端连接和 Redis 均正常时，锁可以在业务执行期间持续续租。

watchdog 不能保证：

- 网络分区期间旧 owner 自动停止；
- Redis 主从切换期间不会出现两个 owner；
- 进程长时间暂停后业务仍然拥有锁；
- 外部数据库或消息系统拒绝旧 owner 的写入；
- 具备 fencing token 或跨故障域共识能力。

因此 watchdog 是租约延长机制，不是强一致分布式锁协议。
