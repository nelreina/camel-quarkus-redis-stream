package tech.nelreina.camel.quarkus.redis.stream.producer;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultProducer;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamConfiguration;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamEndpoint;
import tech.nelreina.camel.quarkus.redis.stream.exception.RedisStreamException;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;

public class RedisStreamProducer extends DefaultProducer {

    private final RedisStreamEndpoint endpoint;
    private final RedisStreamConfiguration configuration;
    private RedisCommands<String, String> redisCommands;
    private final ObjectMapper objectMapper;

    public RedisStreamProducer(RedisStreamEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
        this.configuration = endpoint.getConfiguration();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        
        StatefulRedisConnection<String, String> connection = endpoint.getConnection();
        if (connection == null) {
            throw new RedisStreamException("Redis connection is not available");
        }
        
        this.redisCommands = connection.sync();
        
        Log.infof("Started Redis Stream producer for stream: %s", configuration.getStreamKeyName());
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Object body = exchange.getIn().getBody();
        
        if (body instanceof EventData) {
            // Direct EventData object
            EventData eventData = (EventData) body;
            publishEventData(eventData);
        } else {
            // Create EventData from message body and headers
            EventData eventData = createEventDataFromExchange(exchange);
            publishEventData(eventData);
        }
        
        // Set the message ID as a header for the response
        exchange.getOut().setHeaders(exchange.getIn().getHeaders());
        exchange.getOut().setBody(exchange.getIn().getBody());
    }

    private EventData createEventDataFromExchange(Exchange exchange) {
        Map<String, Object> headers = exchange.getIn().getHeaders();
        Object body = exchange.getIn().getBody();
        
        EventData.Builder builder = EventData.builder();
        
        // Extract standard fields from headers
        String event = (String) headers.get("event");
        String aggregateId = (String) headers.get("aggregateId");
        String serviceName = (String) headers.get("serviceName");
        
        if (event == null) {
            throw new IllegalArgumentException("'event' header is required when not sending EventData object");
        }
        
        builder.event(event)
               .aggregateId(aggregateId)
               .serviceName(serviceName != null ? serviceName : configuration.getServiceName())
               .payload(body)
               .timestamp(Instant.now());
        
        // Add remaining headers as custom headers
        headers.entrySet().stream()
                .filter(entry -> !isStandardHeader(entry.getKey()))
                .forEach(entry -> builder.header(entry.getKey(), entry.getValue()));
        
        return builder.build();
    }

    private boolean isStandardHeader(String headerName) {
        return "event".equals(headerName) || 
               "aggregateId".equals(headerName) || 
               "serviceName".equals(headerName) ||
               "timestamp".equals(headerName) ||
               "payload".equals(headerName);
    }

    private void publishEventData(EventData eventData) {
        try {
            Map<String, String> streamMessage = convertEventDataToStreamMessage(eventData);
            
            String messageId = redisCommands.xadd(configuration.getStreamKeyName(), streamMessage);
            
            Log.debugf("Published message to stream '%s' with ID: %s", 
                     configuration.getStreamKeyName(), messageId);
            
        } catch (Exception e) {
            throw new RedisStreamException("Failed to publish message to Redis Stream", e);
        }
    }

    private Map<String, String> convertEventDataToStreamMessage(EventData eventData) {
        Map<String, String> message = new HashMap<>();
        
        // Standard fields
        if (eventData.getEvent() != null) {
            message.put("event", eventData.getEvent());
        }
        if (eventData.getAggregateId() != null) {
            message.put("aggregateId", eventData.getAggregateId());
        }
        if (eventData.getServiceName() != null) {
            message.put("serviceName", eventData.getServiceName());
        }
        if (eventData.getTimestamp() != null) {
            message.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(eventData.getTimestamp()));
        }
        
        // Payload - serialize to JSON if it's an object
        if (eventData.getPayload() != null) {
            String payloadStr = serializePayload(eventData.getPayload());
            message.put("payload", payloadStr);
            
            // Add MIME type information
            if (eventData.getPayload() instanceof String) {
                message.put("mimeType", "text/plain");
            } else {
                message.put("mimeType", "application/json");
            }
        }
        
        // Custom headers
        if (eventData.getHeaders() != null) {
            eventData.getHeaders().entrySet().stream()
                    .filter(entry -> entry.getValue() != null)
                    .forEach(entry -> {
                        String value = entry.getValue() instanceof String ? 
                                      (String) entry.getValue() : 
                                      entry.getValue().toString();
                        message.put(entry.getKey(), value);
                    });
        }
        
        return message;
    }

    private String serializePayload(Object payload) {
        if (payload instanceof String) {
            return (String) payload;
        }
        
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Log.warn("Failed to serialize payload as JSON, using toString()", e);
            return payload.toString();
        }
    }

    public void produceMessage(String event, String aggregateId, String payload) {
        produceMessage(event, aggregateId, payload, "application/json");
    }

    public void produceMessage(String event, String aggregateId, String payload, String mimeType) {
        EventData eventData = EventData.builder()
                .event(event)
                .aggregateId(aggregateId)
                .payload(payload)
                .serviceName(configuration.getServiceName())
                .timestamp(Instant.now())
                .header("mimeType", mimeType)
                .build();
        
        publishEventData(eventData);
    }

    @Override
    protected void doStop() throws Exception {
        Log.infof("Stopping Redis Stream producer for stream: %s", configuration.getStreamKeyName());
        super.doStop();
    }
}