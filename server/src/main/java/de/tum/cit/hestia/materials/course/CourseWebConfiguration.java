package de.tum.cit.hestia.materials.course;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the course role check for the module's endpoints. */
@Configuration(proxyBeanMethods = false)
class CourseWebConfiguration implements WebMvcConfigurer {

    private final CourseGuard guard;

    CourseWebConfiguration(CourseGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new CourseAccessInterceptor(guard));
    }
}
