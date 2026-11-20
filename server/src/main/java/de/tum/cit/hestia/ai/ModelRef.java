package de.tum.cit.hestia.ai;

import java.util.Objects;

/**
 * A model to call: the provider and its model id. A Logos reference without id means the configured
 * default chat model.
 */
public record ModelRef(Provider provider, String id) {

    public ModelRef {
        Objects.requireNonNull(provider, "provider");
        if (id != null && id.isBlank()) {
            id = null;
        }
        if (id == null && provider != Provider.LOGOS) {
            throw new IllegalArgumentException("A model of " + provider + " needs an id");
        }
    }

    /** The configured default Logos chat model. */
    public static ModelRef logos() {
        return new ModelRef(Provider.LOGOS, null);
    }

    /** A Logos model by id; null or blank means the default. */
    public static ModelRef logos(String id) {
        return new ModelRef(Provider.LOGOS, id);
    }

    public static ModelRef of(Provider provider, String id) {
        return new ModelRef(provider, id);
    }

    public boolean external() {
        return provider.external();
    }
}
