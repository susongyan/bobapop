package io.github.susongyan.bobagrip.lettuce;

import io.github.susongyan.bobagrip.core.RedisLock;
import io.github.susongyan.bobagrip.core.RedisLockClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.internal.HostAndPort;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DnsResolver;
import io.lettuce.core.resource.DefaultClientResources;
import io.lettuce.core.resource.MappingSocketAddressResolver;
import org.junit.Assume;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/** Opt-in Redis Cluster adapter test. Set redis.cluster.integration=true to enable it. */
public class LettuceClusterLockIntegrationTest {

    @Test
    public void lockReentersAndReleasesAgainstRealCluster() throws Exception {
        Assume.assumeTrue("Set -Dredis.cluster.integration=true to run",
                Boolean.getBoolean("redis.cluster.integration"));

        List<RedisURI> seeds = new ArrayList<RedisURI>();
        for (String value : System.getProperty("redis.cluster.nodes", "127.0.0.1:7000")
                .split(",")) {
            seeds.add(RedisURI.create("redis://" + value.trim()));
        }

        ClientResources resources = DefaultClientResources.builder()
                .socketAddressResolver(MappingSocketAddressResolver.create(
                        DnsResolver.jvmDefault(), LettuceClusterLockIntegrationTest::mapAddress))
                .build();
        RedisClusterClient client = RedisClusterClient.create(resources, seeds);
        StatefulRedisClusterConnection<String, String> connection = client.connect();
        try {
            RedisLock lock = new RedisLockClient(
                    new LettuceClusterLockBackend(connection),
                    "boba-grip-lettuce-cluster-it:" + UUID.randomUUID() + ":")
                    .createLock("order:{1}");
            assertTrue(lock.tryLock(1, 10, TimeUnit.SECONDS));
            try {
                assertTrue(lock.tryLockNow(10, TimeUnit.SECONDS));
                assertTrue(lock.unlock());
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } finally {
            connection.close();
            client.shutdown();
            resources.shutdown();
        }
    }

    private static HostAndPort mapAddress(HostAndPort address) {
        String configuredHost = System.getProperty("redis.cluster.connect.host");
        if (configuredHost != null && !configuredHost.trim().isEmpty()) {
            return HostAndPort.of(configuredHost.trim(), address.getPort());
        }
        if ("host.docker.internal".equals(address.getHostText())) {
            return HostAndPort.of("127.0.0.1", address.getPort());
        }
        return address;
    }
}
