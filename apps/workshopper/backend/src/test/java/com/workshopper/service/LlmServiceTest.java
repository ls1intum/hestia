package com.workshopper.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the pure string-manipulation helpers in {@link LlmService}.
 * No Spring context, no mocks — these are fast, isolated logic tests.
 */
class LlmServiceTest {

    private LlmService llmService;

    @BeforeEach
    void setUp() {
        // LlmService requires a ChatModel, but extractJsonArray / extractJsonObject
        // don't use it — we pass null intentionally for these unit tests.
        llmService = new LlmService(null);
    }

    // ── extractJsonArray ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractJsonArray")
    class ExtractJsonArrayTests {

        @Test
        @DisplayName("returns bare array unchanged")
        void bareArray() {
            String input = "[\"a\", \"b\"]";
            assertThat(llmService.extractJsonArray(input)).isEqualTo("[\"a\", \"b\"]");
        }

        @Test
        @DisplayName("strips markdown json fences")
        void stripsMarkdownFences() {
            String input = "```json\n[\"goal1\", \"goal2\"]\n```";
            assertThat(llmService.extractJsonArray(input)).isEqualTo("[\"goal1\", \"goal2\"]");
        }

        @Test
        @DisplayName("strips plain code fences without language tag")
        void stripsPlainFences() {
            String input = "```\n[{\"id\":\"g1\"}]\n```";
            assertThat(llmService.extractJsonArray(input)).isEqualTo("[{\"id\":\"g1\"}]");
        }

        @Test
        @DisplayName("extracts array when surrounded by prose")
        void extractsArrayFromProse() {
            String input = "Sure! Here are the goals: [{\"id\":\"g1\",\"goal\":\"learn\"}] Hope this helps.";
            String result = llmService.extractJsonArray(input);
            assertThat(result).startsWith("[").endsWith("]");
            assertThat(result).contains("\"id\":\"g1\"");
        }

        @Test
        @DisplayName("throws when no array brackets found")
        void throwsWhenNoBrackets() {
            String input = "Here is some text with no JSON array at all.";
            assertThatThrownBy(() -> llmService.extractJsonArray(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No JSON array found");
        }

        @Test
        @DisplayName("handles empty array []")
        void handlesEmptyArray() {
            assertThat(llmService.extractJsonArray("[]")).isEqualTo("[]");
        }
    }

    // ── extractJsonObject ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("extractJsonObject")
    class ExtractJsonObjectTests {

        @Test
        @DisplayName("returns bare object unchanged")
        void bareObject() {
            String input = "{\"title\":\"Session\",\"blocks\":[]}";
            assertThat(llmService.extractJsonObject(input)).isEqualTo("{\"title\":\"Session\",\"blocks\":[]}");
        }

        @Test
        @DisplayName("strips markdown json fences")
        void stripsMarkdownFences() {
            String input = "```json\n{\"title\":\"Test\"}\n```";
            assertThat(llmService.extractJsonObject(input)).isEqualTo("{\"title\":\"Test\"}");
        }

        @Test
        @DisplayName("extracts object when surrounded by prose")
        void extractsObjectFromProse() {
            String input = "Certainly! {\"title\":\"Session Plan\",\"blocks\":[]} That's your plan.";
            String result = llmService.extractJsonObject(input);
            assertThat(result).startsWith("{").endsWith("}");
            assertThat(result).contains("\"title\":\"Session Plan\"");
        }

        @Test
        @DisplayName("correctly handles nested braces")
        void handlesNestedBraces() {
            String input = "{\"outer\":{\"inner\":\"value\"}}";
            assertThat(llmService.extractJsonObject(input)).isEqualTo("{\"outer\":{\"inner\":\"value\"}}");
        }

        @Test
        @DisplayName("throws when no object braces found")
        void throwsWhenNoBraces() {
            String input = "No JSON object here at all.";
            assertThatThrownBy(() -> llmService.extractJsonObject(input))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No JSON object found");
        }
    }
}
