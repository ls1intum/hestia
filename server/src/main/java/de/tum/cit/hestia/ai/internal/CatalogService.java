package de.tum.cit.hestia.ai.internal;

import de.tum.cit.hestia.ai.ModelCatalog;
import de.tum.cit.hestia.ai.Provider;
import de.tum.cit.hestia.ai.internal.direct.DirectProviders;
import java.util.ArrayList;
import java.util.List;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

/** The configured Logos models, and which direct providers have a key. */
@Service
class CatalogService implements ModelCatalog {

    private final Environment env;
    private final DirectProviders direct;

    CatalogService(Environment env, DirectProviders direct) {
        this.env = env;
        this.direct = direct;
    }

    @Override
    public List<ModelInfo> models() {
        List<ModelInfo> models = new ArrayList<>();
        for (String key : List.of("spring.ai.openai.chat.options.model", "spring.ai.openai.chat.options.vision-model")) {
            String id = env.getProperty(key);
            if (id != null && !id.isBlank() && models.stream().noneMatch(m -> m.id().equals(id))) {
                models.add(new ModelInfo(Provider.LOGOS, id, false));
            }
        }
        return models;
    }

    @Override
    public boolean isAvailable(Provider provider) {
        return provider == Provider.LOGOS || direct.isConfigured(provider);
    }
}
