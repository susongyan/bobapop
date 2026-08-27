package io.github.susongyan.bobagrip.jedis.modern;

import io.github.susongyan.bobagrip.core.RedisLock;
import io.github.susongyan.bobagrip.core.RedisLockClient;
import org.junit.Assume;
import org.junit.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.HostAndPortMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

/**
 * Opt-in Redis Cluster failover test. It performs a real manual failover and is intentionally
 * excluded from the normal build. Run against a disposable cluster with:
 * -Dredis.cluster.integration=true -Dredis.cluster.nodes=127.0.0.1:7100,127.0.0.1:7101
 * -Dredis.cluster.connect.host=127.0.0.1
 */
public class RedisClusterFailoverIntegrationTest {

    @Test
    public void reentrantLockCanRenewAfterReplicaPromotion() throws Exception {
        Assume.assumeTrue("Set -Dredis.cluster.integration=true to run",
                Boolean.getBoolean("redis.cluster.integration"));

        Set<HostAndPort> seeds = parseSeeds();
        try (JedisCluster cluster = new JedisCluster(
                seeds, null, null, new TestHostAndPortMapper())) {
            String key = "order:{failover-" + UUID.randomUUID() + "}";
            RedisLock lock = new RedisLockClient(
                    new JedisLockBackend(cluster),
                    "redis-lock-cluster-it:").createLock(key);

            assertTrue(lock.tryLockNow(30, TimeUnit.SECONDS));
            try {
                ClusterTopology topology = ClusterTopology.discover(seeds, key);
                Assume.assumeNotNull("A replica for the selected slot is required", topology.replica);

                waitForReplication(topology.replica.address, lock.getKey());
                triggerFailover(topology.replica.address);
                waitUntilPromoted(topology.replica.address, topology.replica.id);

                // The same token must still be recognized on the promoted master.
                assertTrue(lock.tryLockNow(30, TimeUnit.SECONDS));
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    private static void triggerFailover(HostAndPort replica) {
        try (Jedis jedis = new Jedis(replica)) {
            jedis.clusterFailover();
        }
    }

    private static void waitForReplication(HostAndPort replica, String key)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Jedis jedis = new Jedis(replica)) {
                jedis.readonly();
                if (jedis.exists(key)) {
                    return;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("Lock key was not replicated within 30 seconds: " + key);
    }

    private static void waitUntilPromoted(HostAndPort replica, String id)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (Jedis jedis = new Jedis(replica)) {
                for (ClusterNode node : parseNodes(jedis.clusterNodes())) {
                    if (id.equals(node.id) && node.flags.contains("master")) {
                        return;
                    }
                }
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Replica was not promoted within 30 seconds: " + replica);
    }

    private static Set<HostAndPort> parseSeeds() {
        String value = System.getProperty("redis.cluster.nodes", "127.0.0.1:7000");
        Set<HostAndPort> result = new HashSet<HostAndPort>();
        for (String seed : value.split(",")) {
            result.add(HostAndPort.from(seed.trim()));
        }
        return result;
    }

    private static final class ClusterTopology {
        private final ClusterNode replica;

        private ClusterTopology(ClusterNode replica) {
            this.replica = replica;
        }

        private static ClusterTopology discover(Set<HostAndPort> seeds, String key) {
            HostAndPort seed = seeds.iterator().next();
            try (Jedis jedis = new Jedis(seed)) {
                long slot = jedis.clusterKeySlot(key);
                Map<String, ClusterNode> nodes = index(parseNodes(jedis.clusterNodes()));
                ClusterNode master = null;
                for (ClusterNode node : nodes.values()) {
                    if (node.flags.contains("master") && node.owns(slot)) {
                        master = node;
                        break;
                    }
                }
                if (master == null) {
                    return new ClusterTopology(null);
                }
                for (ClusterNode node : nodes.values()) {
                    if (node.flags.contains("slave") && master.id.equals(node.masterId)) {
                        return new ClusterTopology(node);
                    }
                }
                return new ClusterTopology(null);
            }
        }

        private static Map<String, ClusterNode> index(Iterable<ClusterNode> nodes) {
            Map<String, ClusterNode> result = new java.util.HashMap<String, ClusterNode>();
            for (ClusterNode node : nodes) {
                result.put(node.id, node);
            }
            return result;
        }
    }

    private static final class ClusterNode {
        private final String id;
        private final HostAndPort address;
        private final String flags;
        private final String masterId;
        private final java.util.List<long[]> slotRanges;

        private ClusterNode(String id, HostAndPort address, String flags,
                            String masterId, java.util.List<long[]> slotRanges) {
            this.id = id;
            this.address = address;
            this.flags = flags;
            this.masterId = masterId;
            this.slotRanges = slotRanges;
        }

        private boolean owns(long slot) {
            for (long[] range : slotRanges) {
                if (slot >= range[0] && slot <= range[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    private static Iterable<ClusterNode> parseNodes(String value) {
        java.util.List<ClusterNode> result = new java.util.ArrayList<ClusterNode>();
        for (String line : value.split("\\r?\\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] fields = line.trim().split("\\s+");
            String address = fields[1].split("@", 2)[0];
            java.util.List<long[]> slotRanges = new java.util.ArrayList<long[]>();
            for (int i = 8; i < fields.length; i++) {
                String slot = fields[i];
                if (slot.indexOf('-') > 0) {
                    String[] range = slot.split("-", 2);
                    slotRanges.add(new long[]{Long.parseLong(range[0]), Long.parseLong(range[1])});
                    continue;
                }
                if (slot.matches("\\d+")) {
                    long slotValue = Long.parseLong(slot);
                    slotRanges.add(new long[]{slotValue, slotValue});
                }
            }
            result.add(new ClusterNode(fields[0], mapAddress(HostAndPort.from(address)), fields[2],
                    fields[3], slotRanges));
        }
        return result;
    }

    private static HostAndPort mapAddress(HostAndPort address) {
        String configuredHost = System.getProperty("redis.cluster.connect.host");
        if (configuredHost != null && !configuredHost.trim().isEmpty()) {
            return new HostAndPort(configuredHost.trim(), address.getPort());
        }
        if ("host.docker.internal".equals(address.getHost())) {
            return new HostAndPort("127.0.0.1", address.getPort());
        }
        return address;
    }

    private static final class TestHostAndPortMapper implements HostAndPortMapper {
        public HostAndPort getHostAndPort(HostAndPort address) {
            return mapAddress(address);
        }
    }
}
