package org.aadi.triptourworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aadi.triptourworld.config.DotenvLoader;
import org.aadi.triptourworld.config.NeonUrl;
import org.aadi.triptourworld.config.Settings;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {
    @Test
    void dotenvHandlesSpacesQuotesCommentsAndExport() {
        Map<String, String> m = DotenvLoader.parse(List.of(
                "# comment", "", "GROQ_API_KEY = \"abc123\"", "GROQ_MODEL_ID=\"openai/gpt-oss-20b\"  # inline",
                "export X='single quoted'", "PLAIN=value # trailing", "DEFAULT_ORIGIN_IATA=\"DEL\""));
        assertEquals("abc123", m.get("GROQ_API_KEY"));
        assertEquals("openai/gpt-oss-20b", m.get("GROQ_MODEL_ID"));
        assertEquals("single quoted", m.get("X"));
        assertEquals("value", m.get("PLAIN"));
        assertEquals("DEL", m.get("DEFAULT_ORIGIN_IATA"));
    }

    @Test
    void placeholdersCountAsNotConfigured() {
        Map<String, String> env = Map.of(
                "GROQ_API_KEY", "your_groq_api_key_here",
                "DATABASE_URL", "your_neondb_postgresql_db_url_here",
                "TAVILY_API_KEY", "tvly-real-key-123",
                "LANGSMITH_TRACING", "true", "LANGSMITH_API_KEY", "your_langsmith_api_key_here");
        Settings s = Settings.load(env::get);
        assertFalse(s.llmConfigured());
        assertFalse(s.databaseConfigured());
        assertFalse(s.tracingEnabled()); // tracing requested but key is a placeholder
        assertTrue(s.tracingRequested());
        assertEquals("tvly-real-key-123", s.tavilyKey());
        assertEquals("openai/gpt-oss-20b", s.groqModelId());
        assertEquals("DEL", s.defaultOriginIata());
        assertEquals("TripTourWorld", s.langsmithProject());
    }

    @Test
    void acceptsLegacyAviationStackKeyName() {
        Settings s = Settings.load(Map.of("AVIATION_STACK_API_KEY", "k123456")::get);
        assertEquals("k123456", s.aviationKey());
    }

    @Test
    void neonUrlIsConvertedToJdbc() {
        NeonUrl n = NeonUrl.parse("postgresql://neondb_owner:p%40ss@ep-cool-123.ap-southeast-1.aws.neon.tech/neondb?sslmode=require&channel_binding=require");
        assertEquals("jdbc:postgresql://ep-cool-123.ap-southeast-1.aws.neon.tech:5432/neondb?sslmode=require", n.jdbcUrl());
        assertEquals("neondb_owner", n.username());
        assertEquals("p@ss", n.password());
    }

    @Test
    void neonUrlAddsSslAndAcceptsAlternateSchemes() {
        NeonUrl n = NeonUrl.parse("postgres://u:pw@db.example.com:6543/app");
        assertEquals("jdbc:postgresql://db.example.com:6543/app?sslmode=require", n.jdbcUrl());
        assertEquals("jdbc:postgresql://localhost:5432/dev", NeonUrl.parse("jdbc:postgresql://u:p@localhost/dev").jdbcUrl());
        assertThrows(IllegalArgumentException.class, () -> NeonUrl.parse("mysql://x"));
        assertThrows(IllegalArgumentException.class, () -> NeonUrl.parse(""));
    }
}
