package tech.nelreina.camel.quarkus.redis.stream.component;

import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriParams;
import org.apache.camel.spi.UriPath;

@UriParams
public class RedisStreamConfiguration {

    @UriPath(description = "Redis stream key name")
    private String streamKeyName;

    @UriParam(description = "Consumer group name")
    private String group;

    @UriParam(description = "Comma-separated list of event types to filter")
    private String events;

    @UriParam(description = "Consumer name (default: auto-generated)")
    private String consumerName;

    @UriParam(description = "Maximum messages per poll", defaultValue = "10")
    private int maxMessages = 10;

    @UriParam(description = "Block timeout in milliseconds", defaultValue = "1000")
    private int blockTimeout = 1000;

    @UriParam(description = "Auto-acknowledge messages", defaultValue = "true")
    private boolean autoAck = true;

    @UriParam(description = "Stream start position", defaultValue = ">")
    private String startId = ">";

    @UriParam(description = "Expected payload type", defaultValue = "STRING", 
              enums = "STRING,MAP,OBJECT")
    private PayloadType payloadType = PayloadType.STRING;

    @UriParam(description = "Target class for OBJECT payload type")
    private String objectClass;

    @UriParam(description = "Consumer group prefix for auto-generated names", defaultValue = "camel")
    private String consumerGroupPrefix = "camel";

    @UriParam(description = "Auto-create consumer groups if they don't exist", defaultValue = "true")
    private boolean autoCreateGroups = true;

    @UriParam(description = "Auto-create stream keys if they don't exist", defaultValue = "true")
    private boolean autoCreateStreams = true;

    @UriParam(description = "Service name for produced messages")
    private String serviceName;

    @UriParam(description = "Polling interval in milliseconds", defaultValue = "100")
    private int pollingInterval = 100;

    public enum PayloadType {
        STRING, MAP, OBJECT
    }

    public String getStreamKeyName() {
        return streamKeyName;
    }

    public void setStreamKeyName(String streamKeyName) {
        this.streamKeyName = streamKeyName;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public String getEvents() {
        return events;
    }

    public void setEvents(String events) {
        this.events = events;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public void setConsumerName(String consumerName) {
        this.consumerName = consumerName;
    }

    public int getMaxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public int getBlockTimeout() {
        return blockTimeout;
    }

    public void setBlockTimeout(int blockTimeout) {
        this.blockTimeout = blockTimeout;
    }

    public boolean isAutoAck() {
        return autoAck;
    }

    public void setAutoAck(boolean autoAck) {
        this.autoAck = autoAck;
    }

    public String getStartId() {
        return startId;
    }

    public void setStartId(String startId) {
        this.startId = startId;
    }

    public PayloadType getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(PayloadType payloadType) {
        this.payloadType = payloadType;
    }

    public String getObjectClass() {
        return objectClass;
    }

    public void setObjectClass(String objectClass) {
        this.objectClass = objectClass;
    }

    public String getConsumerGroupPrefix() {
        return consumerGroupPrefix;
    }

    public void setConsumerGroupPrefix(String consumerGroupPrefix) {
        this.consumerGroupPrefix = consumerGroupPrefix;
    }

    public boolean isAutoCreateGroups() {
        return autoCreateGroups;
    }

    public void setAutoCreateGroups(boolean autoCreateGroups) {
        this.autoCreateGroups = autoCreateGroups;
    }

    public boolean isAutoCreateStreams() {
        return autoCreateStreams;
    }

    public void setAutoCreateStreams(boolean autoCreateStreams) {
        this.autoCreateStreams = autoCreateStreams;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public int getPollingInterval() {
        return pollingInterval;
    }

    public void setPollingInterval(int pollingInterval) {
        this.pollingInterval = pollingInterval;
    }
}