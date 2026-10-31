package de.tum.cit.hestia.learninggoalhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The client's types are generated from {@code client/openapi/openapi.json}. This test fails when
 * that snapshot no longer matches the server's {@code /v3/api-docs}, so a changed request or
 * response cannot reach {@code main} with the client typed against the old shape.
 *
 * <p>To update the snapshot after an API change, from the repository root:
 * <pre>
 * UPDATE_OPENAPI_SNAPSHOT=1 ./gradlew :apps:learninggoalhub:server:test --tests '*OpenApiSnapshotTest' --rerun-tasks
 * cd apps/learninggoalhub/client &amp;&amp; npm run generate:api
 * </pre>
 * The comparison ignores key order and {@code servers}, which names the host the document was
 * fetched from.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSnapshotTest {

    /** Gradle runs the tests in the server project directory. */
    private static final Path SNAPSHOT = Path.of("../client/openapi/openapi.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void clientSnapshotMatchesTheServersApiDocument() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        ObjectNode live = (ObjectNode) objectMapper.readTree(body);
        live.remove("servers");

        if ("1".equals(System.getenv("UPDATE_OPENAPI_SNAPSHOT"))) {
            DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                    .withSeparators(Separators.createDefaultInstance()
                            .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                    .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                    .withArrayIndenter(new DefaultIndenter("  ", "\n"));
            Files.writeString(SNAPSHOT, objectMapper.writer(printer).writeValueAsString(live) + "\n");
            return;
        }

        ObjectNode snapshot = (ObjectNode) objectMapper.readTree(Files.readString(SNAPSHOT));
        snapshot.remove("servers");
        assertThat(snapshot)
                .as("client/openapi/openapi.json is out of date; regenerate it as this test's Javadoc says")
                .isEqualTo(live);
    }
}
