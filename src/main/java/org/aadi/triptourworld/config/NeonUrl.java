package org.aadi.triptourworld.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a Neon/Postgres connection string ({@code postgresql://user:pass@host/db?sslmode=require})
 * into the pieces JDBC wants. Also copes with {@code postgres://}, {@code jdbc:} prefixes, strips
 * libpq-only options that the JDBC driver rejects ({@code channel_binding}), and defaults to
 * {@code sslmode=require} for remote hosts, because Neon only accepts TLS connections.
 */
public record NeonUrl(String jdbcUrl, String username, String password) {

    public static NeonUrl parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("DATABASE_URL is empty");
        }
        String u = url.trim();
        if (u.startsWith("jdbc:")) {
            u = u.substring("jdbc:".length());
        }
        if (u.startsWith("postgres://")) {
            u = "postgresql://" + u.substring("postgres://".length());
        }
        if (!u.startsWith("postgresql://")) {
            throw new IllegalArgumentException("DATABASE_URL must start with postgresql:// (or postgres://)");
        }

        URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(
                    "DATABASE_URL is not a valid URI (percent-encode special characters in the password)");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("DATABASE_URL has no host");
        }

        String user = "";
        String pass = "";
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo != null) {
            int colon = rawUserInfo.indexOf(':');
            user = decode(colon >= 0 ? rawUserInfo.substring(0, colon) : rawUserInfo);
            pass = colon >= 0 ? decode(rawUserInfo.substring(colon + 1)) : "";
        }

        int port = uri.getPort() > 0 ? uri.getPort() : 5432;
        String path = uri.getPath() == null ? "" : uri.getPath();
        String db = path.startsWith("/") ? path.substring(1) : path;

        List<String> query = new ArrayList<>();
        boolean hasSsl = false;
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.isBlank()) {
                    continue;
                }
                String name = pair.contains("=") ? pair.substring(0, pair.indexOf('=')) : pair;
                if (name.equalsIgnoreCase("channel_binding")) {
                    continue; // libpq-only
                }
                if (name.equalsIgnoreCase("sslmode")) {
                    hasSsl = true;
                }
                query.add(pair);
            }
        }
        boolean local = uri.getHost().equals("localhost") || uri.getHost().equals("127.0.0.1");
        if (!hasSsl && !local) {
            query.add("sslmode=require");
        }

        String jdbc = "jdbc:postgresql://" + uri.getHost() + ":" + port + "/" + db
                + (query.isEmpty() ? "" : "?" + String.join("&", query));
        return new NeonUrl(jdbc, user, pass);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
