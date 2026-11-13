package de.tum.cit.hestia.assessment.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Inject the authenticated principal directly into controller args. With the
 * static-token auth filter the principal is the single-user id String
 * ({@link de.tum.cit.hestia.assessment.shared.DefaultUser#ID}).
 *
 * Usage:
 *   @GetMapping("/me")
 *   Map<String,Object> me(@CurrentUser String userId) { ... }
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@AuthenticationPrincipal
public @interface CurrentUser {
}
