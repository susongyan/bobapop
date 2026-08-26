# Redis 主从切换与 WAIT 复制确认

## 文档结论

当前版本不执行 Redis `WAIT` 命令，也不对锁写入进行同步副本确认。

未来即使增加 `WAIT`，它也只能降低“锁刚写入主节点、尚未复制时主节点故障”造成锁丢失的概率，不能提供严格单 owner、线性一致性或共识协议级别的保证。

因此，本项目对 `WAIT` 的定位是：

> 可选的复制耐久性增强，而不是分布式锁正确性的最终保障。

## 当前实现

首次获取锁只执行：

```text
SET <key> <token> NX PX <leaseMillis>
```

主节点返回成功后，客户端立即认为锁已取得。Redis 默认使用异步主从复制，因此可能发生：

```text
客户端 A       Redis 主节点       Redis 副本       客户端 B
   |                 |                |                |
   |--- SET NX PX -->|                |                |
   |<----- OK -------|                |                |
   |                 |-- 尚未复制 --> |                |
   |                 X 主节点故障     |                |
   |                                  | 晋升为新主节点 |
   |                                  |<-- SET NX PX --|
   |                                  |------ OK ------>|
```

此时 A 和 B 都可能认为自己持有同一个逻辑锁。A 的旧主节点连接是否已经断开，并不能确保 A 的业务代码立刻停止。

当前设计接受这个 Redis 主从模型的边界，要求关键业务写入使用幂等键、数据库唯一约束、版本号 CAS 或 fencing token 等机制兜底。

## Redisson 的处理方式

Redisson 默认提供锁副本同步检查：

- `checkLockSyncedSlaves=true`：获取锁后检查锁写入是否同步到副本。
- `slavesSyncTimeout=1000`：等待副本同步的默认超时时间为 1000 毫秒。
- 副本确认数量不足时，Redisson 会让本次获取失败，并尝试释放刚取得的锁。

其目的主要是缩小下面这个故障窗口：

```text
锁写入主节点成功
    -> 尚未发送或确认到任何副本
    -> 主节点故障
    -> 不包含该锁的副本被提升
```

这种处理比只执行 `SET NX PX` 更稳健，但不能把 Redis 主从系统变成强一致锁服务。

### 默认等待几个副本

Redisson 的 `checkLockSyncedSlaves` 不是固定的 `WAIT 1` 策略。它把“已确认副本数”与客户端当前识别到的可用副本数比较：

| 当前 master 的可用副本数 | 通常等效的同步目标 |
| --- | --- |
| 1 | `WAIT 1 <slavesSyncTimeout>` |
| 2 | `WAIT 2 <slavesSyncTimeout>` |
| 0 | 没有可用于确认复制的副本 |

因此，在每个 master 只有一个 replica 的常规 Redis Cluster 中，故障前的锁获取通常等效于 `WAIT 1`，而不是 Redisson 永远固定执行 `WAIT 1`。

### 一主一从 Cluster 切主后的窗口

假设一个 slot 位于下列主从对：

```text
M1 (master) -> S1 (replica)
```

故障前，若锁写入的同步检查成功，S1 已确认收到锁。M1 故障后，S1 晋升为新的 M1，但在其他节点迁移或新 replica 补齐前，它会暂时没有 replica：

```text
旧 M1 故障
S1 晋升为新 M1
新 M1 的 connected_slaves = 0
```

已经在切主前成功完成副本确认的旧锁，通常能够随 S1 的晋升保留；真正需要关注的是切主后**新获取的锁**。

#### 路径 A：拓扑已刷新，复制保障退化

若 Redisson 已识别新 M1 当前没有可用 replica，它的同步目标会随可用副本数量降低。此时新锁可能仍然可以取得，但不再有副本确认：

```text
新 M1：SET 锁成功
可用副本：0
结果：锁可用，但只有新 M1 上的一份数据
```

这是一种可用性优先的退化，不应理解为“仍然满足至少一个副本确认”。在新 replica 补齐前，如果新 M1 再次故障，新取得的锁更容易丢失。

#### 路径 B：拓扑未及时刷新，加锁短暂失败

若 Redisson 仍认为新 M1 有一个可用副本，它会继续等待类似 `WAIT 1 1000` 的确认；但实际没有副本时，Redis 会在超时后返回 `0`。结果可能是：

