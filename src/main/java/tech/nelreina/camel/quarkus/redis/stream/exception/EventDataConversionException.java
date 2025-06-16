package tech.nelreina.camel.quarkus.redis.stream.exception;

public class EventDataConversionException extends RedisStreamException {

    public EventDataConversionException(String message) {
        super(message);
    }

    public EventDataConversionException(String message, Throwable cause) {
        super(message, cause);
    }

    public EventDataConversionException(Throwable cause) {
        super(cause);
    }
}