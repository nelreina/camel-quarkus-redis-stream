package docs.examples;

import org.apache.camel.builder.RouteBuilder;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;

/**
 * Example demonstrating global and route-level header filtering
 * 
 * Assume application.properties contains:
 * camel.component.redis-stream.global-header-filters=environment=production,datacenter=us-east-1
 */
public class GlobalAndRouteFilteringExample extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Example 1: Uses only global filters
        // Will only process events with environment=production AND datacenter=us-east-1
        from("redis-stream://system-events?group=monitoring&events=SystemAlert")
            .log("Processing production alert from us-east-1: ${body.event}")
            .to("bean:alertService?method=handleProductionAlert");

        // Example 2: Overrides datacenter from global filters
        // Will process events with environment=production AND datacenter=eu-west-1
        from("redis-stream://system-events?group=eu-monitoring&events=SystemAlert&headerFilters=datacenter=eu-west-1")
            .log("Processing production alert from eu-west-1: ${body.event}")
            .to("bean:euAlertService?method=handleAlert");

        // Example 3: Adds additional filters to global ones
        // Will process events with environment=production AND datacenter=us-east-1 AND severity=critical
        from("redis-stream://system-events?group=critical-alerts&events=SystemAlert&headerFilters=severity=critical")
            .log("Processing CRITICAL production alert: ${body.event}")
            .to("bean:criticalAlertService?method=handleCriticalAlert");

        // Example 4: Override environment and add new filter
        // Will process events with environment=staging AND datacenter=us-east-1 AND team=devops
        from("redis-stream://system-events?group=staging-alerts&events=SystemAlert&headerFilters=environment=staging,team=devops")
            .log("Processing staging alert for DevOps team: ${body.event}")
            .to("bean:stagingAlertService?method=handleAlert");

        // Example 5: Multiple routes with different combinations
        // Customer service routes with region-specific processing
        
        // Inherits global filters + adds customerType filter
        from("redis-stream://customer-events?group=premium-processor&events=CustomerUpdate&headerFilters=customerType=premium")
            .log("Processing premium customer update in production")
            .to("bean:premiumCustomerService?method=process");

        // Override datacenter for APAC processing
        from("redis-stream://customer-events?group=apac-processor&events=CustomerUpdate&headerFilters=datacenter=ap-southeast-1")
            .log("Processing customer update in APAC region")
            .to("bean:apacCustomerService?method=process");

        // Producer example that sets headers matching filters
        from("direct:create-alert")
            .process(exchange -> {
                EventData eventData = EventData.builder()
                    .aggregateId("system-" + System.currentTimeMillis())
                    .event("SystemAlert")
                    .payload(exchange.getIn().getBody())
                    .serviceName("monitoring-service")
                    .header("environment", getEnvironment())  // production, staging, etc.
                    .header("datacenter", getDatacenter())    // us-east-1, eu-west-1, etc.
                    .header("severity", exchange.getIn().getHeader("severity", "normal"))
                    .header("team", exchange.getIn().getHeader("team", "ops"))
                    .build();
                exchange.getIn().setBody(eventData);
            })
            .to("redis-stream://system-events");
    }
    
    private String getEnvironment() {
        // Logic to determine environment
        return System.getProperty("app.environment", "production");
    }
    
    private String getDatacenter() {
        // Logic to determine datacenter
        return System.getProperty("app.datacenter", "us-east-1");
    }
}