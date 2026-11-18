package de.tum.cit.hestia.materials.course;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Checks the caller's role in the course for every endpoint of the module that carries a course id in
 * its path ({@code /api/courses/{courseId}/**} and {@code /api/courses/{id}}): reading needs a
 * membership, anything but GET/HEAD (content changes, extraction and other model calls) needs editor.
 * Deleting a course needs owner; {@link CourseService#delete} checks that itself. Every object under
 * a course is addressed through its course id, so no endpoint reaches an object around this check.
 */
class CourseAccessInterceptor implements HandlerInterceptor {

    private static final String MODULE_PACKAGE = "de.tum.cit.hestia.materials";

    private final CourseGuard guard;

    CourseAccessInterceptor(CourseGuard guard) {
        this.guard = guard;
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !method.getBeanType().getName().startsWith(MODULE_PACKAGE)) {
            return true;
        }
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (variables == null) {
            return true;
        }
        String raw = variables.get("courseId");
        if (raw == null && method.getBeanType() == CourseController.class) {
            raw = variables.get("id");
        }
        if (raw == null) {
            return true;
        }
        long courseId;
        try {
            courseId = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return true; // the controller answers 400 for a malformed id
        }
        String httpMethod = request.getMethod();
        if ("GET".equals(httpMethod) || "HEAD".equals(httpMethod)) {
            guard.requireRead(courseId);
        } else {
            guard.requireWrite(courseId);
        }
        return true;
    }
}
