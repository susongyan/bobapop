# Redis Cluster 使用说明

## 适配方式

锁脚本只操作一个 Redis key，Jedis 和 Lettuce Cluster 客户端会按该 key 的 slot 路由请求。
因此同一次脚本调用不会跨 slot；需要让多个业务 key 共同参与同一业务操作时，可使用 Redis hash tag，
例如 `order:{123}`。

适配器对应关系：

| 客户端 | 适配器 |
| --- | --- |
| Jedis 2.x/3.x `JedisCluster` | `JedisLegacyClusterLockBackend` |
| Jedis 4.x+ `JedisCluster`/`RedisClusterClient` | `JedisLockBackend` |
| Lettuce `StatefulRedisClusterConnection` | `LettuceClusterLockBackend` |

客户端连接、拓扑刷新、认证、TLS、超时和关闭由业务项目负责。

## 地址配置

测试拓扑通过 `cluster-announce-ip` 和 `cluster-announce-port` 对外公布节点地址。容器内地址、
宿主机端口和 Java 客户端可连接地址可能不同：

- `REDIS_CLUSTER_HOST_IP`：Cluster 节点向其他节点和客户端公布的地址。
- `redis.cluster.nodes`：Java 测试使用的初始 seed，例如 `127.0.0.1:7100,127.0.0.1:7101`。
- `redis.cluster.connect.host`：将 Cluster 返回的容器地址映射为测试客户端可访问的地址。

macOS + Colima 的实际配置和端口转发见[Colima 测试环境](macos-colima.md)。

## 故障切换测试

Jedis failover 测试会：

1. 获取锁并等待锁数据复制到 replica。
2. 对 replica 执行 `CLUSTER FAILOVER`。
3. 等待 replica 晋升为 master。
4. 使用同一个 `RedisLock` 对象和 token 验证重入续租。

这验证的是客户端拓扑刷新和 token 语义，不代表 Redis 主从切换期间严格只有一个 owner。异步复制窗口、
网络分区或主节点在复制前故障，仍可能导致重复 owner；相关边界和 `WAIT` 分析见
[主从切换与 WAIT 复制确认](replica-failover-and-wait.md)。
