package de.tum.cit.hestia.learninggoalhub.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.converter.CompositeResponseTextCleaner;
import org.springframework.ai.converter.MarkdownCodeBlockCleaner;
import org.springframework.ai.converter.ResponseTextCleaner;
import org.springframework.ai.converter.ThinkingTagCleaner;
import org.springframework.ai.converter.WhitespaceCleaner;
import org.springframework.ai.util.JacksonUtils;
import org.springframework.core.ParameterizedTypeReference;

/**
 * Structured-output converters for LLM replies that carry generated prose.
 *
 * <p>Material full of LaTeX makes models write sequences such as {@code \(} or {@code \frac} inside
 * JSON string values. Those are invalid JSON escapes, so the strict parser Spring AI uses by default
 * rejects the complete reply and the call fails. Reading an unknown escape as the escaped character
 * itself recovers the text a model meant to write instead of losing everything around it.
 *
 * <p>Models also repeat a key inside one object — "sourceFigure" twice in the same knowledge item,
 * say. Jackson binds a record through its canonical constructor, and a creator property that arrives
 * twice fails the whole reply with "No fallback setter/field defined for creator property". One
 * repeated key cost a full course run: the session could not be parsed at all, so there were no
 * outcomes to salvage and the extraction aborted. Collapsing duplicates before binding keeps the
 * last value, which is what a plain JSON reader would have done.
 *
 * <p>The mapper otherwise matches Spring AI's own default for
 * {@link BeanOutputConverter}, so the JSON schema sent to the model is unchanged.
 */
public final class LenientJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModules(JacksonUtils.instantiateAvailableModules())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    /**
     * Spring AI's own default chain, plus duplicate-key collapsing.
     *
     * <p>The first four are what {@code BeanOutputConverter} installs when given no cleaner. They are
     * repeated here because that default is private; passing a cleaner replaces it wholesale rather
     * than appending to it, so dropping them would lose markdown-fence and thinking-tag stripping.
     */
    private static final ResponseTextCleaner TEXT_CLEANER = CompositeResponseTextCleaner.builder()
            .addCleaner(new WhitespaceCleaner())
            .addCleaner(new ThinkingTagCleaner())
            .addCleaner(new MarkdownCodeBlockCleaner())
            .addCleaner(new WhitespaceCleaner())
            .addCleaner(LenientJson::collapseDuplicateKeys)
            .build();

    private LenientJson() {
    }

    /**
     * Rewrites the reply through a JSON tree, where a repeated key simply overwrites the earlier one.
     *
     * <p>Text that does not parse even leniently is returned untouched, so a genuinely malformed
     * reply still fails in the converter with its own message rather than here.
     */
    static String collapseDuplicateKeys(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        try {
            JsonNode tree = MAPPER.readTree(text);
            return MAPPER.writeValueAsString(tree);
        } catch (JsonProcessingException notJson) {
            return text;
        }
    }

    public static <T> BeanOutputConverter<T> converter(ParameterizedTypeReference<T> type) {
        return new BeanOutputConverter<>(type, MAPPER, TEXT_CLEANER);
    }

    public static <T> BeanOutputConverter<T> converter(Class<T> type) {
        return new BeanOutputConverter<>(type, MAPPER, TEXT_CLEANER);
    }
}
