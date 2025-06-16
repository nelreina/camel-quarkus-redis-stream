package tech.nelreina.camel.quarkus.redis.stream.exception;

public class RedisStreamException extends RuntimeException {

    public RedisStreamException(String message) {
        super(message);
    }

    public RedisStreamException(String message, Throwable cause) {
        super(message, cause);
    }

    public RedisStreamException(Throwable cause) {
        super(cause);
    }
}