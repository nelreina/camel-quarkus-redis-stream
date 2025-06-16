package tech.nelreina.camel.quarkus.redis.stream.component;

import java.util.Map;

import org.apache.camel.Endpoint;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.quarkus.logging.Log;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@Component("redis-stream")
public class RedisStreamComponent extends DefaultComponent {

    @ConfigProperty(name = "quarkus.redis.hosts", defaultValue = "redis://localhost:6379")
    String redisHosts;

    @ConfigProperty(name = "quarkus.redis.password", defaultValue = "")
    String redisPassword;

    @ConfigProperty(name = "camel.component.redis-stream.consumer-group-prefix", defaultValue = "camel")
    String consumerGroupPrefix;

    @ConfigProperty(name = "camel.component.redis-stream.auto-create-groups", defaultValue = "true")
    boolean autoCreateGroups;

    @ConfigProperty(name = "camel.component.redis-stream.default-block-timeout", defaultValue = "5000")
    int defaultBlockTimeout;

    @ConfigProperty(name = "camel.component.redis-stream.max-messages", defaultValue = "10")
    int maxMessages;

    @ConfigProperty(name = "camel.component.redis-stream.auto-ack", defaultValue = "true")
    boolean autoAck;

    private StatefulRedisConnection<String, String> connection;

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        RedisStreamConfiguration configuration = new RedisStreamConfiguration();
        configuration.setStreamKeyName(remaining);
        
        // Set component defaults
        configuration.setConsumerGroupPrefix(consumerGroupPrefix);
        configuration.setAutoCreateGroups(autoCreateGroups);
        configuration.setBlockTimeout(defaultBlockTimeout);
        configuration.setMaxMessages(maxMessages);
        configuration.setAutoAck(autoAck);
        
        // Configure from URI parameters
        setProperties(configuration, parameters);
        
        // Validate required parameters
        validateConfiguration(configuration);
        
        RedisStreamEndpoint endpoint = new RedisStreamEndpoint(uri, this, configuration);
        endpoint.setConnection(getConnection());
        
        return endpoint;
    }

    private void validateConfiguration(RedisStreamConfiguration configuration) {
        if (configuration.getStreamKeyName() == null || configuration.getStreamKeyName().trim().isEmpty()) {
            throw new IllegalArgumentException("Stream key name is required");
        }
        if (configuration.getGroup() == null || configuration.getGroup().trim().isEmpty()) {
            throw new IllegalArgumentException("Consumer group is required");
        }
        if (configuration.getEvents() == null || configuration.getEvents().trim().isEmpty()) {
            throw new IllegalArgumentException("Events parameter is required");
        }
    }

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

    private StatefulRedisConnection<String, String> createConnection() {
        try {
            Log.info("Creating Redis connection to: " + redisHosts);
            
            RedisURI redisURI = RedisURI.create(redisHosts);
            
            if (redisPassword != null && !redisPassword.trim().isEmpty()) {
                redisURI = RedisURI.builder(redisURI)
                    .withPassword(redisPassword.toCharArray())
                    .build();
            }
            
            RedisClient redisClient = RedisClient.create(redisURI);
            StatefulRedisConnection<String, String> conn = redisClient.connect();
            
            Log.info("Successfully connected to Redis");
            return conn;
            
        } catch (Exception e) {
            Log.error("Failed to create Redis connection", e);
            throw new RuntimeException("Failed to create Redis connection", e);
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        if (connection != null) {
            Log.info("Closing Redis connection");
            connection.close();
            connection = null;
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
}