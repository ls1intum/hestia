package de.tum.cit.hestia.ai;

import java.util.List;

/** The models Hestia can call. */
public interface ModelCatalog {

    /** The configured Logos models (default chat and vision model). */
    List<ModelInfo> models();

    /** Whether calls to {@code provider} can be made (Logos always; a direct provider needs its key). */
    boolean isAvailable(Provider provider);

    record ModelInfo(Provider provider, String id, boolean external) {}
}
