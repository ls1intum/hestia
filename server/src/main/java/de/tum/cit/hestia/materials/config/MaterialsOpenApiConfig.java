package de.tum.cit.hestia.materials.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document of the materials module, served at {@code /v3/api-docs/materials}. It holds
 * only this module's endpoints, and its paths stay {@code /api/...}: the module prefix is the
 * deployment's business (the client's proxy adds it), and the client's generated calls are typed
 * against the unprefixed paths.
 */
@Configuration(proxyBeanMethods = false)
public class MaterialsOpenApiConfig {

    /** Name of the document group; the contract URL is {@code /v3/api-docs/materials}. */
    public static final String GROUP = "materials";

    static final String PREFIX = "/learninggoalhub";

    @Bean
    public GroupedOpenApi materialsApiGroup() {
        return GroupedOpenApi.builder()
                .group(GROUP)
                .pathsToMatch(PREFIX + "/api/**")
                .addOpenApiCustomizer(materialsStripPrefix())
                .build();
    }

    static OpenApiCustomizer materialsStripPrefix() {
        return (OpenAPI openApi) -> {
            Paths paths = openApi.getPaths();
            if (paths == null) {
                return;
            }
            Paths stripped = new Paths();
            for (Map.Entry<String, PathItem> entry : paths.entrySet()) {
                String path = entry.getKey();
                stripped.addPathItem(path.startsWith(PREFIX + "/") ? path.substring(PREFIX.length()) : path,
                        entry.getValue());
            }
            stripped.setExtensions(paths.getExtensions());
            openApi.setPaths(stripped);
        };
    }
}
