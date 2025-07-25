package tech.nelreina.camel.quarkus.redis.stream.consumer;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.support.ScheduledPollConsumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamConfiguration;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamEndpoint;
import tech.nelreina.camel.quarkus.redis.stream.exception.RedisStreamException;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;
import tech.nelreina.camel.quarkus.redis.stream.util.ConsumerNameGenerator;
import tech.nelreina.camel.quarkus.redis.stream.util.HeaderFilter;

public class RedisStreamConsumer extends ScheduledPollConsumer {

    private final RedisStreamEndpoint endpoint;
    private final RedisStreamConfiguration configuration;
    private RedisCommands<String, String> redisCommands;
    private String consumerName;
    private Set<String> allowedEvents;
    private HeaderFilter headerFilter;
    private ObjectMapper objectMapper;

    public RedisStreamConsumer(RedisStreamEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.configuration = endpoint.getConfiguration();
        this.objectMapper = new ObjectMapper();
    }

    public RedisStreamConsumer(RedisStreamEndpoint endpoint, Processor processor, ObjectMapper objectMapper) {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.configuration = endpoint.getConfiguration();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        
        StatefulRedisConnection<String, String> connection = endpoint.getConnection();
        if (connection == null) {
            throw new RedisStreamException("Redis connection is not available");
        }
        
        this.redisCommands = connection.sync();
        this.consumerName = generateConsumerName();
        this.allowedEvents = parseAllowedEvents();
        
        // Create header filter with merged global and route filters
        this.headerFilter = new HeaderFilter(
            configuration.getGlobalHeaderFilters(), 
            configuration.getHeaderFilters()
        );
        
        ensureConsumerGroupAndStream();
        
        Log.infof("Started Redis Stream consumer: group=%s, consumer=%s, stream=%s, events=%s, headerFilters=%s (global=%s, route=%s)", 
                configuration.getGroup(), consumerName, configuration.getStreamKeyName(), configuration.getEvents(), 
                HeaderFilter.mergeFilters(configuration.getGlobalHeaderFilters(), configuration.getHeaderFilters()),
                configuration.getGlobalHeaderFilters(), configuration.getHeaderFilters());
    }

    @Override
    protected int poll() throws Exception {
        try {
            List<StreamMessage<String, String>> messages = redisCommands.xreadgroup(
                Consumer.from(configuration.getGroup(), consumerName),
                XReadArgs.StreamOffset.from(configuration.getStreamKeyName(), configuration.getStartId())
            );

            int processedCount = 0;
            
            for (StreamMessage<String, String> message : messages) {
                try {
                    EventData eventData = mapToEventData(message);
                    
                    // Filter events
                    if (!allowedEvents.contains(eventData.getEvent())) {
                        Log.debugf("Skipping event: %s (not in allowed events)", eventData.getEvent());
                        acknowledgeMessage(message.getId());
                        continue;
                    }
                    
                    // Filter by headers
                    if (!headerFilter.matches(eventData)) {
                        Log.debugf("Skipping event: %s (headers don't match filter criteria)", eventData.getEvent());
                        acknowledgeMessage(message.getId());
                        continue;
                    }
                    
                    // Create exchange and process
                    Exchange exchange = createExchange(false);
                    exchange.getIn().setBody(eventData);
                    exchange.getIn().setHeader("RedisStreamId", message.getId());
                    exchange.getIn().setHeader("RedisStreamKey", configuration.getStreamKeyName());
                    exchange.getIn().setHeader("ConsumerGroup", configuration.getGroup());
                    
                    getProcessor().process(exchange);
                    
                    // Auto-acknowledge if configured
                    if (configuration.isAutoAck()) {
                        acknowledgeMessage(message.getId());
                    }
                    
                    processedCount++;
                    
                } catch (Exception e) {
                    Log.errorf(e, "Error processing message: %s", message.getId());
                    // Don't acknowledge failed messages
                    handleProcessingError(message, e);
                }
            }
            
            return processedCount;
            
        } catch (Exception e) {
            return 0;
        }
    }

    private String generateConsumerName() {
        if (configuration.getConsumerName() != null && !configuration.getConsumerName().trim().isEmpty()) {
            return configuration.getConsumerName();
        }
        return ConsumerNameGenerator.generateConsumerName(configuration.getGroup());
    }

    private Set<String> parseAllowedEvents() {
        return Arrays.stream(configuration.getEvents().split(","))
                .map(String::trim)
                .filter(event -> !event.isEmpty())
                .collect(Collectors.toSet());
    }