- 每次尝试额外等待接近 `slavesSyncTimeout`，默认约 1000 毫秒。
- 获取锁失败，并出现 `None of slaves were synced` 一类异常。
- 客户端尝试删除刚写入的锁；切主、连接断开或超时期间，清理结果仍可能不确定。
- 若 master 或 slot 映射也未刷新，可能同时出现 `MOVED`、连接超时或请求短暂发往旧节点。

这不是“新主没有 replica”本身的 Redis bug：在没有副本的节点上，不可能让 `WAIT 1` 成功。问题在于客户端对可用副本数、master 地址和 slot 拓扑的刷新是否及时、准确。Redisson 的历史 issue 中出现过切主后 `None of slaves were synced`，以及 Cluster 拓扑刷新不一致导致持续 `MOVED`/连接异常的情况；具体行为需要结合所用 Redisson 版本和实际切主演练验证。

#### `WAIT` 在切主过程中的返回

| 调用位置 | 可能结果 | 含义 |
| --- | --- | --- |
| 旧主节点上正在等待确认 | 连接断开、I/O 超时或命令异常 | 不能依据异常判断锁是否已经写入或复制，获取必须视为失败 |
| 切主后在新主节点执行 `SET` 后再执行 `WAIT 1 1000` | 约 1000ms 后返回 `0` | 新主没有 replica，严格的至少一副本策略应拒绝本次锁 |
| 切主后新连接仅执行 `WAIT` | 不能确认旧连接/旧主上的写入 | `WAIT` 只覆盖当前物理连接此前的写命令 |

`WAIT 1 0` 在没有副本时会无限阻塞，因此复制确认策略必须使用有限超时。

## WAIT 的准确语义

例如：

```redis
SET lock:order:123 token NX PX 30000
WAIT 1 1000
```

`WAIT 1 1000` 表示：等待当前连接此前的写操作被至少一个副本确认，最多等待 1000 毫秒。

返回值是实际确认的副本数量。超时不一定以异常结束；调用方必须检查返回数量是否达到要求。

### 连接亲和性

`WAIT` 针对的是当前 Redis 连接此前执行的写操作，因此写锁和等待确认必须使用同一条物理连接：

```text
同一连接：SET -> WAIT        正确覆盖该次锁写入
连接 A：SET；连接 B：WAIT    不能正确确认连接 A 的该次写入
```

如果未来实现该能力：

- JedisPool 必须在一次借用的连接中完成 `SET + WAIT`，之后再归还连接。
- Lettuce 必须在同一个 `StatefulRedisConnection` 上完成两个操作。
- RedisTemplate 必须在同一个底层 connection callback 中完成两个操作。
- `WAIT` 消耗的时间必须计入 `tryLock` 的总等待预算。

## WAIT 能保证什么

在命令成功返回且确认数达到要求的那个时刻，`WAIT` 可以证明：

1. 当前连接在 `WAIT` 之前发送的锁写入，已经到达指定数量的副本。
2. 至少存在若干份内存中的锁数据副本，而不只有主节点上的一份。
3. 主节点在锁刚写入后立即故障时，只要从已确认且保留该数据的副本中选举新主节点，锁不会因“完全没有复制”而消失。
4. 调用方可以在副本确认不足时拒绝进入临界区，从而降低故障切换产生双 owner 的概率。

它是一种 best-effort 的同步复制确认，可以改善可用性与数据安全之间的取舍。

## WAIT 不能保证什么

### 不能保证被提升的一定是已确认副本

假设有两个副本，而调用方只要求 `WAIT 1`：

```text
master
 ├── replica-A：已确认锁
 └── replica-B：尚未收到锁
```

如果故障切换最终提升 replica-B，锁仍可能丢失。即使等待全部“当前已连接副本”，拓扑变化、错误的可用副本判断或连续故障仍可能破坏这一前提。

### 不能把 Redis 变成强一致系统

Redis 官方明确指出，`WAIT` 不会把 Redis 实例集合变成强一致的 CP 系统。它确认复制进度，不提供类似 Raft/Paxos 的多数派提交和线性一致性语义。

### 不能保证数据已经持久化到磁盘

普通 `WAIT` 确认的是副本已经接收复制数据，不等价于数据已经安全持久化。节点同时或连续故障时，已确认写入仍可能丢失。

### 不能阻止旧 owner 继续运行

客户端可能经历：

- 长时间 Stop-The-World GC。
- 操作系统或虚拟机暂停。
- 网络分区。
- Redis 请求超时，但业务线程仍继续执行。
- 租约已经过期，而线程恢复后仍继续写外部资源。

