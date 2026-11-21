package de.tum.cit.hestia.core.spi;

import de.tum.cit.hestia.core.DomainModule;
import java.util.Collection;
import java.util.Map;

/**
 * Names the objects a domain module links to goals (e.g. a session's title), for the review list
 * of {@code AlignmentApi.openReviews}. Called inside a read-only transaction of the current user;
 * objects the module does not know or the user cannot see are left out of the result.
 */
public interface AlignmentTargetLabels {

    DomainModule module();

    /** Label by target id, for objects of one type of this module. */
    Map<String, String> labels(String targetType, Collection<String> targetIds);
}
