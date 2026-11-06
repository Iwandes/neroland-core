package za.co.neroland.nerolandcore.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Existing translations retain the source string's format tokens. Missing translations can fall
 * back to English until the community supplies them through Weblate.
 */
class LangFilesTest {

    private static final String DIR = "/assets/nerolandcore/lang/";
    private static final Pattern TOKEN = Pattern.compile("%(?:\\d+\\$)?[sd]|%%|§[0-9a-fk-or]|\\{[^}]*\\}|\n");

    private static JsonObject load(String locale) throws IOException {
        try (InputStream in = LangFilesTest.class.getResourceAsStream(DIR + locale + ".json")) {
            assertNotNull(in, "missing lang file " + locale);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) {
            // Positional and bare %s are interchangeable for counting purposes (translators may reorder).
            out.add(m.group().replaceAll("^%\\d+\\$", "%"));
        }
        Collections.sort(out);
        return out;
    }

    @ParameterizedTest
    @ValueSource(strings = { "zh_cn", "it_it" })
    void translatedPlaceholdersMatchEnglish(String locale) throws IOException {
        JsonObject en = load("en_us");
        JsonObject tr = load(locale);
        for (Map.Entry<String, JsonElement> e : en.entrySet()) {
            if (!tr.has(e.getKey())) continue;
            String translated = tr.get(e.getKey()).getAsString();
            assertEquals(tokens(e.getValue().getAsString()), tokens(translated),
                    locale + " placeholder mismatch for " + e.getKey());
        }
    }
}