`WAIT` 只处理 Redis 写入复制，不会主动终止旧 owner，也不能让外部数据库判断某个 owner 是否已经过期。

### 不能消除响应不确定性

`SET`、`WAIT` 或失败后的清理发生超时时，客户端可能无法判断服务端具体执行到了哪一步。例如：

- `SET` 已成功，但响应丢失。
- `WAIT` 已达到要求，但响应丢失。
- 同步不足后的安全删除已执行，但响应丢失。
- 执行过程中刚好发生主从切换。

因此，重试不能简单地被视为同一次操作，仍然必须依靠唯一 token 和带 token 比较的安全删除。

### 不能保证 Redisson 或其他客户端的拓扑判断永远正确

客户端需要确定应该等待多少个副本，并在副本离线、恢复或主从切换后刷新这个数量。该逻辑受客户端版本、拓扑刷新及时性和部署方式影响。

历史上 Redisson 曾出现过可用副本数量刷新相关的问题，这说明 `WAIT` 本身语义明确，但围绕它的拓扑管理仍可能产生实现缺陷。

## 如果未来支持 WAIT

建议把它设计成显式、可选策略，不改变默认的轻量模式：

```text
SET key token NX PX leaseMillis
  ├── 未取得：返回 false
  └── 已取得：WAIT requiredReplicas timeoutMillis
       ├── 确认数达标：返回 true
       └── 确认数不足：compare-token-and-delete，获取失败
```

建议配置至少包含：

```text
enabled=false
requiredReplicas=1
timeoutMillis=<显式配置>
```

实现时需要遵循以下规则：

1. `SET` 和 `WAIT` 必须使用同一条物理连接。
2. 只有首次成功获取需要复制确认；获取失败不执行 `WAIT`。
3. 必须检查 `WAIT` 返回的实际副本数量，不能只判断命令是否正常返回。
4. 确认不足时使用 token 比较 Lua 删除，禁止直接 `DEL`。
5. 清理失败或响应不确定时，不得向调用方报告获取成功。
6. `WAIT` 超时不能超过本次 `tryLock` 剩余等待时间。
7. 需要明确 required replicas 是固定配置还是根据拓扑动态计算；动态计算必须正确处理副本离线和恢复。
8. 需要分别测试 standalone replication、Sentinel 和 Redis Cluster 的真实故障切换。
9. API 和文档必须继续声明：启用后仍不保证严格单 owner。

## 与业务正确性的关系

可以把保障分成三层：

| 层次 | 解决的问题 | 仍然存在的问题 |
| --- | --- | --- |
| `SET NX PX + token` | 正常情况下的互斥、崩溃后的 TTL 回收、安全解锁 | 切主丢锁、旧 owner |
| 加上 `WAIT` | 降低锁尚未复制便切主造成的丢锁概率 | 未确认副本晋升、连续故障、暂停客户端、非强一致 |
| 加上业务 fencing/CAS/唯一约束 | 拒绝旧 owner 或重复写入 | 需要受保护资源配合实现 |

如果业务不能接受两个执行者在故障窗口内同时运行，仅靠普通 Redis 锁或 `WAIT` 都不够。应让受保护资源验证单调递增的 fencing token，或使用数据库版本号 CAS、唯一约束和幂等键拒绝过期/重复操作。

## 当前决策

- 当前版本保持 `SET NX PX` 首次获取，不增加 `WAIT`。
- 不为 `WAIT` 引入额外连接绑定、拓扑追踪和失败清理复杂度。
- README 明确披露异步复制和主从切换产生双 owner 的风险。
- 将 `WAIT` 保留为未来可选增强项，而不是默认安全承诺。
- 无论未来是否支持 `WAIT`，关键写入都继续要求业务层并发保护。

## 参考资料

- [Redisson：Locks and synchronizers](https://redisson.pro/docs/data-and-services/locks-and-synchronizers/)
- [Redisson：Configuration](https://redisson.pro/docs/configuration/)
- [Redis：WAIT command](https://redis.io/docs/latest/commands/wait/)
- [Redis：Replication](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)
- [Redisson issue #6518：可用副本数量更新问题](https://github.com/redisson/redisson/issues/6518)
- [Redisson issue #4706：切主后 None of slaves were synced](https://github.com/redisson/redisson/issues/4706)
- [Redisson issue #5972：Cluster 拓扑刷新与 MOVED](https://github.com/redisson/redisson/issues/5972)
