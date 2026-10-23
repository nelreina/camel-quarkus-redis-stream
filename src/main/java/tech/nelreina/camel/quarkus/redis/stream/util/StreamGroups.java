package tech.nelreina.camel.quarkus.redis.stream.util;

import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.sync.RedisCommands;
import io.quarkus.logging.Log;

/** Consumer-group helpers shared by the consumer and the tenant manager. */
public final class StreamGroups {

    private StreamGroups() {
    }

    /**
     * {@code XGROUP CREATE <streamKey> <group> <startId> [MKSTREAM]}; BUSYGROUP (group exists) is ignored,
     * so an existing group keeps its position.
     *
     * @return true when the group was created by this call
     */
    public static boolean ensureGroup(RedisCommands<String, String> redis, String streamKey, String group,
            String startId, boolean mkStream) {
        String id = startId == null || startId.isBlank() ? "$" : startId.trim();
        try {
            redis.xgroupCreate(XReadArgs.StreamOffset.from(streamKey, id), group, XGroupCreateArgs.Builder.mkstream(mkStream));
            Log.infof("Created consumer group %s on %s at %s%s", group, streamKey, id, mkStream ? " (MKSTREAM)" : "");
            return true;
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("BUSYGROUP")) {
                Log.debugf("Consumer group %s already exists on %s", group, streamKey);
                return false;
            }
            throw e;
        }
    }

    /** True when the error says the group or the stream key is gone (e.g. after FLUSHALL). */
    public static boolean isNoGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("NOGROUP")) {
                return true;
            }
        }
        return false;
    }
}
