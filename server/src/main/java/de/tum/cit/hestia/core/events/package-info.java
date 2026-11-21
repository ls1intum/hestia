/**
 * Events of {@code core}, published inside the transaction that causes them. Domain modules may
 * listen to them for their own side effects; {@code core} itself marks alignment links for review
 * when a goal changes or is removed.
 */
@NamedInterface("events")
package de.tum.cit.hestia.core.events;

import org.springframework.modulith.NamedInterface;
