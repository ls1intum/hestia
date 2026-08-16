package app.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Local-filesystem {@link StorageService}. Objects live at
 * {@code <base-path>/<bucket>/<path>}, preserving the same {@code {userId}/{examId}/...}
 * layout the frontend used with Supabase Storage. Intended to be backed by a
 * Docker volume in deployment.
 */
@Component
public class LocalFileSystemStorageService implements StorageService {

    private final Path basePath;

    public LocalFileSystemStorageService(@Value("${storage.local.base-path:./data/storage}") String basePath) {
        this.basePath = Path.of(basePath).toAbsolutePath().normalize();
    }

    @Override
    public byte[] download(String bucket, String path) {
        Path target = resolve(bucket, path);
        if (!Files.exists(target)) return null;
        try {
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read object " + bucket + "/" + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void store(String bucket, String path, byte[] bytes) {
        Path target = resolve(bucket, path);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write object " + bucket + "/" + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String bucket, String path) {
        Path target = resolve(bucket, path);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete object " + bucket + "/" + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void deletePrefix(String bucket, String prefix) {
        Path root = resolve(bucket, prefix);
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            // Delete children before their directories.
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete prefix " + bucket + "/" + prefix + ": " + e.getMessage(), e);
        }
    }

    /** Resolve and validate a bucket-relative path, guarding against traversal escapes. */
    private Path resolve(String bucket, String path) {
        Path bucketRoot = basePath.resolve(bucket).normalize();
        Path target = bucketRoot.resolve(path).normalize();
        if (!target.startsWith(bucketRoot)) {
            throw new IllegalArgumentException("Illegal storage path: " + path);
        }
        return target;
    }
}
