package org.aadi.triptourworld.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TripRepository {

    void create(String threadId, String userQuery);

    void updateStatus(String threadId, TripStatus status, String error);

    void appendProgress(String threadId, String node);

    void saveState(String threadId, Map<String, Object> state);

    void addReview(String threadId, boolean approved, String feedback);

    Optional<TripRecord> find(String threadId);

    List<TripRecord> recent(int limit);

    void saveEval(String suite, int total, int passed, String reportJson);

    Optional<String> latestEval();

    /** "neondb" or "memory" - surfaced by the health endpoint. */
    String kind();
}
