package de.tum.cit.hestia.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Work handed to another thread must run for the organisation and the login of the thread that
 * submitted it: core checks course roles of the current user, and without the login a background
 * task fails with "Not logged in". The worker thread must not keep either afterwards.
 */
class OrgContextTest {

    private record Seen(Optional<UUID> org, Authentication auth) {}

    private ExecutorService worker;

    @BeforeEach
    void startWorker() {
        worker = Executors.newSingleThreadExecutor();
    }

    @AfterEach
    void stopWorker() throws InterruptedException {
        worker.shutdownNow();
        worker.awaitTermination(5, TimeUnit.SECONDS);
        SecurityContextHolder.clearContext();
        OrgContext.set(null);
    }

    private static Seen seen() {
        return new Seen(OrgContext.current(), SecurityContextHolder.getContext().getAuthentication());
    }

    private static Authentication login(String name) {
        return new TestingAuthenticationToken(name, "n/a", "ROLE_USER");
    }

    @Test
    void wrappedRunnableRunsWithCallersOrganisationAndLogin() throws Exception {
        UUID org = UUID.randomUUID();
        Authentication lecturer = login("lecturer");
        OrgContext.set(org);
        SecurityContextHolder.getContext().setAuthentication(lecturer);

        Seen[] inTask = new Seen[1];
        worker.submit(OrgContext.wrap(() -> inTask[0] = seen())).get(5, TimeUnit.SECONDS);

        assertThat(inTask[0].org()).contains(org);
        assertThat(inTask[0].auth()).isSameAs(lecturer);
    }

    @Test
    void wrappedCallableRunsWithCallersOrganisationAndLogin() throws Exception {
        UUID org = UUID.randomUUID();
        Authentication lecturer = login("lecturer");
        OrgContext.set(org);
        SecurityContextHolder.getContext().setAuthentication(lecturer);

        Callable<Seen> task = OrgContext.wrap(OrgContextTest::seen);
        Seen inTask = worker.submit(task).get(5, TimeUnit.SECONDS);

        assertThat(inTask.org()).contains(org);
        assertThat(inTask.auth()).isSameAs(lecturer);
    }

    @Test
    void workerThreadKeepsNeitherOrganisationNorLoginAfterTheTask() throws Exception {
        OrgContext.set(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(login("lecturer"));
        worker.submit(OrgContext.wrap(() -> { })).get(5, TimeUnit.SECONDS);

        Seen afterwards = worker.submit(OrgContextTest::seen).get(5, TimeUnit.SECONDS);

        assertThat(afterwards.org()).isEmpty();
        assertThat(afterwards.auth()).isNull();
    }

    @Test
    void workerThreadGetsItsOwnOrganisationAndLoginBackAfterTheTask() throws Exception {
        UUID workerOrg = UUID.randomUUID();
        Authentication workerLogin = login("worker");
        worker.submit(() -> {
            OrgContext.set(workerOrg);
            SecurityContextHolder.getContext().setAuthentication(workerLogin);
        }).get(5, TimeUnit.SECONDS);

        OrgContext.set(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(login("lecturer"));
        worker.submit(OrgContext.wrap(() -> { })).get(5, TimeUnit.SECONDS);

        Seen afterwards = worker.submit(OrgContextTest::seen).get(5, TimeUnit.SECONDS);

        assertThat(afterwards.org()).contains(workerOrg);
        assertThat(afterwards.auth()).isSameAs(workerLogin);
    }

    @Test
    void laterChangesInTheCallerDoNotReachTheTask() throws Exception {
        Authentication lecturer = login("lecturer");
        SecurityContextHolder.getContext().setAuthentication(lecturer);
        Callable<Seen> task = OrgContext.wrap(OrgContextTest::seen);

        SecurityContextHolder.getContext().setAuthentication(login("someone-else"));
        Seen inTask = worker.submit(task).get(5, TimeUnit.SECONDS);

        assertThat(inTask.auth()).isSameAs(lecturer);
    }

    @Test
    void taskDecoratorWrapsLikeWrap() throws Exception {
        UUID org = UUID.randomUUID();
        Authentication lecturer = login("lecturer");
        OrgContext.set(org);
        SecurityContextHolder.getContext().setAuthentication(lecturer);

        Seen[] inTask = new Seen[1];
        Runnable decorated = OrgContext.taskDecorator().decorate(() -> inTask[0] = seen());
        worker.submit(decorated).get(5, TimeUnit.SECONDS);

        assertThat(inTask[0].org()).contains(org);
        assertThat(inTask[0].auth()).isSameAs(lecturer);
    }
}
