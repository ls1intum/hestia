package de.tum.cit.hestia.materials;

import de.tum.cit.hestia.core.CourseApi;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.TestLogin;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import de.tum.cit.hestia.core.internal.identity.IdentityService;
import java.util.UUID;
import de.tum.cit.hestia.core.internal.identity.HestiaAuthentication;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * The default tenant of the materials tests that use the database: the test thread works for
 * {@link #ORG} (so repositories see and write that organisation's rows), and {@link #course(String)}
 * creates a core course the default user {@link #USER} owns. MockMvc requests are logged in as that user
 * by {@link MaterialsTestConfiguration}.
 */
public class TestTenant implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    public static final String ORG = "materials-tests.test";
    public static final String USER = "test-user";

    private static volatile ApplicationContext context;
    private static volatile HestiaPrincipal principal;

    @Override
    public void beforeAll(ExtensionContext extension) {
        context = SpringExtension.getApplicationContext(extension);
        principal = TestLogin.login(context.getBean(IdentityService.class), ORG, USER);
        OrgContext.set(principal.orgId());
    }

    /** The test thread is logged in as the default user, for tests that call services directly. */
    @Override
    public void beforeEach(ExtensionContext extension) {
        OrgContext.set(principal.orgId());
        SecurityContextHolder.getContext().setAuthentication(new HestiaAuthentication(principal));
    }

    /** Logs the test thread in as the default user again (MockMvc clears the context after a request). */
    public static void restoreLogin() {
        if (principal != null) {
            OrgContext.set(principal.orgId());
            SecurityContextHolder.getContext().setAuthentication(new HestiaAuthentication(principal));
        }
    }

    @Override
    public void afterEach(ExtensionContext extension) {
        SecurityContextHolder.clearContext();
    }

    @Override
    public void afterAll(ExtensionContext extension) {
        OrgContext.set(null);
        context = null;
    }

    /** A new core course owned by the default user; returns its id. */
    public static UUID course(String name) {
        return courseAs(ORG, USER, name);
    }

    /** As {@link #course(String)}, for another organisation and user. */
    public static UUID courseAs(String org, String user, String name) {
        IdentityService identities = context.getBean(IdentityService.class);
        CourseApi courseApi = context.getBean(CourseApi.class);
        return TestLogin.runAs(identities, org, user, () -> courseApi.create(name).id());
    }

    public static <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    public static IdentityService identities() {
        return context.getBean(IdentityService.class);
    }
}
