package de.tum.cit.hestia.teaching.controller;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuration of the teaching web tests. They name it with {@code @ContextConfiguration} instead of
 * starting from {@code HestiaApplication}, whose {@code @EnableJpaRepositories} would pull the
 * repositories (and so a database) into a web-layer slice. It repeats the module's URL prefix
 * (see {@code ModulePaths}) and adds the exception handler; each test imports its controller.
 */
@Configuration(proxyBeanMethods = false)
@Import(ApiExceptionHandler.class)
class TeachingWebMvcTestConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/workshopper", HandlerTypePredicate.forBasePackage("de.tum.cit.hestia.teaching"));
    }
}
