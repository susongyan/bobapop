# 真实 Redis 集成测试

普通构建不会依赖本机 Redis：

```bash
mvn clean test
```

## Standalone

```bash
docker compose up -d
mvn -Dmaven.repo.local=/tmp/boba-grip-m2 \
    -Dredis.integration=true \
    -pl boba-grip-jedis-modern -am test
```

## Redis Cluster

项目提供 6 节点拓扑（3 master + 3 replica），默认使用宿主机端口 7100～7105。Docker Desktop
或其他普通 Docker 环境的完整命令如下：

```bash
export REDIS_CLUSTER_HOST_IP=192.168.65.254

docker compose -p redis-lock-cluster-it -f docker-compose.cluster.yml up -d
docker compose -p redis-lock-cluster-it -f docker-compose.cluster.yml exec -T \
    redis-cluster-7000 redis-cli --cluster create \
    "$REDIS_CLUSTER_HOST_IP:7100" "$REDIS_CLUSTER_HOST_IP:7101" \
    "$REDIS_CLUSTER_HOST_IP:7102" "$REDIS_CLUSTER_HOST_IP:7103" \
    "$REDIS_CLUSTER_HOST_IP:7104" "$REDIS_CLUSTER_HOST_IP:7105" \
    --cluster-replicas 1 --cluster-yes
```

运行 Jedis 主从切换测试：

```bash
mvn -Dmaven.repo.local=/tmp/boba-grip-m2 \
    -Dredis.cluster.integration=true \
    -Dredis.cluster.nodes=127.0.0.1:7100,127.0.0.1:7101 \
    -Dredis.cluster.connect.host=127.0.0.1 \
    -pl boba-grip-jedis-modern -am test
```

运行 Lettuce Cluster 测试：

```bash
mvn -Dmaven.repo.local=/tmp/boba-grip-m2 \
    -Dredis.cluster.integration=true \
    -Dredis.cluster.nodes=127.0.0.1:7100,127.0.0.1:7101 \
    -Dredis.cluster.connect.host=127.0.0.1 \
    -pl boba-grip-lettuce -am test
```

测试会等待锁复制到 replica，执行真实 `CLUSTER FAILOVER`，再验证 replica 晋升为 master 后，
同一个锁对象仍能使用原 token 重入续租。测试默认跳过，避免普通构建改变外部 Redis 拓扑。

测试完成后清理：

```bash
docker compose -p redis-lock-cluster-it -f docker-compose.cluster.yml down -v
```

Redis Cluster 的适配和地址配置见 [Redis Cluster 使用说明](redis-cluster.md)。macOS + Colima 的安装、Docker socket、DNS/代理排障和 Cluster 命令见
[macOS + Colima 测试环境](macos-colima.md)。
