package tech.nelreina.camel.quarkus.redis.stream.examples;

import org.apache.camel.builder.RouteBuilder;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BasicConsumerRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Basic consumer example - Order processing
        from("redis-stream://order-events?group=inventory-service&events=OrderCreated,OrderCancelled")
            .log("Processing event: ${body.event} for aggregate: ${body.aggregateId}")
            .choice()
                .when(simple("${body.event} == 'OrderCreated'"))
                    .log("Reserving inventory for order: ${body.aggregateId}")
                    .to("bean:inventoryService?method=reserveItems")
                .when(simple("${body.event} == 'OrderCancelled'"))
                    .log("Releasing inventory for order: ${body.aggregateId}")
                    .to("bean:inventoryService?method=releaseItems")
                .otherwise()
                    .log("Unknown event type: ${body.event}")
            .end();

        // Advanced consumer with manual acknowledgment
        from("redis-stream://payment-events?" +
             "group=accounting-service&" +
             "events=PaymentProcessed,PaymentFailed&" +
             "consumerName=accounting-worker-1&" +
             "maxMessages=5&" +
             "blockTimeout=2000&" +
             "autoAck=false")
            .process(exchange -> {
                EventData eventData = exchange.getIn().getBody(EventData.class);
                String messageId = exchange.getIn().getHeader("RedisStreamId", String.class);
                
                try {
                    // Process the payment event
                    processPaymentEvent(eventData);
                    
                    // Manual acknowledgment (you would implement this)
                    // acknowledgeMessage(messageId);
                    
                } catch (Exception e) {
                    log.error("Failed to process payment event: " + messageId, e);
                    // Don't acknowledge - message will be retried
                }
            });
    }
    
    private void processPaymentEvent(EventData eventData) {
        // Implement your payment processing logic here
        log.info("Processing payment event: " + eventData.getEvent() + 
                " for aggregate: " + eventData.getAggregateId());
    }
}