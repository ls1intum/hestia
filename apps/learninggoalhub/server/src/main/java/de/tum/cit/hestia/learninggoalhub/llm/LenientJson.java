package de.tum.cit.hestia.learninggoalhub.llm;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.converter.BeanOutputConverter;
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
 * <p>The mapper otherwise matches Spring AI's own default for
 * {@link BeanOutputConverter}, so the JSON schema sent to the model is unchanged.
 */
public final class LenientJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModules(JacksonUtils.instantiateAvailableModules())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .enable(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER)
            .build();

    private LenientJson() {
    }

    public static <T> BeanOutputConverter<T> converter(ParameterizedTypeReference<T> type) {
        return new BeanOutputConverter<>(type, MAPPER);
    }

    public static <T> BeanOutputConverter<T> converter(Class<T> type) {
        return new BeanOutputConverter<>(type, MAPPER);
    }
}
