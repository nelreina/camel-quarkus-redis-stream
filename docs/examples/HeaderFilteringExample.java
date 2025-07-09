package docs.examples;

import org.apache.camel.builder.RouteBuilder;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;

public class HeaderFilteringExample extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Example 1: Filter by single header
        from("redis-stream://order-events?group=us-orders&events=OrderCreated&headerFilters=region=US")
            .log("Processing US order: ${body.aggregateId}")
            .to("bean:usOrderService?method=process");

        // Example 2: Filter by multiple headers (AND condition)
        from("redis-stream://order-events?group=priority-orders&events=OrderCreated&headerFilters=priority=high,region=US")
            .log("Processing high priority US order: ${body.aggregateId}")
            .to("bean:priorityOrderService?method=process");

        // Example 3: Different consumers for different regions
        from("redis-stream://notifications?group=email-us&events=SendEmail&headerFilters=region=US")
            .to("bean:usEmailService?method=send");

        from("redis-stream://notifications?group=email-eu&events=SendEmail&headerFilters=region=EU")
            .to("bean:euEmailService?method=send");

        from("redis-stream://notifications?group=email-asia&events=SendEmail&headerFilters=region=ASIA")
            .to("bean:asiaEmailService?method=send");

        // Example 4: Producer that sets headers
        from("direct:create-order")
            .process(exchange -> {
                EventData eventData = EventData.builder()
                    .aggregateId(exchange.getIn().getHeader("orderId", String.class))
                    .event("OrderCreated")
                    .payload(exchange.getIn().getBody())
                    .serviceName("order-service")
                    .header("region", exchange.getIn().getHeader("region"))
                    .header("priority", exchange.getIn().getHeader("priority"))
                    .header("customerType", exchange.getIn().getHeader("customerType"))
                    .build();
                exchange.getIn().setBody(eventData);
            })
            .to("redis-stream://order-events");
    }
}