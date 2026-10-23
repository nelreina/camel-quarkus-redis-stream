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

    @UriParam(description = "Consumer name. When not set, the component-level ConsumerNameSupplier (e.g. ConsumerSlot) is used, else an auto-generated name")
    private String consumerName;

    @UriParam(description = "Maximum messages per poll (XREADGROUP COUNT)", defaultValue = "10")
    private int maxMessages = 10;

    @UriParam(description = "Block timeout in milliseconds. Not used for reads: all routes share one connection, so XREADGROUP is non-blocking", defaultValue = "1000")
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

    @UriParam(description = "Comma-separated list of header filters in format key1=value1,key2=value2")
    private String headerFilters;
    
    @UriParam(description = "Id the consumer group is created at (XGROUP CREATE ... MKSTREAM). '$' = only new entries, '0' = whole stream, or an activation id like '<ms>-0'", defaultValue = "$")
    private String groupStartId = "$";

    @UriParam(description = "Minimum idle time in ms before a pending entry is claimed by XAUTOCLAIM", defaultValue = "60000")
    private long autoClaimMinIdleMs = 60000;

    @UriParam(description = "Interval in ms between XAUTOCLAIM passes (also runs once on start); 0 or less disables the periodic pass", defaultValue = "60000")
    private long autoClaimEveryMs = 60000;

    @UriParam(description = "COUNT per XAUTOCLAIM call", defaultValue = "100")
    private int autoClaimCount = 100;

    @UriParam(description = "Drain this consumer's own pending entries (PEL) on start", defaultValue = "true")
    private boolean drainEnabled = true;

    @UriParam(description = "Batch size for the PEL drain", defaultValue = "50")
    private int drainBatch = 50;

    @UriParam(description = "An entry delivered more than this many times is parked (XADD parkedStream + XACK) instead of being retried; a failure on the last allowed delivery parks it as well", defaultValue = "5")
    private int drainMaxDeliveries = 5;

    @UriParam(description = "Stream that receives parked entries (default: ynohub:parked:<group>)")
    private String parkedStream;

    @UriParam(description = "During the XAUTOCLAIM pass, delete other consumers of the group that have an empty PEL and are idle longer than this (ms); 0 disables", defaultValue = "3600000")
    private long deleteIdleConsumersMs = 3600000;

    @UriParam(description = "Max time in ms doStop waits for an in-flight poll to finish", defaultValue = "30000")
    private long stopTimeoutMs = 30000;

    // Global filters from component configuration (not a URI param)
    private String globalHeaderFilters;

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

    public String getHeaderFilters() {
        return headerFilters;
    }

    public void setHeaderFilters(String headerFilters) {
        this.headerFilters = headerFilters;
    }

    public String getGlobalHeaderFilters() {
        return globalHeaderFilters;
    }

    public void setGlobalHeaderFilters(String globalHeaderFilters) {
        this.globalHeaderFilters = globalHeaderFilters;
    }

    public String getGroupStartId() {
        return groupStartId;
    }

    public void setGroupStartId(String groupStartId) {
        this.groupStartId = groupStartId;
    }

    public long getAutoClaimMinIdleMs() {
        return autoClaimMinIdleMs;
    }

    public void setAutoClaimMinIdleMs(long autoClaimMinIdleMs) {
        this.autoClaimMinIdleMs = autoClaimMinIdleMs;
    }

    public long getAutoClaimEveryMs() {
        return autoClaimEveryMs;
    }

    public void setAutoClaimEveryMs(long autoClaimEveryMs) {
        this.autoClaimEveryMs = autoClaimEveryMs;
    }

    public int getAutoClaimCount() {
        return autoClaimCount;
    }

    public void setAutoClaimCount(int autoClaimCount) {
        this.autoClaimCount = autoClaimCount;
    }

    public boolean isDrainEnabled() {
        return drainEnabled;
    }

    public void setDrainEnabled(boolean drainEnabled) {
        this.drainEnabled = drainEnabled;
    }

    public int getDrainBatch() {
        return drainBatch;
    }

    public void setDrainBatch(int drainBatch) {
        this.drainBatch = drainBatch;
    }

    public int getDrainMaxDeliveries() {
        return drainMaxDeliveries;
    }

    public void setDrainMaxDeliveries(int drainMaxDeliveries) {
        this.drainMaxDeliveries = drainMaxDeliveries;
    }

    public String getParkedStream() {
        return parkedStream;
    }

    public void setParkedStream(String parkedStream) {
        this.parkedStream = parkedStream;
    }

    /** Parked stream to use: the configured one, else {@code ynohub:parked:<group>}. */
    public String resolveParkedStream() {
        if (parkedStream != null && !parkedStream.trim().isEmpty()) {
            return parkedStream.trim();
        }
        return "ynohub:parked:" + group;
    }

    public long getDeleteIdleConsumersMs() {
        return deleteIdleConsumersMs;
    }

    public void setDeleteIdleConsumersMs(long deleteIdleConsumersMs) {
        this.deleteIdleConsumersMs = deleteIdleConsumersMs;
    }

    public long getStopTimeoutMs() {
        return stopTimeoutMs;
    }

    public void setStopTimeoutMs(long stopTimeoutMs) {
        this.stopTimeoutMs = stopTimeoutMs;
    }
}
