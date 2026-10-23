package tech.nelreina.camel.quarkus.redis.stream.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.camel.Endpoint;
import org.apache.camel.Service;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.consumer.RedisStreamConsumer;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerNameSupplier;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerSlot;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerSlotListener;

@Component("redis-stream")
public class RedisStreamComponent extends DefaultComponent {

    static {
        Log.info("RedisStreamComponent class loaded successfully");
    }

    public RedisStreamComponent() {
        Log.info("RedisStreamComponent instance created");
    }

    private String redisHosts = "redis://localhost:6379";
    private String redisPassword = "";
    private String consumerGroupPrefix = "camel";
    private boolean autoCreateGroups = true;
    private boolean autoCreateStreams = true;
    private int defaultBlockTimeout = 1000;
    private int maxMessages = 10;
    private boolean autoAck = true;
    private int pollingInterval = 100;
    private String globalHeaderFilters = "";
    private String groupStartId = "$";
    private long autoClaimMinIdleMs = 60000;
    private long autoClaimEveryMs = 60000;
    private int drainMaxDeliveries = 5;
    private long stopTimeoutMs = 30000;

    private volatile RedisClient redisClient;
    private volatile StatefulRedisConnection<String, String> connection;

    private volatile ConsumerNameSupplier consumerNameSupplier;
    private volatile boolean consumerNameSupplierResolved;
    private final Set<RedisStreamConsumer> supplierNamedConsumers = ConcurrentHashMap.newKeySet();
    private final Set<Service> connectionUsers = ConcurrentHashMap.newKeySet();

    /** Stops every supplier-named consumer while the slot changes hands, restarts them under the new name. */
    private final ConsumerSlotListener restartOnSlotChange = new ConsumerSlotListener() {
        private final List<RedisStreamConsumer> stopped = new ArrayList<>();

        @Override
        public synchronized void beforeReclaim(String lostName) {
            stopped.clear();
            for (RedisStreamConsumer c : new ArrayList<>(supplierNamedConsumers)) {
                if (c.isStarted()) {
                    try {
                        c.stop();
                        stopped.add(c);
                    } catch (Exception e) {
                        Log.errorf(e, "Failed to stop consumer %s after losing slot %s", c.getConsumerName(), lostName);
                    }
                }
            }
        }

        @Override
        public synchronized void afterReclaim(String lostName, String newName) {
            if (newName == null) {
                stopped.clear();
                return; // the slot's fatal handler decides; never consume under a name this pod does not hold
            }
            for (RedisStreamConsumer c : stopped) {
                try {
                    c.start();
                } catch (Exception e) {
                    Log.errorf(e, "Failed to restart consumer for %s under %s", c.getEndpoint().getEndpointUri(), newName);
                }
            }
            stopped.clear();
        }
    };

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        Log.infof("Creating Redis Stream endpoint for URI: %s, remaining: %s, parameters: %s", uri, remaining, parameters);

        RedisStreamConfiguration configuration = new RedisStreamConfiguration();
        configuration.setStreamKeyName(remaining);

        // Set component defaults
        configuration.setConsumerGroupPrefix(consumerGroupPrefix);
        configuration.setAutoCreateGroups(autoCreateGroups);
        configuration.setAutoCreateStreams(autoCreateStreams);
        configuration.setBlockTimeout(defaultBlockTimeout);
        configuration.setMaxMessages(maxMessages);
        configuration.setAutoAck(autoAck);
        configuration.setPollingInterval(pollingInterval);
        configuration.setGroupStartId(groupStartId);
        configuration.setAutoClaimMinIdleMs(autoClaimMinIdleMs);
        configuration.setAutoClaimEveryMs(autoClaimEveryMs);
        configuration.setDrainMaxDeliveries(drainMaxDeliveries);
        configuration.setStopTimeoutMs(stopTimeoutMs);

        // Always set global header filters - they will be merged with route-level filters
        configuration.setGlobalHeaderFilters(globalHeaderFilters);

        // Configure from URI parameters
        setProperties(configuration, parameters);

        // Validate required parameters
        validateConfiguration(configuration);

