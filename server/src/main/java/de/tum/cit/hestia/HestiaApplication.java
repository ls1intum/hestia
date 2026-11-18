package de.tum.cit.hestia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

/**
 * The one Hestia server. Its modules are the direct sub-packages of this package (core, materials,
 * teaching, assessment); {@code ModuleBoundaryTest} checks the dependencies between them.
 *
 * <p>Components are named by their fully qualified class name: the modules came from three
 * separate servers and share simple class names such as {@code SecurityConfig} or
 * {@code CourseRepository} (repositories: see {@link JpaRepositoriesConfig}).
 */
@SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class HestiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(HestiaApplication.class, args);
    }
}
