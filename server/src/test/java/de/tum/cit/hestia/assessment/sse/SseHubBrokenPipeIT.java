package de.tum.cit.hestia.assessment.sse;

import de.tum.cit.hestia.assessment.AbstractIntegrationTest;
import de.tum.cit.hestia.assessment.examination.Examination;
import de.tum.cit.hestia.assessment.examination.ExaminationRepository;
import de.tum.cit.hestia.core.OrgContext;
import de.tum.cit.hestia.core.internal.identity.HestiaPrincipal;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduces the broken-pipe ERROR from a real socket: a browser with an open
 * SSE stream goes away, and the next publish writes into the dead connection.
 *
 * <p>A mocked emitter cannot show this — the unit test proves {@code publish}
 * catches the IOException — so the ERROR must be produced below our code, by the
 * container finishing the abandoned async request. This pins where it comes from.
 */
// Profile local: every request is the dev login (no session over a raw socket).
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseHubBrokenPipeIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired SseHub hub;
    @Autowired ExaminationRepository exams;

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @BeforeEach
    void attachAppender() {
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        rootLogger.detachAppender(appender);
    }

    /** The dev login's user, whose organisation the publishing thread carries. */
    private HestiaPrincipal devUser() {
        HestiaPrincipal dev = identities.login("local.test", "dev-local-user", "dev-local-user");
        OrgContext.set(dev.orgId());
        return dev;
    }

    private UUID examCreatedBy(UUID userId) {
        Examination exam = new Examination();
        exam.setSource("pdf");
        exam.setCreatedBy(userId);
        return exams.save(exam).getId();
    }

    /** Opens a real SSE stream of the dev login's list and waits for the "connected" comment. */
    private Socket openListStream() throws Exception {
        Socket socket = new Socket("localhost", port);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /examlense/api/exams/events HTTP/1.1\r\n"
            + "Host: localhost:" + port + "\r\n"
            + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        InputStream in = socket.getInputStream();
        byte[] buf = new byte[1024];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        StringBuilder seen = new StringBuilder();
        while (System.nanoTime() < deadline && !seen.toString().contains("connected")) {
            int n = in.read(buf);
            if (n < 0) break;
            seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        assertThat(seen.toString()).contains("connected");
        return socket;
    }

    /** Everything the stream sends within {@code millis}. */
    private static String readFor(Socket socket, long millis) throws Exception {
        socket.setSoTimeout(100);
        InputStream in = socket.getInputStream();
        byte[] buf = new byte[1024];
        StringBuilder seen = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            try {
                int n = in.read(buf);
                if (n < 0) break;
                seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            } catch (java.net.SocketTimeoutException quiet) {
                // keep waiting until the deadline
            }
        }
        return seen.toString();
    }

    /** Opens a real SSE stream, waits for the "connected" comment, then kills it. */
    private void openThenKillSseStream() throws Exception {
        Socket socket = openListStream();

        // Abort rather than close politely: SO_LINGER 0 sends an RST, which is
        // what a killed tab / reloaded dev page actually does to the server.
        socket.setSoLinger(true, 0);
        socket.close();
        Thread.sleep(300);
    }

    private List<ILoggingEvent> errors() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }

    @Test
    void aGenuineServerErrorIsStillReported() throws Exception {
        // The whole point of the filter is that it is narrow. A real failure must
        // still surface — otherwise this "fix" just blinds us.
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(
                ("GET /examlense/api/__boom HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] buf = new byte[512];
            int n = socket.getInputStream().read(buf);
            String status = new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);

            // Not swallowed into a hung/blank connection: the client gets a response.
            assertThat(status).startsWith("HTTP/1.1");
        }
    }

    @Test
    void publishingToADeadSseStreamLogsNoErrorAndDoesNotThrow() throws Exception {
        UUID exam = examCreatedBy(devUser().userId());
        openThenKillSseStream();

        // Two publishes: the first hits the dead socket, the second proves the
        // emitter was evicted rather than left to fail again.
        hub.examUpdated(exam);
        Thread.sleep(300);
        hub.examUpdated(exam);
        Thread.sleep(500);

        assertThat(errors())
            .describedAs("a client going away is expected teardown, not an error: %s",
                errors().stream().map(ILoggingEvent::getFormattedMessage).toList())
            .isEmpty();
    }

    /**
     * The list stream is per user: the creator hears of their exam, but not of another user's exam
     * in the same university. The creator is looked up in the database under row-level security.
     */
    @Test
    void listEventsReachOnlyTheExamsCreator() throws Exception {
        HestiaPrincipal dev = devUser();
        UUID own = examCreatedBy(dev.userId());
        UUID colleagues = examCreatedBy(identities.login("local.test", "colleague", "colleague").userId());

        try (Socket socket = openListStream()) {
            hub.examUpdated(colleagues);
            hub.examUpdated(own);
            String events = readFor(socket, 1500);

            assertThat(events).contains(own.toString());
            assertThat(events).doesNotContain(colleagues.toString());
        }
    }
}
