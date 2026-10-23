package tech.nelreina.camel.quarkus.redis.stream.consumer;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.support.ScheduledPollConsumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import io.lettuce.core.models.stream.PendingMessage;
import io.lettuce.core.models.stream.PendingMessages;
import io.quarkus.logging.Log;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamComponent;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamConfiguration;
import tech.nelreina.camel.quarkus.redis.stream.component.RedisStreamEndpoint;
import tech.nelreina.camel.quarkus.redis.stream.exception.RedisStreamException;
import tech.nelreina.camel.quarkus.redis.stream.model.EventData;
import tech.nelreina.camel.quarkus.redis.stream.slot.ConsumerNameSupplier;
import tech.nelreina.camel.quarkus.redis.stream.util.ConsumerNameGenerator;
import tech.nelreina.camel.quarkus.redis.stream.util.HeaderFilter;
import tech.nelreina.camel.quarkus.redis.stream.util.StreamGroups;

/**
 * Redis stream consumer-group consumer (scheduled, non-blocking polls on the component's shared connection).
 *
 * <p>Start sequence: XGROUP CREATE at {@code groupStartId} with MKSTREAM (BUSYGROUP ignored) &rarr; on the
 * first poll: drain this consumer's own PEL with a moving cursor (bounded by the XPENDING count) &rarr;
 * XAUTOCLAIM entries idle longer than {@code autoClaimMinIdleMs} &rarr; then {@code XREADGROUP COUNT maxMessages
 * ... >}. XAUTOCLAIM repeats every {@code autoClaimEveryMs}. An entry delivered more than
 * {@code drainMaxDeliveries} times, or failing on its last allowed delivery, is parked: XADD to
 * {@code parkedStream} (MAXLEN ~ 10000) then XACK.
 *
 * <p>Stop waits (bounded by {@code stopTimeoutMs}) for the in-flight poll; a poll stops between messages,
 * so every processed message is acked and unprocessed ones stay in this consumer's PEL for the next start.
 */
public class RedisStreamConsumer extends ScheduledPollConsumer {

    static final long PARKED_MAXLEN = 10_000;
    static final int AUTOCLAIM_MAX_ROUNDS = 10;

    private final RedisStreamEndpoint endpoint;
    private final RedisStreamConfiguration configuration;
    private RedisCommands<String, String> redisCommands;
    private volatile String consumerName;
    private boolean supplierNamed;
    private Set<String> allowedEvents;
    private HeaderFilter headerFilter;
    private ObjectMapper objectMapper;

    private final ReentrantLock pollLock = new ReentrantLock();
    private volatile boolean stopping;
    private volatile boolean recoveryPending;
    private volatile long nextAutoClaimAt;
    private volatile long parkedCount;

    public RedisStreamConsumer(RedisStreamEndpoint endpoint, Processor processor) {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.configuration = endpoint.getConfiguration();
        this.objectMapper = new ObjectMapper();
    }

