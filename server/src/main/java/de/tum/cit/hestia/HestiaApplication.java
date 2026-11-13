package de.tum.cit.hestia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The one Hestia server. Its modules are the direct sub-packages of this package (core, materials,
 * teaching, assessment); {@code ModuleBoundaryTest} checks the dependencies between them.
 *
 * <p>Components are named by their fully qualified class name: the modules came from three
 * separate servers and share simple class names such as {@code SecurityConfig} or
 * {@code CourseRepository}. Spring Data does not use the application's name generator, so the
 * repositories are enabled here with the same one.
 */
@SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
@EnableJpaRepositories(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class HestiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(HestiaApplication.class, args);
    }
}
