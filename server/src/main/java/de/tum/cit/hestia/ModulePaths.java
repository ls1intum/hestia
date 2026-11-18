package de.tum.cit.hestia;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Mounts each module's controllers under the URL prefix its client already uses. The controllers
 * keep their {@code @RequestMapping("/api/...")}; the three former servers each served
 * {@code /api/**}, so without the prefix their paths would collide.
 */
@Configuration(proxyBeanMethods = false)
class ModulePaths implements WebMvcConfigurer {

    static final String MATERIALS = "/learninggoalhub";
    static final String TEACHING = "/workshopper";
    static final String ASSESSMENT = "/examlense";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(MATERIALS, HandlerTypePredicate.forBasePackage("de.tum.cit.hestia.materials"));
        configurer.addPathPrefix(TEACHING, HandlerTypePredicate.forBasePackage("de.tum.cit.hestia.teaching"));
        configurer.addPathPrefix(ASSESSMENT, HandlerTypePredicate.forBasePackage("de.tum.cit.hestia.assessment"));
    }
}
