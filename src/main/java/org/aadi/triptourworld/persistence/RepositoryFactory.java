package org.aadi.triptourworld.persistence;

import org.aadi.triptourworld.config.NeonUrl;
import org.aadi.triptourworld.config.Settings;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chooses the persistence backend at startup: NeonDB when DATABASE_URL is a real URL and reachable
 * (Neon may need a few seconds to wake from auto-suspend, hence the retries), otherwise memory.
 */
public final class RepositoryFactory {
    private static final Logger LOG = Logger.getLogger(RepositoryFactory.class.getName());

    private RepositoryFactory() {}

    /** Repository plus the pool backing it (null for the in-memory store); closed on application shutdown. */
    public record Result(TripRepository repository, HikariDataSource dataSource) implements AutoCloseable {
        @Override
        public void close() {
            if (dataSource != null) {
                dataSource.close();
            }
        }
    }

    public static Result create(Settings cfg) {
        if (!cfg.databaseConfigured()) {
            LOG.warning("DATABASE_URL not set -> using the in-memory store (data is lost on restart).");
            return new Result(new InMemoryTripRepository(), null);
        }
        HikariDataSource ds = null;
        try {
            NeonUrl url = NeonUrl.parse(cfg.databaseUrl());
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(url.jdbcUrl());
            hc.setUsername(url.username());
            hc.setPassword(url.password());
            hc.setMaximumPoolSize(5);
            hc.setMinimumIdle(0);            // let Neon auto-suspend when idle
            hc.setIdleTimeout(60_000);
            hc.setMaxLifetime(240_000);      // recycle before Neon/proxies drop long-lived connections
            hc.setConnectionTimeout(20_000);
            hc.setInitializationFailTimeout(-1);
            hc.setPoolName("neon-pool");
            ds = new HikariDataSource(hc);

            JdbcTripRepository repo = new JdbcTripRepository(new JdbcTemplate(ds));
            RuntimeException last = null;
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    repo.initSchema();
                    LOG.info("Connected to PostgreSQL (NeonDB) and verified schema.");
                    return new Result(repo, ds);
                } catch (RuntimeException e) {
                    last = e;
                    LOG.log(Level.WARNING, "Database not ready (attempt {0}/4): {1}",
                            new Object[] {attempt, e.getMessage()});
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw last == null ? new IllegalStateException("database unavailable") : last;
        } catch (RuntimeException e) {
            LOG.log(Level.SEVERE, "Could not use DATABASE_URL ({0}). Falling back to the in-memory store.",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            if (ds != null) {
                ds.close();
            }
            return new Result(new InMemoryTripRepository(), null);
        }
    }
}
