package app.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemStorageServiceTest {

    @TempDir
    Path base;

    private LocalFileSystemStorageService storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileSystemStorageService(base.toString());
    }

    @Test
    void storeDownloadDeleteRoundtrip() {
        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        storage.store("exam-pdfs", "user/exam.pdf", bytes);
        assertThat(storage.download("exam-pdfs", "user/exam.pdf")).isEqualTo(bytes);

        storage.delete("exam-pdfs", "user/exam.pdf");
        assertThat(storage.download("exam-pdfs", "user/exam.pdf")).isNull();
        // Deleting again is a no-op, not an error.
        storage.delete("exam-pdfs", "user/exam.pdf");
    }

    @Test
    void downloadMissingObjectReturnsNull() {
        assertThat(storage.download("exam-pdfs", "nope/missing.pdf")).isNull();
    }

    @Test
    void deletePrefixRemovesEverythingUnderIt() {
        storage.store("exam-figures", "user/exam1/a.png", new byte[] {1});
        storage.store("exam-figures", "user/exam1/nested/b.png", new byte[] {2});
        storage.store("exam-figures", "user/exam2/keep.png", new byte[] {3});

        storage.deletePrefix("exam-figures", "user/exam1");

        assertThat(storage.download("exam-figures", "user/exam1/a.png")).isNull();
        assertThat(storage.download("exam-figures", "user/exam1/nested/b.png")).isNull();
        assertThat(storage.download("exam-figures", "user/exam2/keep.png")).isNotNull();
        // Missing prefix is a no-op.
        storage.deletePrefix("exam-figures", "user/never-existed");
    }

    @Test
    void rejectsPathTraversalOutOfTheBucket() throws Exception {
        Path secret = base.resolve("secret.txt");
        Files.writeString(secret, "top secret");

        assertThatThrownBy(() -> storage.download("exam-pdfs", "../secret.txt"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("exam-pdfs", "../../evil.sh", new byte[] {1}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.delete("exam-pdfs", "a/../../secret.txt"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(secret)).isEqualTo("top secret");
    }

    @Test
    void rejectsAbsolutePaths() {
        assertThatThrownBy(() -> storage.download("exam-pdfs", "/etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
