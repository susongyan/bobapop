# 快速开始与客户端接入

## Spring Data Redis

`RedisLockClient` 和 backend 可以是单例 Bean，`RedisLock` 必须按请求或调用链创建：

```java
@Configuration
public class LockConfiguration {
    @Bean
    public RedisLockClient redisLockClient(StringRedisTemplate template) {
        RedisTemplateLockBackend backend = new RedisTemplateLockBackend(template);
        return new RedisLockClient(backend, "order-service:lock:");
    }
}
```

Spring Boot 1.5、2.x、3.x 的配置形式相同，可参考 `demo/boot1`、`demo/boot2` 和 `demo/boot3`。

## 业务代码使用

`RedisLockClient` 通常作为 Spring 单例 Bean 注入；`RedisLock` 每次业务调用创建：

以下片段省略常规 `import`。

```java
@Service
public class OrderService {
    private final RedisLockClient redisLockClient;

    public OrderService(RedisLockClient redisLockClient) {
        this.redisLockClient = redisLockClient;
    }

    public void update(String orderId) throws InterruptedException {
        RedisLock lock = redisLockClient.createLock("order:{" + orderId + "}");
        if (!lock.tryLock(500, 30_000, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("order is busy");
        }
        try {
            updateOrder(orderId);
        } finally {
            lock.unlock();
        }
    }
}
```

需要重入时沿调用链传递同一个 `RedisLock`；不要在内层重新调用 `createLock(sameKey)`，因为新对象会生成新的 token，
不会被识别为重入。立即只尝试一次使用 `lock.tryLockNow(30, TimeUnit.SECONDS)`。

业务耗时无法准确预估时，可以显式开启 watchdog：

```java
RedisLock lock = redisLockClient.createLock("order:{" + orderId + "}");
if (!lock.tryLockWithWatchdog(500, TimeUnit.MILLISECONDS)) {
    throw new IllegalStateException("order is busy");
}
try {
    updateOrder(orderId);
} finally {
    lock.unlock();
}
```

watchdog 只会续租当前 token，不会在续租失败后重新抢锁；续租异常会进入有限重试，确认丢锁后可通过
`lock.getState()`、`lock.isLeaseAlive()` 和 `LockLostException` 感知。watchdog 不能替代 `unlock()`，
完整设计和 Redis 故障边界见 [Watchdog 使用场景与设计](watchdog.md)。

## Jedis

Jedis 2.x/3.x 使用 `boba-grip-jedis-legacy`，多线程应用优先传入 `JedisPool`：

```java
JedisPool pool = new JedisPool("127.0.0.1", 6379);
RedisLockClient client = new RedisLockClient(
        new JedisLegacyLockBackend(pool), "order-service:lock:");
```

Jedis 4.x+ 使用 `boba-grip-jedis-modern`，可传入 `JedisPooled`、`JedisCluster`、`UnifiedJedis` 或 `JedisPool`：

```java
UnifiedJedis jedis = new JedisPooled("127.0.0.1", 6379);
RedisLockClient client = new RedisLockClient(
        new JedisLockBackend(jedis), "order-service:lock:");
```

Jedis 2.x/3.x Cluster 使用 `JedisLegacyClusterLockBackend`；Jedis 4.x+ 的 `JedisCluster` 可直接传入 `JedisLockBackend`。

## Lettuce

适配器使用同步命令，连接生命周期由调用方负责：

```java
RedisClient redisClient = RedisClient.create("redis://127.0.0.1:6379");
StatefulRedisConnection<String, String> connection = redisClient.connect();
RedisLockClient client = new RedisLockClient(
        new LettuceLockBackend(connection), "order-service:lock:");
```

Lettuce Cluster 使用 `StatefulRedisClusterConnection` 和 `LettuceClusterLockBackend`。不要在 Netty 事件循环线程中执行可能等待的同步锁调用。

客户端连接、连接池、认证、TLS、超时和关闭均由业务项目负责，本库不会接管客户端生命周期。

## Spring Boot 下的 Jedis/Lettuce 配置

Spring Boot 应用中建议把客户端、连接池/连接和 `RedisLockClient` 注册为单例 Bean；`RedisLock`
必须每次请求或每条调用链创建。下面使用自定义 `app.redis.*` 配置，兼容 Spring Boot 1.x、2.x 和 3.x。

以下 Java 片段省略 `package` 和常规 `import`，实际使用时分别引入对应的 Spring、Jedis/Lettuce 和本项目适配器类。

```yaml
app:
  redis:
    host: 127.0.0.1
    port: 6379
    uri: redis://127.0.0.1:6379
```

依赖版本由业务项目或 Spring Boot BOM 管理：Jedis 2.x/3.x 使用 legacy 模块，Jedis 4.x+ 使用 modern
模块，Lettuce 5.x+ 使用 Lettuce 模块。

### Jedis 2.x/3.x：JedisPool

适合历史 Spring Boot 1.x/2.x 项目：

```java
@Configuration
public class JedisLegacyLockConfiguration {

    @Bean(destroyMethod = "close")
    public JedisPool jedisPool(
            @Value("${app.redis.host:127.0.0.1}") String host,
            @Value("${app.redis.port:6379}") int port) {
        return new JedisPool(host, port);
    }

    @Bean
    public RedisLockClient redisLockClient(JedisPool jedisPool) {
        return new RedisLockClient(
                new JedisLegacyLockBackend(jedisPool),
                "order-service:lock:");
    }
}
```

`JedisPool` 由 Spring 关闭；backend 不负责关闭连接池。不要把单个 `Jedis` 连接注册为全局共享 Bean。

### Jedis 4.x+：JedisPooled

```java
@Configuration
public class JedisModernLockConfiguration {

    @Bean(destroyMethod = "close")
    public JedisPooled jedisPooled(
            @Value("${app.redis.host:127.0.0.1}") String host,
            @Value("${app.redis.port:6379}") int port) {
        return new JedisPooled(host, port);
    }

    @Bean
    public RedisLockClient redisLockClient(JedisPooled jedis) {
        return new RedisLockClient(
                new JedisLockBackend(jedis),
                "order-service:lock:");
    }
}
```

如果应用已有 `JedisPool` 或 `JedisCluster`，也可以直接传给 modern backend；Cluster 会按单个锁 key 的 slot 路由。

### Lettuce：StatefulRedisConnection

```java
@Configuration
public class LettuceLockConfiguration {

    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(
            @Value("${app.redis.uri:redis://127.0.0.1:6379}") String uri) {
        return RedisClient.create(uri);
    }

    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisConnection(
            RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public RedisLockClient redisLockClient(
            StatefulRedisConnection<String, String> connection) {
        return new RedisLockClient(
                new LettuceLockBackend(connection),
                "order-service:lock:");
    }
}
```

Lettuce 的 `StatefulRedisConnection` 设计为长生命周期、线程安全的连接，可以作为 Spring 单例被多个业务线程共享；
本项目的锁操作只使用普通 `SET`、`EVAL` 等非阻塞命令，适合复用同一个连接。不要在 Netty 事件循环线程中执行
可能等待的同步锁调用。

如果同一连接还要执行 `BLPOP` 等阻塞命令、`MULTI/EXEC`/`WATCH` 事务、Pub/Sub 或切换 database，应为这些用途
创建独立连接，避免连接状态互相影响。普通命令通常不需要连接池。

连接和 `RedisClient` 的生命周期由 Spring 管理。

Redis Cluster 相关的 seed、hash tag 和故障切换测试见 [Redis Cluster 使用说明](redis-cluster.md)。
