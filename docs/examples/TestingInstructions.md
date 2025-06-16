# Testing the Redis Stream Component Locally

## 1. Install Component to Local Repository

From the component project directory:

```bash
mvn clean install
```

This installs the component to your local `~/.m2/repository`.

## 2. Create Test Project

```bash
mvn io.quarkus:quarkus-maven-plugin:3.12.0:create \
    -DprojectGroupId=com.example \
    -DprojectArtifactId=redis-stream-test \
    -DclassName="com.example.TestRoute" \
    -Dpath="/test" \
    -Dextensions="camel-quarkus-core,redis-client"

cd redis-stream-test
```

## 3. Add Component Dependency

Add to `pom.xml`:

```xml
<dependency>
    <groupId>tech.nelreina</groupId>
    <artifactId>camel-quarkus-redis-stream</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

## 4. Configure Application

Update `src/main/resources/application.properties`:

```properties
# Redis connection
quarkus.redis.hosts=redis://localhost:6379

# Remove any conflicting component configuration
# camel.component.redis-stream.auto-ack=true  # Remove this line if present
```

## 5. Create Test Route

Replace `src/main/java/com/example/TestRoute.java`:

```java
package com.example;

import org.apache.camel.builder.RouteBuilder;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TestRoute extends RouteBuilder {

    @Override
    public void configure() throws Exception {
        
        // Simple producer test
        from("timer:producer?period=10000")
            .setHeader("event", constant("TestEvent"))
            .setHeader("aggregateId", constant("test-123"))
            .setHeader("serviceName", constant("test-service"))
            .setBody(constant("{\"message\":\"Hello Redis Stream!\"}"))
            .to("redis-stream://test-stream")
            .log("Published test event");

        // Simple consumer test  
        from("redis-stream://test-stream?group=test-group&events=TestEvent")
            .log("Received event: ${body.event} with payload: ${body.payload}");
    }
}
```

## 6. Start Redis

```bash
# Using Docker
docker run -d -p 6379:6379 --name redis-test redis:latest

# Or use local Redis installation
redis-server
```

## 7. Run Test Application

```bash
mvn quarkus:dev
```

## Expected Output

You should see:
1. Component discovery working (no component error)
2. Producer publishing events every 10 seconds
3. Consumer receiving and logging the events

## Troubleshooting

### Component Not Found Error
If you still get component discovery errors:

1. **Check local installation**:
   ```bash
   ls ~/.m2/repository/tech/nelreina/camel-quarkus-redis-stream/1.0.0-SNAPSHOT/
   ```

2. **Verify JAR contents**:
   ```bash
   jar -tf ~/.m2/repository/tech/nelreina/camel-quarkus-redis-stream/1.0.0-SNAPSHOT/camel-quarkus-redis-stream-1.0.0-SNAPSHOT.jar | grep redis-stream
   ```

3. **Clean and rebuild**:
   ```bash
   # In component directory
   mvn clean install
   
   # In test project
   mvn clean compile
   ```

### Redis Connection Issues
1. Ensure Redis is running on localhost:6379
2. Check application.properties for correct Redis URL
3. Verify with: `redis-cli ping`

### Component Configuration Conflicts
Remove any properties starting with `camel.component.redis-stream.` from your test project's `application.properties` to avoid conflicts.