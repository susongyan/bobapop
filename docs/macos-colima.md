# macOS + Colima 测试环境

Colima 在 macOS 上运行 Linux VM，并在 VM 内提供 Docker daemon。Homebrew 只安装工具，首次启动还需要
Colima 的 Ubuntu VM 镜像。

## 安装与启动

```bash
brew install colima docker docker-compose jq
brew link docker
```

Intel Mac 使用 amd64 镜像：

```text
https://github.com/abiosoft/colima-core/releases/download/v0.10.4/ubuntu-24.04-minimal-cloudimg-amd64-docker.raw.gz
```

Apple Silicon 使用 arm64 镜像，并将 `--arch x86_64` 改为 `--arch aarch64`：

```text
https://github.com/abiosoft/colima-core/releases/download/v0.10.4/ubuntu-24.04-minimal-cloudimg-arm64-docker.raw.gz
```

启动示例：

```bash
colima start \
    --runtime docker \
    --arch x86_64 \
    --network-address \
    --cpus 4 \
    --memory 6 \
    --disk 30 \
    --disk-image /private/tmp/ubuntu-24.04-minimal-cloudimg-amd64-docker.raw.gz

docker context use colima
docker info
```

普通终端可后台启动；如果执行环境会回收后台进程，使用 `colima start --foreground` 并保持该进程运行。
Intel Mac 使用 VZ 失败时，可增加 `--vm-type qemu`。

## 启动 Cluster

```bash
export REDIS_CLUSTER_HOST_IP="$(colima ls -j | jq -r '.address')"
export DOCKER_HOST="unix://$HOME/.colima/default/docker.sock"

# Docker Desktop 配置可能包含 credsStore=desktop，Colima 没有该 credential helper。
mkdir -p /tmp/redis-lock-colima-docker-config
export DOCKER_CONFIG=/tmp/redis-lock-colima-docker-config
```

下面命令默认在项目根目录执行。首次启动或需要重建 Cluster 时，先清理本测试项目的容器和数据卷；只重启已有环境时不要执行这一步：

```bash
docker compose -p redis-lock-colima -f docker-compose.cluster.yml down -v

docker compose -p redis-lock-colima -f docker-compose.cluster.yml up -d
docker compose -p redis-lock-colima -f docker-compose.cluster.yml exec -T \
    redis-cluster-7000 redis-cli --cluster create \
    "$REDIS_CLUSTER_HOST_IP:7100" "$REDIS_CLUSTER_HOST_IP:7101" \
    "$REDIS_CLUSTER_HOST_IP:7102" "$REDIS_CLUSTER_HOST_IP:7103" \
    "$REDIS_CLUSTER_HOST_IP:7104" "$REDIS_CLUSTER_HOST_IP:7105" \
    --cluster-replicas 1 --cluster-yes
```

如果 `docker compose` 拉取镜像时提示 credential helper 或认证错误，确认上面的
`DOCKER_CONFIG` 指向一个不包含 Docker Desktop `credsStore` 的目录。

Colima 会转发宿主机端口 7100～7105，Java 测试仍连接 `127.0.0.1`。测试命令和清理方式见
[真实 Redis 集成测试](integration-testing.md)。

## DNS 与代理排障

如果拉取 `redis:7.4-alpine` 时出现 DNS 或连接超时，先检查：

```bash
colima ssh -- getent hosts registry-1.docker.io
```

某些精简 Ubuntu 镜像可能缺少 resolver 文件，可以补充 DNS（地址按网络环境调整）：

```bash
colima ssh -- sudo sh -c \
  'mkdir -p /run/systemd/resolve && printf "nameserver 8.8.8.8\\nnameserver 1.1.1.1\\n" > /run/systemd/resolve/stub-resolv.conf'
```

如果本机通过代理访问外网，需要让 VM 内 Docker daemon 使用宿主机代理。以下 `7897` 是示例端口：

```bash
colima ssh -- sudo sh -c \
  'mkdir -p /etc/systemd/system/docker.service.d && printf "%s\\n" "[Service]" "Environment=HTTP_PROXY=http://192.168.5.2:7897" "Environment=HTTPS_PROXY=http://192.168.5.2:7897" > /etc/systemd/system/docker.service.d/http-proxy.conf && systemctl daemon-reload && systemctl restart docker'
```

验证 Cluster：

```bash
docker exec redis-lock-colima-redis-cluster-7000-1 \
    redis-cli -p 7000 cluster info
```

应看到 `cluster_state:ok`、`cluster_slots_ok:16384` 和 `cluster_known_nodes:6`。
