package de.tum.cit.hestia.core;

import java.util.Locale;

/**
 * A domain module that creates learning goals or links its objects to them. Stored in lower case,
 * as the module's package name ({@link #key()}).
 */
public enum DomainModule {
    MATERIALS,
    TEACHING,
    ASSESSMENT,
    CATALOG;

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DomainModule ofKey(String key) {
        return valueOf(key.toUpperCase(Locale.ROOT));
    }
}
