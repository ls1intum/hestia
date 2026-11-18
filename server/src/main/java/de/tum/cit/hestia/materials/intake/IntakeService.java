package de.tum.cit.hestia.materials.intake;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stores the material of past courses that teachers upload as the first step of the workflow.
 * Nothing here calls a model.
 */
@Service
class IntakeService {

    /** Formats teachers typically hand in; anything else is refused with 415. */
    static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "pptx", "ppt", "docx", "doc", "xlsx", "odt", "odp",
            "zip", "txt", "md", "tex");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_NOTE_CHARS = 4000;
    private static final int MAX_EMAIL_CHARS = 254;
    private static final int MAX_TITLE_CHARS = 300;

    record NewSubmission(String termsVersion, String termsSha256, boolean confirmedTerms, boolean consentResearch,
                         boolean consentReplicationPackage, String courseTitle, String contactEmail, String note,
                         List<MultipartFile> files) {
    }

    private final IntakeSubmissionRepository submissions;
    private final IntakeFileRepository files;
    private final IntakeTerms terms;
    private final Clock clock = Clock.systemUTC();
    private final int maxFiles;
    private final long maxFileBytes;

    IntakeService(IntakeSubmissionRepository submissions, IntakeFileRepository files, IntakeTerms terms,
                  @Value("${hestia.intake.max-files:10}") int maxFiles,
                  @Value("${hestia.intake.max-file-size:50MB}") DataSize maxFileSize) {
        this.submissions = submissions;
        this.files = files;
        this.terms = terms;
        this.maxFiles = maxFiles;
        this.maxFileBytes = maxFileSize.toBytes();
    }

    @Transactional
    IntakeDtos.SubmissionResponse submit(String submitterId, NewSubmission request) {
        if (!request.confirmedTerms()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The terms of use must be accepted (rights to the material, no student data)");
        }
        IntakeTerms.Current current = terms.current();
        if (!current.version().equals(request.termsVersion()) || !current.sha256().equals(request.termsSha256())) {
            // The page showed another text than the one in force: reload and accept the current one.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The terms of use have changed; please reload");
        }
        List<MultipartFile> uploads = request.files() == null ? List.of()
                : request.files().stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (uploads.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one file is required");
        }
        if (uploads.size() > maxFiles) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most " + maxFiles + " files per submission");
        }
        for (MultipartFile file : uploads) {
            requireAllowed(file);
        }
        String email = normalizeEmail(request.contactEmail());
        String courseTitle = blankToNull(request.courseTitle());
        if (courseTitle != null && courseTitle.length() > MAX_TITLE_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Course title is too long");
        }
        String note = blankToNull(request.note());
        if (note != null && note.length() > MAX_NOTE_CHARS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Note is too long");
        }

        Instant now = clock.instant();
        IntakeSubmission submission = submissions.save(new IntakeSubmission(submitterId, courseTitle, email, note, current,
                request.consentResearch(), request.consentReplicationPackage(), now));
        List<IntakeFile> stored = new ArrayList<>(uploads.size());
        for (MultipartFile upload : uploads) {
            byte[] bytes = bytes(upload);
            stored.add(new IntakeFile(submission.getId(), displayName(upload), upload.getContentType(), bytes,
                    IntakeTerms.sha256(bytes), now));
        }
        files.saveAll(stored);
        files.flush();
        return withFiles(List.of(submission)).getFirst();
    }

    @Transactional(readOnly = true)
    List<IntakeDtos.SubmissionResponse> mine(String submitterId) {
        return withFiles(submissions.findBySubmitterIdOrderByCreatedAtDesc(submitterId));
    }

    private List<IntakeDtos.SubmissionResponse> withFiles(List<IntakeSubmission> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<IntakeFileInfo>> bySubmission = files
                .findInfoBySubmissionIds(list.stream().map(IntakeSubmission::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(IntakeFileInfo::submissionId));
        return list.stream()
                .map(s -> IntakeDtos.SubmissionResponse.from(s, bySubmission.getOrDefault(s.getId(), List.of())))
                .toList();
    }

    private void requireAllowed(MultipartFile file) {
        String name = displayName(file);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "File type not accepted: " + name);
        }
        if (file.getSize() > maxFileBytes) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File too large: " + name);
        }
    }

    private static String displayName(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "upload";
        }
        // Browsers send the bare name; strip any path a client might add.
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    }

    private static String normalizeEmail(String raw) {
        String email = blankToNull(raw);
        if (email == null) {
            return null;
        }
        if (email.length() > MAX_EMAIL_CHARS || !EMAIL.matcher(email).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact e-mail is not valid");
        }
        return email;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
