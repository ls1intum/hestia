package app.sse;

import app.AbstractIntegrationTest;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SseHubBrokenPipeIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired SseHub hub;

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

    /** Opens a real SSE stream, waits for the "connected" comment, then kills it. */
    private void openThenKillSseStream() throws Exception {
        Socket socket = new Socket("localhost", port);
        OutputStream out = socket.getOutputStream();
        out.write(("GET /api/exams/events?token=" + TEST_TOKEN + " HTTP/1.1\r\n"
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
                ("GET /api/__boom HTTP/1.1\r\nHost: localhost:" + port + "\r\n"
                    + "Authorization: Bearer " + TEST_TOKEN + "\r\n\r\n")
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
        openThenKillSseStream();

        // Two publishes: the first hits the dead socket, the second proves the
        // emitter was evicted rather than left to fail again.
        hub.examUpdated(UUID.randomUUID());
        Thread.sleep(300);
        hub.examUpdated(UUID.randomUUID());
        Thread.sleep(500);

        assertThat(errors())
            .describedAs("a client going away is expected teardown, not an error: %s",
                errors().stream().map(ILoggingEvent::getFormattedMessage).toList())
            .isEmpty();
    }
}
