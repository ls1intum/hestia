package de.tum.cit.hestia.ai;

/**
 * Who serves a model. Logos is the TUM AET gateway and the default for everything. The others are
 * commercial providers outside TUM, called directly only for models Logos does not offer; a call to
 * them needs the user's consent ({@link ConsentScope#EXTERNAL_MODELS}).
 */
public enum Provider {
    LOGOS(false),
    OPENAI(true),
    ANTHROPIC(true),
    GEMINI(true);

    private final boolean external;

    Provider(boolean external) {
        this.external = external;
    }

    /** Outside TUM: needs consent before use. */
    public boolean external() {
        return external;
    }
}
