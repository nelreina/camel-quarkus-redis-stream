package tech.nelreina.camel.quarkus.redis.stream.component;

import java.util.Map;

import org.apache.camel.Endpoint;
import org.apache.camel.spi.annotations.Component;
import org.apache.camel.support.DefaultComponent;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.quarkus.logging.Log;

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

    private StatefulRedisConnection<String, String> connection;

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
}
