package org.aadi.triptourworld.persistence;

import org.aadi.triptourworld.util.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL (NeonDB) implementation using plain Spring JDBC. State is stored as JSON text. */
public final class JdbcTripRepository implements TripRepository {
    private final JdbcTemplate jdbc;

    public JdbcTripRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates tables if missing (idempotent). */
    public void initSchema() {
        try (InputStream in = getClass().getResourceAsStream("/schema.sql")) {
            if (in == null) {
                throw new IllegalStateException("schema.sql not found on classpath");
            }
            String ddl = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String stmt : ddl.split(";")) {
                if (!stmt.isBlank()) {
                    jdbc.execute(stmt.strip());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read schema.sql", e);
        }
    }

    @Override
    public void create(String id, String query) {
        jdbc.update("INSERT INTO trips (thread_id, user_query, status) VALUES (?, ?, ?)",
                id, query, TripStatus.RUNNING.name());
    }

    @Override
    public void updateStatus(String id, TripStatus status, String error) {
        jdbc.update("UPDATE trips SET status = ?, error = ?, updated_at = now() WHERE thread_id = ?",
                status.name(), error, id);
    }

    @Override
    public void appendProgress(String id, String node) {
        // read-modify-write is fine: one writer per thread at a time
        List<String> current = find(id).map(TripRecord::progress).orElse(new ArrayList<>());
        List<String> next = new ArrayList<>(current);
        next.add(node);
        jdbc.update("UPDATE trips SET progress = ?, updated_at = now() WHERE thread_id = ?", Json.write(next), id);
    }

    @Override
    public void saveState(String id, Map<String, Object> state) {
        jdbc.update("UPDATE trips SET state_json = ?, updated_at = now() WHERE thread_id = ?", Json.write(state), id);
    }

    @Override
    public void addReview(String id, boolean approved, String feedback) {
        jdbc.update("INSERT INTO trip_reviews (thread_id, approved, feedback) VALUES (?, ?, ?)", id, approved, feedback);
    }

    @Override
    public Optional<TripRecord> find(String id) {
        List<TripRecord> rows = jdbc.query(
                "SELECT thread_id, user_query, status, progress, state_json, error, created_at, updated_at "
                        + "FROM trips WHERE thread_id = ?",
                (rs, n) -> map(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7), rs.getTimestamp(8)),
                id);
        return rows.stream().findFirst();
    }

    @Override
    public List<TripRecord> recent(int limit) {
        return jdbc.query(
                "SELECT thread_id, user_query, status, progress, state_json, error, created_at, updated_at "
                        + "FROM trips ORDER BY created_at DESC LIMIT ?",
                (rs, n) -> map(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getTimestamp(7), rs.getTimestamp(8)),
                limit);
    }

    @Override
    public void saveEval(String suite, int total, int passed, String reportJson) {
        jdbc.update("INSERT INTO eval_runs (suite, total, passed, report_json) VALUES (?, ?, ?, ?)",
                suite, total, passed, reportJson);
    }

    @Override
    public Optional<String> latestEval() {
        List<String> rows = jdbc.query("SELECT report_json FROM eval_runs ORDER BY id DESC LIMIT 1",
                (rs, n) -> rs.getString(1));
        return rows.stream().findFirst();
    }

    @Override
    public String kind() {
        return "neondb";
    }

    private static TripRecord map(String id, String query, String status, String progress, String stateJson,
                                  String error, Timestamp created, Timestamp updated) {
        List<String> p = new ArrayList<>();
        for (Object o : Json.asList(parseSafe(progress))) {
            p.add(String.valueOf(o));
        }
        return new TripRecord(id, query, TripStatus.valueOf(status), p, Json.asMap(parseSafe(stateJson)),
                error == null ? "" : error, created.toInstant().toString(), updated.toInstant().toString());
    }

    private static Object parseSafe(String json) {
        try {
            return json == null || json.isBlank() ? null : Json.parse(json);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
