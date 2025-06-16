package tech.nelreina.camel.quarkus.redis.stream.component;

import org.apache.camel.Category;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.support.ScheduledPollEndpoint;

import io.lettuce.core.api.StatefulRedisConnection;
import tech.nelreina.camel.quarkus.redis.stream.consumer.RedisStreamConsumer;
import tech.nelreina.camel.quarkus.redis.stream.producer.RedisStreamProducer;

@UriEndpoint(
    firstVersion = "1.0.0",
    scheme = "redis-stream",
    title = "Redis Stream",
    syntax = "redis-stream:streamKeyName",
    category = {Category.MESSAGING}
)
public class RedisStreamEndpoint extends ScheduledPollEndpoint {

    @UriParam
    private RedisStreamConfiguration configuration;

    private StatefulRedisConnection<String, String> connection;

    public RedisStreamEndpoint(String uri, RedisStreamComponent component, RedisStreamConfiguration configuration) {
        super(uri, component);
        this.configuration = configuration;
    }

    @Override
    public Producer createProducer() throws Exception {
        return new RedisStreamProducer(this);
    }

    @Override
    public Consumer createConsumer(Processor processor) throws Exception {
        RedisStreamConsumer consumer = new RedisStreamConsumer(this, processor);
        configureConsumer(consumer);
        return consumer;
    }

    @Override
    protected void configureConsumer(Consumer consumer) throws Exception {
        super.configureConsumer(consumer);
        if (consumer instanceof RedisStreamConsumer) {
            RedisStreamConsumer redisConsumer = (RedisStreamConsumer) consumer;
            // Configure polling interval (default 1 second)
            redisConsumer.setDelay(1000);
            redisConsumer.setInitialDelay(0);
        }
    }

    public RedisStreamConfiguration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(RedisStreamConfiguration configuration) {
        this.configuration = configuration;
    }

    public StatefulRedisConnection<String, String> getConnection() {
        return connection;
    }

    public void setConnection(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public RedisStreamComponent getComponent() {
        return (RedisStreamComponent) super.getComponent();
    }

    @Override
    protected void doStart() throws Exception {
        super.doStart();
        if (connection == null) {
            connection = getComponent().getConnection();
        }
    }

    @Override
    protected void doStop() throws Exception {
        super.doStop();
        // Connection is managed by the component, don't close it here
    }
}