    private void ensureConsumerGroupAndStream() {
        try {
            // Check if stream exists
            boolean streamExists = redisCommands.exists(configuration.getStreamKeyName()) == 1;
            
            if (!streamExists) {
                if (configuration.isAutoCreateStreams()) {
                    // Create the stream by adding a dummy message and removing it
                    Log.infof("Stream '%s' does not exist, creating it...", configuration.getStreamKeyName());
                    
                    // Add a minimal dummy message to create the stream
                    String messageId = redisCommands.xadd(configuration.getStreamKeyName(), 
                        Map.of("_dummy", "true"));
                    
                    // Immediately remove the dummy message to leave stream empty
                    redisCommands.xdel(configuration.getStreamKeyName(), messageId);
                    
                    Log.infof("Successfully created empty stream: %s", configuration.getStreamKeyName());
                } else {
                    throw new RedisStreamException("Stream key '" + configuration.getStreamKeyName() + 
                        "' does not exist and auto-create is disabled");
                }
            }

            // Create consumer group if it doesn't exist and auto-create is enabled
            if (configuration.isAutoCreateGroups()) {
                try {
                    redisCommands.xgroupCreate(
                        XReadArgs.StreamOffset.from(configuration.getStreamKeyName(), "0-0"), 
                        configuration.getGroup()
                    );
                    Log.infof("Created consumer group: %s", configuration.getGroup());
                } catch (Exception e) {
                    Log.debugf("Consumer group already exists: %s", configuration.getGroup());
                }
            }
        } catch (RedisStreamException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisStreamException("Error ensuring consumer group and stream", e);
        }
    }

    private EventData mapToEventData(StreamMessage<String, String> message) throws JsonProcessingException {
        Map<String, String> fields = message.getBody();

        EventData.Builder builder = EventData.builder()
                .keyId(message.getId())
                .aggregateId(fields.get("aggregateId"))
                .event(fields.get("event"))
                .payload(fields.get("payload"))
                .serviceName(fields.get("serviceName"))
                .mimeType(fields.get("mimeType"));
        
        // Parse timestamp
        String timestampStr = fields.get("timestamp");
        if (timestampStr != null) {
            try {
                // Try parsing as ISO instant first, then as epoch millis
                if (timestampStr.contains("T")) {
                    builder.timestamp(Instant.parse(timestampStr));
                } else {
                    builder.timestamp(Instant.ofEpochMilli(Long.parseLong(timestampStr)));
                }
            } catch (Exception e) {
                Log.debugf("Failed to parse timestamp: %s, using current time", timestampStr);
                builder.timestamp(Instant.now());
            }
        }

        // Add headers
        String headersJson = fields.get("headers");
        if (headersJson != null && !headersJson.isEmpty()) {
            try {
                Map<String, Object> headers = objectMapper.readValue(headersJson, Map.class);
                if (headers != null) {
                    builder.headers(headers);
                }
            } catch (Exception e) {
                Log.warnf("Failed to parse headers JSON: %s", headersJson);
            }
        }
        
        // Check for non-standard fields and warn
        List<String> nonStandardFields = fields.keySet().stream()
                .filter(key -> !isStandardField(key))
                .collect(Collectors.toList());
        
        if (!nonStandardFields.isEmpty()) {
            Log.warnf("Found non-standard fields in Redis Stream message: %s. These fields will be ignored.", 
                     nonStandardFields);
        }
        
        return builder.build();
    }

    private boolean isStandardField(String fieldName) {
        return "aggregateId".equals(fieldName) || 
               "event".equals(fieldName) || 
               "payload".equals(fieldName) || 
               "serviceName".equals(fieldName) || 
               "timestamp".equals(fieldName) ||
               "mimeType".equals(fieldName) ||
               "headers".equals(fieldName);
    }

    public void acknowledgeMessage(String messageId) {
        try {
            redisCommands.xack(configuration.getStreamKeyName(), configuration.getGroup(), messageId);
            Log.debugf("Acknowledged message: %s", messageId);
        } catch (Exception e) {
            Log.error("Failed to acknowledge message: " + messageId, e);
        }
    }

    private void handleProcessingError(StreamMessage<String, String> message, Exception error) {
        // TODO: Implement dead letter queue or retry logic
        Log.errorf(error, "Failed to process message: %s - %s", message.getId(), error.getMessage());
    }

    @Override
    protected void doStop() throws Exception {
        Log.infof("Stopping Redis Stream consumer: group=%s, consumer=%s", 
                configuration.getGroup(), consumerName);
        super.doStop();
    }
}
