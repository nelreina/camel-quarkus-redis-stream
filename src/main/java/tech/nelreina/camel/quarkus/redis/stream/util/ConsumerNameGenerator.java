package tech.nelreina.camel.quarkus.redis.stream.util;

import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicInteger;

import io.quarkus.logging.Log;

public class ConsumerNameGenerator {
    
    private static final AtomicInteger instanceCounter = new AtomicInteger(1);
    
    public static String generateConsumerName(String groupName) {
        try {
            InetAddress localhost = InetAddress.getLocalHost();
            String hostname = localhost.getHostName();
            int instanceId = instanceCounter.getAndIncrement();
            
            return String.format("%s-%s-%d", groupName, hostname, instanceId);
            
        } catch (Exception e) {
            Log.warn("Failed to get hostname, using fallback consumer name generation", e);
            int instanceId = instanceCounter.getAndIncrement();
            return String.format("%s-consumer-%d", groupName, instanceId);
        }
    }
    
    public static void resetInstanceCounter() {
        instanceCounter.set(1);
    }
}