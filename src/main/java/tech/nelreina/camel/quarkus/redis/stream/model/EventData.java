package tech.nelreina.camel.quarkus.redis.stream.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public class EventData {
    private String keyId;
    private String aggregateId;
    private Instant timestamp;
    private String event;
    private Object payload;
    private String serviceName;
    private Map<String, Object> headers;

    public EventData() {
        this.headers = new HashMap<>();
    }

    private EventData(Builder builder) {
        this.keyId = builder.keyId;
        this.aggregateId = builder.aggregateId;
        this.timestamp = builder.timestamp;
        this.event = builder.event;
        this.payload = builder.payload;
        this.serviceName = builder.serviceName;
        this.headers = builder.headers != null ? new HashMap<>(builder.headers) : new HashMap<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String keyId;
        private String aggregateId;
        private Instant timestamp;
        private String event;
        private Object payload;
        private String serviceName;
        private Map<String, Object> headers;

        public Builder keyId(String keyId) {
            this.keyId = keyId;
            return this;
        }

        public Builder aggregateId(String aggregateId) {
            this.aggregateId = aggregateId;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder payload(Object payload) {
            this.payload = payload;
            return this;
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder headers(Map<String, Object> headers) {
            this.headers = headers;
            return this;
        }

        public Builder header(String key, Object value) {
            if (this.headers == null) {
                this.headers = new HashMap<>();
            }
            this.headers.put(key, value);
            return this;
        }

        public EventData build() {
            if (timestamp == null) {
                timestamp = Instant.now();
            }
            return new EventData(this);
        }
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public Map<String, Object> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, Object> headers) {
        this.headers = headers != null ? headers : new HashMap<>();
    }

    public Object getHeader(String key) {
        return headers.get(key);
    }

    public void setHeader(String key, Object value) {
        if (headers == null) {
            headers = new HashMap<>();
        }
        headers.put(key, value);
    }

    @Override
    public String toString() {
        return "EventData{" +
                "keyId='" + keyId + '\'' +
                ", aggregateId='" + aggregateId + '\'' +
                ", timestamp=" + timestamp +
                ", event='" + event + '\'' +
                ", payload=" + payload +
                ", serviceName='" + serviceName + '\'' +
                ", headers=" + headers +
                '}';
    }
}