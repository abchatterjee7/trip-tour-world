package org.aadi.triptourworld.persistence;

import org.aadi.triptourworld.util.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Bounded in-memory store: lets the app run (and tests pass) without any database. */
public final class InMemoryTripRepository implements TripRepository {
    private static final int MAX_TRIPS = 200;

    private final Map<String, TripRecord> trips = new LinkedHashMap<>();
    private String latestEval;

    @Override
    public synchronized void create(String id, String query) {
        String now = Instant.now().toString();
        trips.put(id, new TripRecord(id, query, TripStatus.RUNNING, new ArrayList<>(), new LinkedHashMap<>(), "", now, now));
        while (trips.size() > MAX_TRIPS) {
            trips.remove(trips.keySet().iterator().next());
        }
    }

    @Override
    public synchronized void updateStatus(String id, TripStatus status, String error) {
        TripRecord r = trips.get(id);
        if (r != null) {
            trips.put(id, new TripRecord(id, r.userQuery(), status, r.progress(), r.state(),
                    error == null ? "" : error, r.createdAt(), Instant.now().toString()));
        }
    }

    @Override
    public synchronized void appendProgress(String id, String node) {
        TripRecord r = trips.get(id);
        if (r != null) {
            List<String> p = new ArrayList<>(r.progress());
            p.add(node);
            trips.put(id, new TripRecord(id, r.userQuery(), r.status(), p, r.state(), r.error(), r.createdAt(),
                    Instant.now().toString()));
        }
    }

    @Override
    public synchronized void saveState(String id, Map<String, Object> state) {
        TripRecord r = trips.get(id);
        if (r != null) {
            trips.put(id, new TripRecord(id, r.userQuery(), r.status(), r.progress(), new LinkedHashMap<>(state),
                    r.error(), r.createdAt(), Instant.now().toString()));
        }
    }

    @Override
    public void addReview(String id, boolean approved, String feedback) {
        // reviews are only kept in the database implementation
    }

    @Override
    public synchronized Optional<TripRecord> find(String id) {
        return Optional.ofNullable(trips.get(id));
    }

    @Override
    public synchronized List<TripRecord> recent(int limit) {
        List<TripRecord> all = new ArrayList<>(trips.values());
        java.util.Collections.reverse(all);
        return all.subList(0, Math.min(limit, all.size()));
    }

    @Override
    public synchronized void saveEval(String suite, int total, int passed, String reportJson) {
        latestEval = reportJson;
    }

    @Override
    public synchronized Optional<String> latestEval() {
        return Optional.ofNullable(latestEval);
    }

    @Override
    public String kind() {
        return "memory";
    }

    /** Convenience for tests. */
    static String debug(Map<String, Object> m) {
        return Json.write(m);
    }
}
