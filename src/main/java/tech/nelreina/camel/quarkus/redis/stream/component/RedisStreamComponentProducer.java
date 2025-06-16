package tech.nelreina.camel.quarkus.redis.stream.component;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;

@ApplicationScoped
public class RedisStreamComponentProducer {

    @Produces
    @ApplicationScoped
    @Named("redis-stream")
    public RedisStreamComponent redisStreamComponent() {
        return new RedisStreamComponent();
    }
}