        RedisStreamEndpoint endpoint = new RedisStreamEndpoint(uri, this, configuration);
        // Don't eagerly create connection - let it be lazy

        Log.infof("Successfully created Redis Stream endpoint for stream: %s", remaining);
        return endpoint;
    }

    private void validateConfiguration(RedisStreamConfiguration configuration) {
        if (configuration.getStreamKeyName() == null || configuration.getStreamKeyName().trim().isEmpty()) {
            throw new IllegalArgumentException("Stream key name is required");
        }
        // Group and events are only required for consumers, not producers
        // Validation will be done at consumer creation time
    }

    /** Shared connection used by every endpoint of this component. Never run blocking commands on it. */
    public StatefulRedisConnection<String, String> getConnection() {
        if (connection == null) {
            synchronized (this) {
                if (connection == null) {
                    connection = createConnection();
                }
            }
        }
        return connection;
    }

    /**
     * The Lettuce client behind the shared connection. Use it to open a dedicated connection
     * (e.g. for XREAD BLOCK): the shared sync connection must not block.
     */
    public RedisClient getRedisClient() {
        getConnection();
        return redisClient;
    }

    private StatefulRedisConnection<String, String> createConnection() {
        try {
            Log.info("Creating Redis connection to: " + redisHosts);

            RedisURI redisURI = RedisURI.create(redisHosts);

            if (redisPassword != null && !redisPassword.trim().isEmpty()) {
                redisURI = RedisURI.builder(redisURI)
                    .withPassword(redisPassword.toCharArray())
                    .build();
            }

            RedisClient client = RedisClient.create(redisURI);
            StatefulRedisConnection<String, String> conn = client.connect();
            this.redisClient = client;

            Log.info("Successfully connected to Redis");
            return conn;

        } catch (Exception e) {
            Log.error("Failed to create Redis connection", e);
            throw new RuntimeException("Failed to create Redis connection", e);
        }
    }

    /**
     * Component-level consumer name supplier: the one set with {@link #setConsumerNameSupplier}, else the
     * single {@link ConsumerNameSupplier} bean in the Camel registry (CDI in Quarkus), looked up once.
     * Returns null when there is none (consumers then fall back to the generated name).
     */
    public ConsumerNameSupplier getConsumerNameSupplier() {
        if (!consumerNameSupplierResolved) {
            synchronized (this) {
                if (!consumerNameSupplierResolved) {
                    if (consumerNameSupplier == null && getCamelContext() != null) {
                        try {
                            consumerNameSupplier = getCamelContext().getRegistry().findSingleByType(ConsumerNameSupplier.class);
                        } catch (Exception e) {
                            Log.warnf("ConsumerNameSupplier lookup failed: %s", e.getMessage());
                        }
                    }
                    if (consumerNameSupplier instanceof ConsumerSlot) {
                        ((ConsumerSlot) consumerNameSupplier).addListener(restartOnSlotChange);
                    }
                    if (consumerNameSupplier != null) {
                        Log.infof("redis-stream uses ConsumerNameSupplier %s", consumerNameSupplier.getClass().getName());
                    }
                    consumerNameSupplierResolved = true;
                }
            }
        }
        return consumerNameSupplier;
    }

    public synchronized void setConsumerNameSupplier(ConsumerNameSupplier supplier) {
        if (this.consumerNameSupplier instanceof ConsumerSlot && this.consumerNameSupplier != supplier) {
            ((ConsumerSlot) this.consumerNameSupplier).removeListener(restartOnSlotChange);
        }
        this.consumerNameSupplier = supplier;
        this.consumerNameSupplierResolved = false;
    }

    /** Internal: consumers named by the supplier register here so a slot change restarts them. */
    public void registerSupplierNamedConsumer(RedisStreamConsumer consumer) {
        supplierNamedConsumers.add(consumer);
    }

    /** Internal counterpart of {@link #registerSupplierNamedConsumer}. */
    public void unregisterSupplierNamedConsumer(RedisStreamConsumer consumer) {
        supplierNamedConsumers.remove(consumer);
    }

    /**
     * Services that use this component's connection (or a connection of its client) beyond the routes, e.g. the
     * {@code TenantRouteManager}. {@link #doStop} stops them first, while the connection is still open.
     */
    public void registerConnectionUser(Service service) {
        connectionUsers.add(service);
    }

    /** Counterpart of {@link #registerConnectionUser}; called by the service when it stops. */
    public void unregisterConnectionUser(Service service) {
        connectionUsers.remove(service);
    }

    @Override
    protected void doStop() throws Exception {
        // Camel stops the components before the services added with addService: stop the connection users
        // first so their last writes (heartbeat DEL) reach Redis and their own connections close cleanly.
        for (Service user : new ArrayList<>(connectionUsers)) {
            try {
                user.stop();
            } catch (Exception e) {
                Log.warnf(e, "Stopping %s before closing the Redis connection failed", user);
            }
        }
        connectionUsers.clear();
        super.doStop();
        ConsumerNameSupplier supplier = consumerNameSupplier;
        if (supplier != null) {
            try {
                // Components stop after the routes: release the slot lease once no consumer uses the name.
                supplier.release();
            } catch (Exception e) {
                Log.warnf("Releasing consumer name supplier failed: %s", e.getMessage());
            }
        }
        if (connection != null) {
            Log.info("Closing Redis connection");
            connection.close();
            connection = null;
        }
        if (redisClient != null) {
            redisClient.shutdown();
            redisClient = null;
        }
    }

    public String getConsumerGroupPrefix() {
        return consumerGroupPrefix;
    }

    public void setConsumerGroupPrefix(String consumerGroupPrefix) {
        this.consumerGroupPrefix = consumerGroupPrefix;
    }

    public boolean isAutoCreateGroups() {
        return autoCreateGroups;
    }

    public void setAutoCreateGroups(boolean autoCreateGroups) {
        this.autoCreateGroups = autoCreateGroups;
    }

    public boolean isAutoCreateStreams() {
        return autoCreateStreams;
    }

    public void setAutoCreateStreams(boolean autoCreateStreams) {
        this.autoCreateStreams = autoCreateStreams;
    }

    public int getDefaultBlockTimeout() {
        return defaultBlockTimeout;
    }

    public void setDefaultBlockTimeout(int defaultBlockTimeout) {
        this.defaultBlockTimeout = defaultBlockTimeout;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public boolean isAutoAck() {
        return autoAck;
    }

    public void setAutoAck(boolean autoAck) {
        this.autoAck = autoAck;
    }

    public String getRedisHosts() {
        return redisHosts;
    }

    public void setRedisHosts(String redisHosts) {
        this.redisHosts = redisHosts;
    }

    public String getRedisPassword() {
        return redisPassword;
    }

    public void setRedisPassword(String redisPassword) {
        this.redisPassword = redisPassword;
    }

    public int getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(int pollingInterval) {
        this.pollingInterval = pollingInterval;
    }

    public String getGlobalHeaderFilters() {
        return globalHeaderFilters;
    }

    public void setGlobalHeaderFilters(String globalHeaderFilters) {
        this.globalHeaderFilters = globalHeaderFilters;
    }

    public String getGroupStartId() {
        return groupStartId;
    }

    public void setGroupStartId(String groupStartId) {
        this.groupStartId = groupStartId;
    }

    public long getAutoClaimMinIdleMs() {
        return autoClaimMinIdleMs;
    }

    public void setAutoClaimMinIdleMs(long autoClaimMinIdleMs) {
        this.autoClaimMinIdleMs = autoClaimMinIdleMs;
    }

    public long getAutoClaimEveryMs() {
        return autoClaimEveryMs;
    }

    public void setAutoClaimEveryMs(long autoClaimEveryMs) {
        this.autoClaimEveryMs = autoClaimEveryMs;
    }

    public int getDrainMaxDeliveries() {
        return drainMaxDeliveries;
    }

    public void setDrainMaxDeliveries(int drainMaxDeliveries) {
        this.drainMaxDeliveries = drainMaxDeliveries;
    }

    public long getStopTimeoutMs() {
        return stopTimeoutMs;
    }

    public void setStopTimeoutMs(long stopTimeoutMs) {
        this.stopTimeoutMs = stopTimeoutMs;
    }
}
