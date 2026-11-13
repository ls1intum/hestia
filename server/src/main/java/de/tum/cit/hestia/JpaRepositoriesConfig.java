package de.tum.cit.hestia;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Spring Data does not use the application's bean name generator, so the repositories are enabled
 * here with the fully qualified one ({@code CourseRepository} exists in two modules). A separate
 * configuration class, not an annotation on {@link HestiaApplication}, so that test slices such as
 * {@code @WebMvcTest} do not pull in JPA.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
class JpaRepositoriesConfig {
}
