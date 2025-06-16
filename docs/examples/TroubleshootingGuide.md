# Troubleshooting: "No endpoint could be found" Error

## Updated Component (1.0.1-SNAPSHOT)

I've added debugging and fixed validation issues. The updated component now:

1. **✅ Validates only required parameters** during endpoint creation
2. **✅ Validates consumer parameters** only when creating consumers  
3. **✅ Adds debug logging** to help identify issues
4. **✅ Lazy connection creation** to avoid startup issues

## Testing Steps

### 1. Update Dependency
```xml
<dependency>
    <groupId>tech.nelreina</groupId>
    <artifactId>camel-quarkus-redis-stream</artifactId>
    <version>1.0.1-SNAPSHOT</version>
</dependency>
```

### 2. Simple Producer Test (No Redis Required)
```java
@ApplicationScoped  
public class TestRoute extends RouteBuilder {
    @Override
    public void configure() throws Exception {
        
        // This should work - just tests endpoint creation
        from("timer:test?period=30000")
            .log("Testing endpoint creation...")
            .setBody(constant("test message"))
            .to("redis-stream://test-stream")  // No parameters needed for producer
            .log("Message sent successfully");
    }
}
```

### 3. Check Logs
Look for these debug messages:
```
INFO RedisStreamComponent class loaded successfully
INFO RedisStreamComponent instance created  
INFO Creating Redis Stream endpoint for URI: redis-stream://test-stream, remaining: test-stream, parameters: {}
INFO Successfully created Redis Stream endpoint for stream: test-stream
```

### 4. If Still Getting "No endpoint found"

**Check component discovery:**
```bash
# Verify JAR contents
jar -tf ~/.m2/repository/tech/nelreina/camel-quarkus-redis-stream/1.0.1-SNAPSHOT/camel-quarkus-redis-stream-1.0.1-SNAPSHOT.jar | grep redis-stream
```

**Should show:**
```
META-INF/services/org/apache/camel/component/redis-stream
```

**Check service file content:**
```bash
jar -xf ~/.m2/repository/tech/nelreina/camel-quarkus-redis-stream/1.0.1-SNAPSHOT/camel-quarkus-redis-stream-1.0.1-SNAPSHOT.jar META-INF/services/org/apache/camel/component/redis-stream
cat META-INF/services/org/apache/camel/component/redis-stream
```

**Should show:**
```
class=tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent
```

### 5. Alternative Test Project Setup

If issues persist, try this minimal test project:

**pom.xml:**
```xml
<dependencies>
    <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-arc</artifactId>
    </dependency>
    <dependency>
        <groupId>org.apache.camel.quarkus</groupId>
        <artifactId>camel-quarkus-core</artifactId>
    </dependency>
    <dependency>
        <groupId>tech.nelreina</groupId>
        <artifactId>camel-quarkus-redis-stream</artifactId>
        <version>1.0.1-SNAPSHOT</version>
    </dependency>
</dependencies>
```

**application.properties:**
```properties
# Enable debug logging
quarkus.log.level=INFO
quarkus.log.category."tech.nelreina.camel.quarkus.redis.stream".level=DEBUG
quarkus.log.category."org.apache.camel".level=DEBUG

# No Redis configuration needed for basic test
```

### 6. Expected Debug Output

When working correctly, you should see:
```
INFO  RedisStreamComponent class loaded successfully
INFO  RedisStreamComponent instance created
DEBUG Creating Redis Stream endpoint for URI: redis-stream://test-stream...
DEBUG Successfully created Redis Stream endpoint for stream: test-stream
```

### 7. Common Issues

**Missing from classpath:** 
- Component class not found
- Service file missing
- Wrong component version

**CDI issues:**
- Component not registered as bean
- Injection failures

**Configuration conflicts:**
- Old component properties in application.properties

Let me know what debug output you see and I can help identify the specific issue! 🕵️‍♂️