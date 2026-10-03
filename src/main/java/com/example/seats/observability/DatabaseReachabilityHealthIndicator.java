package com.example.seats.observability;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Properties;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Readiness check for "is Postgres reachable", deliberately NOT using the application pool:
 * <ul>
 *   <li>fails fast (2s connect/socket timeout) instead of waiting out the pool's 30s connection timeout;</li>
 *   <li>a pool that is merely saturated by a burst is not an outage, so it must not flip readiness.</li>
 * </ul>
 * Registered as health contributor "database".
 */
@Component("database")
public class DatabaseReachabilityHealthIndicator extends AbstractHealthIndicator {

    private final String url;
    private final Properties props = new Properties();

    public DatabaseReachabilityHealthIndicator(JdbcConnectionDetails db) {
        super("database unreachable");
        this.url = db.getJdbcUrl();
        props.setProperty("user", db.getUsername());
        props.setProperty("password", db.getPassword());
        // pgjdbc timeouts, in seconds
        props.setProperty("connectTimeout", "2");
        props.setProperty("socketTimeout", "2");
        props.setProperty("loginTimeout", "2");
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        long start = System.nanoTime();
        try (Connection c = DriverManager.getConnection(url, props); Statement s = c.createStatement()) {
            s.execute("SELECT 1");
        }
        builder.up().withDetail("latency_ms", (System.nanoTime() - start) / 1_000_000);
    }
}
