package ai.verbex.speech.sdk.listen.model;

import ai.verbex.speech.sdk.Conformance;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** conformance/responses.json: the model list. */
class ModelsTest {
    private static final JsonNode R = Conformance.load("responses.json");

    @Test
    void models() {
        List<SttModel> models = Models.parse(R.get("models").toString().getBytes());
        assertEquals(1, models.size());
        SttModel m = models.get(0);
        assertEquals("verbex_jp_stt_x1", m.model());
        assertEquals("Verbex Japanese STT X1", m.displayName());
        assertEquals("ja", m.language());
        assertEquals(2, m.modes().size());
        ModelFeature async = m.modes().get(0).features().get(0);
        assertEquals("hotwords", async.paramName());
        assertEquals("list", async.type());
        assertEquals(20, async.maxItems());
        assertNull(async.description());
        assertEquals("Hiragana only.", m.modes().get(1).features().get(0).description());
    }
}
