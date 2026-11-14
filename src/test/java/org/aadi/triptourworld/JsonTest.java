package org.aadi.triptourworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.aadi.triptourworld.util.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesNestedStructures() {
        Object o = Json.parse("{\"a\":[1,2.5,{\"b\":\"x\\ny\\u00e9\"}],\"t\":true,\"n\":null}");
        assertEquals("x\nyé", Json.str(o, "a", 2, "b"));
        assertEquals("1", Json.str(o, "a", 0));
        assertEquals("true", Json.str(o, "t"));
        assertEquals("", Json.str(o, "n"));
        assertEquals("", Json.str(o, "missing", "deep"));
    }

    @Test
    void roundTripsAndEscapes() {
        Map<String, Object> m = Json.obj("q", "say \"hi\"\n", "list", List.of(1, "two"), "d", 2.0);
        Object back = Json.parse(Json.write(m));
        assertEquals("say \"hi\"\n", Json.str(back, "q"));
        assertEquals("two", Json.str(back, "list", 1));
        assertEquals("2", Json.str(back, "d"));
    }

    @Test
    void extractsJsonFromChattyModelOutput() {
        Map<String, Object> m = Json.parseObjectLenient("Sure! ```json\n{\"allowed\": false, \"reason\": \"off topic\"}\n``` hope that helps");
        assertEquals(Boolean.FALSE, m.get("allowed"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObjectLenient("no json here"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertTrue(Json.write(Json.obj("x", Double.NaN)).contains("null"));
    }
}
