package org.aadi.triptourworld.persistence;

import java.util.List;
import java.util.Map;

/** One trip request: status, live progress and the latest agent-state snapshot. */
public record TripRecord(
        String threadId,
        String userQuery,
        TripStatus status,
        List<String> progress,
        Map<String, Object> state,
        String error,
        String createdAt,
        String updatedAt) {
}
