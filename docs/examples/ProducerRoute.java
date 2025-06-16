package tech.nelreina.camel.quarkus.redis.stream.examples;

import java.time.Instant;
import java.util.UUID;

import org.apache.camel.builder.RouteBuilder;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class ProducerRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Producer using EventData builder pattern
        from("direct:publish-user-event")
            .process(exchange -> {
                String userId = exchange.getIn().getHeader("userId", String.class);
                Object userData = exchange.getIn().getBody();
                
                EventData eventData = EventData.builder()
                    .aggregateId(userId)
                    .event("UserUpdated")
                    .payload(userData)
                    .serviceName("user-service")
                    .timestamp(Instant.now())
                    .header("correlationId", UUID.randomUUID().toString())
                    .header("source", "user-management-api")
                    .build();
                    
                exchange.getIn().setBody(eventData);
            })
            .to("redis-stream://user-events")
            .log("Published user event for user: ${header.userId}");

        // Simple producer using headers
        from("direct:publish-simple")
            .setHeader("event", constant("ProductCreated"))
            .setHeader("aggregateId", simple("${body.id}"))
            .setHeader("serviceName", constant("product-service"))
            .setHeader("correlationId", method(UUID.class, "randomUUID"))
            .to("redis-stream://product-events")
            .log("Published product created event for product: ${body.id}");

        // Batch producer example
        from("direct:publish-batch")
            .split(body())
            .process(exchange -> {
                Object item = exchange.getIn().getBody();
                
                EventData eventData = EventData.builder()
                    .aggregateId(extractId(item))
                    .event("ItemProcessed")
                    .payload(item)
                    .serviceName("batch-processor")
                    .timestamp(Instant.now())
                    .header("batchId", exchange.getProperty("CamelSplitUuid"))
                    .build();
                    
                exchange.getIn().setBody(eventData);
            })
            .to("redis-stream://batch-events")
            .log("Published batch item: ${body.aggregateId}");

        // Error handling with producer
        from("direct:publish-with-error-handling")
            .onException(Exception.class)
                .handled(true)
                .log("Failed to publish event: ${exception.message}")
                .to("direct:dead-letter-queue")
            .end()
            .process(exchange -> {
                EventData eventData = EventData.builder()
                    .aggregateId(exchange.getIn().getHeader("entityId", String.class))
                    .event(exchange.getIn().getHeader("eventType", String.class))
                    .payload(exchange.getIn().getBody())
                    .serviceName("reliable-service")
                    .build();
                    
                exchange.getIn().setBody(eventData);
            })
            .to("redis-stream://reliable-events");
    }
    
    private String extractId(Object item) {
        // Implement your ID extraction logic
        return item.toString();
    }
}