    public RedisStreamConsumer(RedisStreamEndpoint endpoint, Processor processor, ObjectMapper objectMapper) {
        super(endpoint, processor);
        this.endpoint = endpoint;
        this.configuration = endpoint.getConfiguration();
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doStart() throws Exception {
        stopping = false;
        StatefulRedisConnection<String, String> connection = endpoint.getConnection();
        if (connection == null) {
            connection = endpoint.getComponent().getConnection();
            endpoint.setConnection(connection);
        }
        if (connection == null) {
            throw new RedisStreamException("Redis connection is not available");
        }

        this.redisCommands = connection.sync();
        this.consumerName = resolveConsumerName();
        this.allowedEvents = parseAllowedEvents();

        // Create header filter with merged global and route filters
        this.headerFilter = new HeaderFilter(
            configuration.getGlobalHeaderFilters(),
            configuration.getHeaderFilters()
        );

        ensureConsumerGroupAndStream();
        recoveryPending = configuration.isDrainEnabled() || configuration.getAutoClaimEveryMs() > 0;
        nextAutoClaimAt = 0;

        // Scheduler starts last: polls only run once the group exists.
        super.doStart();

        Log.infof("Started Redis Stream consumer: group=%s, consumer=%s, stream=%s, events=%s, groupStartId=%s, "
                + "maxMessages=%d, headerFilters=%s (global=%s, route=%s)",
                configuration.getGroup(), consumerName, configuration.getStreamKeyName(), configuration.getEvents(),
                configuration.getGroupStartId(), configuration.getMaxMessages(),
                HeaderFilter.mergeFilters(configuration.getGlobalHeaderFilters(), configuration.getHeaderFilters()),
                configuration.getGlobalHeaderFilters(), configuration.getHeaderFilters());
    }

    @Override
    protected boolean isPollAllowed() {
        return !stopping && super.isPollAllowed();
    }

    @Override
    protected int poll() throws Exception {
        if (stopping) {
            return 0;
        }
        pollLock.lock();
        try {
            if (stopping) {
                return 0;
            }
            int processed = 0;
            if (recoveryPending) {
                recoveryPending = false;
                if (configuration.isDrainEnabled()) {
                    processed += drainOwnPending();
                }
                processed += autoClaimPass();
            } else if (configuration.getAutoClaimEveryMs() > 0 && System.currentTimeMillis() >= nextAutoClaimAt) {
                processed += autoClaimPass();
            }
            if (stopping) {
                return processed;
            }

            List<StreamMessage<String, String>> messages = redisCommands.xreadgroup(
                Consumer.from(configuration.getGroup(), consumerName),
                XReadArgs.Builder.count(Math.max(1, configuration.getMaxMessages())),
                XReadArgs.StreamOffset.from(configuration.getStreamKeyName(), configuration.getStartId())
            );
            for (StreamMessage<String, String> message : messages) {
                if (stopping) {
                    // Remaining entries stay in this consumer's PEL; the next start drains them.
                    break;
                }
                if (processMessage(message, 1)) {
                    processed++;
                }
            }
            return processed;
        } catch (Exception e) {
            if (StreamGroups.isNoGroup(e) && configuration.isAutoCreateGroups() && !stopping) {
                Log.warnf("Consumer group %s on %s is gone (%s); recreating it at %s", configuration.getGroup(),
                    configuration.getStreamKeyName(), e.getMessage(), configuration.getGroupStartId());
                try {
                    ensureConsumerGroupAndStream();
                } catch (Exception again) {
                    Log.errorf(again, "Recreating consumer group %s failed", configuration.getGroup());
                }
            } else if (!stopping) {
                Log.warnf("Poll of %s (group=%s, consumer=%s) failed: %s", configuration.getStreamKeyName(),
                    configuration.getGroup(), consumerName, e.getMessage());
            }
            return 0;
        } finally {
            pollLock.unlock();
        }
    }

    /**
     * Drains this consumer's own pending entries with a moving cursor (a fixed "0" would return the same
     * un-acked entry forever), bounded by the PEL size at the start of the drain.
     */
    int drainOwnPending() {
        long budget = ownPendingCount();
        if (budget <= 0) {
            return 0;
        }
        Log.infof("Draining %d pending entr%s of consumer %s on %s", budget, budget == 1 ? "y" : "ies", consumerName,
            configuration.getStreamKeyName());
        String cursor = "0";
        int processed = 0;
        int batch = Math.max(1, configuration.getDrainBatch());
        while (budget > 0 && !stopping) {
            List<StreamMessage<String, String>> messages = redisCommands.xreadgroup(
                Consumer.from(configuration.getGroup(), consumerName),
                XReadArgs.Builder.count(Math.min(batch, budget)),
                XReadArgs.StreamOffset.from(configuration.getStreamKeyName(), cursor));
            if (messages == null || messages.isEmpty()) {
                break;
            }
            Map<String, Long> deliveries = deliveryCounts(messages);
            for (StreamMessage<String, String> m : messages) {
                if (stopping) {
                    break;
                }
                cursor = m.getId();
                budget--;
                if (processMessage(m, deliveries.getOrDefault(m.getId(), 1L))) {
                    processed++;
                }
            }
        }
        return processed;
    }

    /**
     * XAUTOCLAIM entries idle longer than autoClaimMinIdleMs (orphaned by a crashed pod, or this consumer's own
     * failed entries) and processes them; then deletes idle consumers with an empty PEL.
     */
    int autoClaimPass() {
        nextAutoClaimAt = System.currentTimeMillis() + Math.max(1000, configuration.getAutoClaimEveryMs());
        int processed = 0;
        try {
            String cursor = "0-0";
            for (int round = 0; round < AUTOCLAIM_MAX_ROUNDS && !stopping; round++) {
                ClaimedMessages<String, String> claimed = redisCommands.xautoclaim(configuration.getStreamKeyName(),
                    XAutoClaimArgs.Builder.xautoclaim(Consumer.from(configuration.getGroup(), consumerName),
                        Duration.ofMillis(configuration.getAutoClaimMinIdleMs()), cursor)
                        .count(Math.max(1, configuration.getAutoClaimCount())));
                List<StreamMessage<String, String>> messages = claimed.getMessages();
                if (messages != null && !messages.isEmpty()) {
                    Log.infof("XAUTOCLAIM took %d entr%s on %s for %s", messages.size(),
                        messages.size() == 1 ? "y" : "ies", configuration.getStreamKeyName(), consumerName);
                    Map<String, Long> deliveries = deliveryCounts(messages);
                    for (StreamMessage<String, String> m : messages) {
                        if (stopping) {
                            break;
                        }
                        if (processMessage(m, deliveries.getOrDefault(m.getId(), 1L))) {
                            processed++;
                        }
                    }
                }
                cursor = claimed.getId();
                if (cursor == null || "0-0".equals(cursor)) {
                    break;
                }
            }
            deleteIdleConsumers();
        } catch (Exception e) {
            if (!stopping) {
                Log.warnf("XAUTOCLAIM pass on %s failed: %s", configuration.getStreamKeyName(), e.getMessage());
            }
        }
        return processed;
    }

    /** XGROUP DELCONSUMER for other consumers of the group with an empty PEL, idle longer than the threshold. */
    void deleteIdleConsumers() {
        long threshold = configuration.getDeleteIdleConsumersMs();
        if (threshold <= 0) {
            return;
        }
        List<Object> infos = redisCommands.xinfoConsumers(configuration.getStreamKeyName(), configuration.getGroup());
        for (Object info : infos) {
            Map<String, Object> fields = toFieldMap(info);
            String name = asString(fields.get("name"));
            long pending = asLong(fields.get("pending"));
            long idle = asLong(fields.get("idle"));
            if (name == null || name.equals(consumerName) || pending != 0 || idle < threshold) {
                continue;
            }
            redisCommands.xgroupDelconsumer(configuration.getStreamKeyName(), Consumer.from(configuration.getGroup(), name));
            Log.infof("Deleted idle consumer %s (idle %d ms, empty PEL) from group %s on %s", name, idle,
                configuration.getGroup(), configuration.getStreamKeyName());
        }
    }

    private long ownPendingCount() {
        PendingMessages summary = redisCommands.xpending(configuration.getStreamKeyName(), configuration.getGroup());
        if (summary == null || summary.getCount() == 0 || summary.getConsumerMessageCount() == null) {
            return 0;
        }
        Long own = summary.getConsumerMessageCount().get(consumerName);
        return own == null ? 0 : own;
    }

    private Map<String, Long> deliveryCounts(List<StreamMessage<String, String>> messages) {
        Map<String, Long> counts = new HashMap<>();
        if (messages.isEmpty()) {
            return counts;
        }
        String first = messages.get(0).getId();
        String last = messages.get(messages.size() - 1).getId();
        List<PendingMessage> pending = redisCommands.xpending(configuration.getStreamKeyName(),
            Consumer.from(configuration.getGroup(), consumerName), Range.create(first, last),
            Limit.from(messages.size() * 2L + 10));
        for (PendingMessage p : pending) {
            counts.put(p.getId(), p.getRedeliveryCount());
        }
        return counts;
    }

    /**
     * Processes one entry with the delivery/parking policy.
     *
     * @return true when the message was handed to the route successfully
     */
    boolean processMessage(StreamMessage<String, String> message, long deliveries) {
        Map<String, String> body = message.getBody();
        if (body == null || body.isEmpty()) {
            // Entry deleted or trimmed while pending: nothing to process.
            Log.warnf("Pending entry %s on %s no longer exists; acknowledging it", message.getId(),
                configuration.getStreamKeyName());
            acknowledgeMessage(message.getId());
            return false;
        }
        int max = configuration.getDrainMaxDeliveries();
        if (max > 0 && deliveries > max) {
            park(message, deliveries, "delivered " + deliveries + " times (max " + max + ")");
            return false;
        }
        try {
            EventData eventData = mapToEventData(message);

            // Filter events
            if (!allowedEvents.contains(eventData.getEvent())) {
                Log.debugf("Skipping event: %s (not in allowed events)", eventData.getEvent());
                acknowledgeMessage(message.getId());
                return false;
            }

            // Filter by headers
            if (!headerFilter.matches(eventData)) {
                Log.debugf("Skipping event: %s (headers don't match filter criteria)", eventData.getEvent());
                acknowledgeMessage(message.getId());
                return false;
            }

            Exchange exchange = createExchange(false);
            exchange.getIn().setBody(eventData);
            exchange.getIn().setHeader("RedisStreamId", message.getId());
            exchange.getIn().setHeader("RedisStreamKey", configuration.getStreamKeyName());
            exchange.getIn().setHeader("ConsumerGroup", configuration.getGroup());
            exchange.getIn().setHeader("RedisStreamConsumer", consumerName);
            exchange.getIn().setHeader("RedisStreamDeliveries", deliveries);

            getProcessor().process(exchange);

            if (configuration.isAutoAck()) {
                acknowledgeMessage(message.getId());
            }
            return true;
        } catch (Exception e) {
            handleProcessingError(message, deliveries, e);
            return false;
        }
    }

    private String resolveConsumerName() {
        RedisStreamComponent component = endpoint.getComponent();
        if (configuration.getConsumerName() != null && !configuration.getConsumerName().trim().isEmpty()) {
            supplierNamed = false;
            return configuration.getConsumerName().trim();
        }
        ConsumerNameSupplier supplier = component.getConsumerNameSupplier();
        if (supplier != null) {
            String name = supplier.consumerName();
            if (name != null && !name.isBlank()) {
                supplierNamed = true;
                component.registerSupplierNamedConsumer(this);
                return name;
            }
        }
        supplierNamed = false;
        return ConsumerNameGenerator.generateConsumerName(configuration.getGroup());
    }

    private Set<String> parseAllowedEvents() {
        return Arrays.stream(configuration.getEvents().split(","))
                .map(String::trim)
                .filter(event -> !event.isEmpty())
                .collect(Collectors.toSet());
    }

    private void ensureConsumerGroupAndStream() {
        try {
            String streamKey = configuration.getStreamKeyName();
            if (!configuration.isAutoCreateStreams() && redisCommands.exists(streamKey) != 1) {
                throw new RedisStreamException("Stream key '" + streamKey + "' does not exist and auto-create is disabled");
            }
            if (configuration.isAutoCreateGroups()) {
                StreamGroups.ensureGroup(redisCommands, streamKey, configuration.getGroup(),
                    configuration.getGroupStartId(), configuration.isAutoCreateStreams());
            }
        } catch (RedisStreamException e) {
            throw e;
        } catch (Exception e) {
            throw new RedisStreamException("Error ensuring consumer group and stream", e);
        }
    }

    private EventData mapToEventData(StreamMessage<String, String> message) throws JsonProcessingException {
        Map<String, String> fields = message.getBody();

        EventData.Builder builder = EventData.builder()
                .keyId(message.getId())
                .aggregateId(fields.get("aggregateId"))
                .event(fields.get("event"))
                .payload(fields.get("payload"))
                .serviceName(fields.get("serviceName"))
                .mimeType(fields.get("mimeType"));

        // Parse timestamp
        String timestampStr = fields.get("timestamp");
        if (timestampStr != null) {
            try {
                // Try parsing as ISO instant first, then as epoch millis
                if (timestampStr.contains("T")) {
                    builder.timestamp(Instant.parse(timestampStr));
                } else {
                    builder.timestamp(Instant.ofEpochMilli(Long.parseLong(timestampStr)));
                }
            } catch (Exception e) {
                Log.debugf("Failed to parse timestamp: %s, using current time", timestampStr);
                builder.timestamp(Instant.now());
            }
        }

        // Add headers
        String headersJson = fields.get("headers");
        if (headersJson != null && !headersJson.isEmpty()) {
            try {
                Map<String, Object> headers = objectMapper.readValue(headersJson, Map.class);
                if (headers != null) {
                    builder.headers(headers);
                }
            } catch (Exception e) {
                Log.warnf("Failed to parse headers JSON: %s", headersJson);
            }
        }

        // Check for non-standard fields and warn
        List<String> nonStandardFields = fields.keySet().stream()
                .filter(key -> !isStandardField(key))
                .collect(Collectors.toList());

        if (!nonStandardFields.isEmpty()) {
            Log.warnf("Found non-standard fields in Redis Stream message: %s. These fields will be ignored.",
                     nonStandardFields);
        }

        return builder.build();
    }

    private boolean isStandardField(String fieldName) {
        return "aggregateId".equals(fieldName) ||
               "event".equals(fieldName) ||
               "payload".equals(fieldName) ||
               "serviceName".equals(fieldName) ||
               "timestamp".equals(fieldName) ||
               "mimeType".equals(fieldName) ||
               "headers".equals(fieldName);
    }

    public void acknowledgeMessage(String messageId) {
        try {
            redisCommands.xack(configuration.getStreamKeyName(), configuration.getGroup(), messageId);
            Log.debugf("Acknowledged message: %s", messageId);
        } catch (Exception e) {
            Log.error("Failed to acknowledge message: " + messageId, e);
        }
    }

    /**
     * Failed-message policy: the entry stays in this consumer's PEL (retried by XAUTOCLAIM once idle for
     * autoClaimMinIdleMs) until its delivery count reaches drainMaxDeliveries; the failure on that last
     * delivery parks it.
     */
    private void handleProcessingError(StreamMessage<String, String> message, long deliveries, Exception error) {
        int max = configuration.getDrainMaxDeliveries();
        if (max > 0 && deliveries >= max) {
            Log.errorf(error, "Failed to process message %s on delivery %d of %d; parking it", message.getId(), deliveries, max);
            park(message, deliveries, "failed on delivery " + deliveries + ": " + error);
        } else {
            Log.errorf(error, "Failed to process message %s (delivery %d%s); left pending for retry", message.getId(),
                deliveries, max > 0 ? " of " + max : "");
        }
    }

    /** XADD the entry (with its original fields) to the parked stream, then XACK it. Keeps it pending if XADD fails. */
    void park(StreamMessage<String, String> message, long deliveries, String reason) {
        String parkedStream = configuration.resolveParkedStream();
        Map<String, String> fields = new LinkedHashMap<>();
        if (message.getBody() != null) {
            fields.putAll(message.getBody());
        }
        fields.put("streamKey", configuration.getStreamKeyName());
        fields.put("group", configuration.getGroup());
        fields.put("consumer", consumerName);
        fields.put("entryId", message.getId());
        fields.put("deliveries", String.valueOf(deliveries));
        fields.put("reason", reason == null ? "" : reason);
        try {
            redisCommands.xadd(parkedStream, XAddArgs.Builder.maxlen(PARKED_MAXLEN).approximateTrimming(), fields);
        } catch (Exception e) {
            Log.errorf(e, "Parking %s to %s failed; entry stays pending", message.getId(), parkedStream);
            return;
        }
        acknowledgeMessage(message.getId());
        parkedCount++;
        Log.warnf("Parked entry %s of %s (group=%s, consumer=%s) to %s: %s", message.getId(),
            configuration.getStreamKeyName(), configuration.getGroup(), consumerName, parkedStream, reason);
    }

    /**
     * Clean stop: no new polls, wait (bounded by stopTimeoutMs) for the in-flight poll to finish its current
     * message and ack it, then stop the scheduler (its task is idle by then, so nothing is interrupted).
     */
    @Override
    protected void doStop() throws Exception {
        Log.infof("Stopping Redis Stream consumer: group=%s, consumer=%s", configuration.getGroup(), consumerName);
        awaitInFlightPoll();
        if (supplierNamed) {
            endpoint.getComponent().unregisterSupplierNamedConsumer(this);
        }
        super.doStop();
    }

    @Override
    protected void doSuspend() throws Exception {
        awaitInFlightPoll();
        super.doSuspend();
    }

    @Override
    protected void doResume() throws Exception {
        stopping = false;
        super.doResume();
    }

    private void awaitInFlightPoll() throws InterruptedException {
        stopping = true;
        long timeout = Math.max(0, configuration.getStopTimeoutMs());
        if (pollLock.isHeldByCurrentThread()) {
            return;
        }
        if (pollLock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
            pollLock.unlock();
        } else {
            Log.warnf("In-flight poll of %s (consumer=%s) did not finish within %d ms", configuration.getStreamKeyName(),
                consumerName, timeout);
        }
    }

    @Override
    public RedisStreamEndpoint getEndpoint() {
        return endpoint;
    }

    public String getConsumerName() {
        return consumerName;
    }

    public long getParkedCount() {
        return parkedCount;
    }

    public boolean isStopping() {
        return stopping;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toFieldMap(Object info) {
        Map<String, Object> out = new HashMap<>();
        if (info instanceof Map) {
            ((Map<Object, Object>) info).forEach((k, v) -> out.put(String.valueOf(k), v));
        } else if (info instanceof List) {
            List<Object> l = (List<Object>) info;
            for (int i = 0; i + 1 < l.size(); i += 2) {
                out.put(String.valueOf(l.get(i)), l.get(i + 1));
            }
        }
        return out;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static long asLong(Object o) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        try {
            return o == null ? 0 : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
