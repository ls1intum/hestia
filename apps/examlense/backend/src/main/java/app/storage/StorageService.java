package app.storage;

/**
 * Object storage abstraction. Replaces the Supabase Storage REST client.
 * Buckets used today: {@code exam-pdfs} (PDFs) and {@code exam-figures} (images).
 * A future S3/MinIO implementation can be dropped in without touching callers.
 */
public interface StorageService {

    /** Download an object's bytes, or {@code null} if it does not exist. Throws on unexpected I/O errors. */
    byte[] download(String bucket, String path);

    /** Store (create or overwrite) an object at {@code bucket/path}, creating parent dirs as needed. */
    void store(String bucket, String path, byte[] bytes);

    /** Delete an object; no-op if it does not exist. */
    void delete(String bucket, String path);

    /**
     * Delete every object under {@code bucket/prefix} (e.g. all of an exam's
     * figures); no-op if the prefix does not exist. S3/MinIO support this via
     * list + batch delete.
     */
    void deletePrefix(String bucket, String prefix);
}
