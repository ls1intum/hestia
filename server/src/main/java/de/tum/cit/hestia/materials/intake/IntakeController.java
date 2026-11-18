package de.tum.cit.hestia.materials.intake;

import io.swagger.v3.oas.annotations.Operation;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Material intake: a logged-in teacher reads the terms of use, confirms them and uploads files for
 * research. Platform admins list, download and delete all submissions.
 */
@RestController
@RequestMapping("/api/intake")
class IntakeController {

    private final IntakeService service;
    private final IntakeTerms terms;
    private final IntakeRequester requester;

    IntakeController(IntakeService service, IntakeTerms terms, IntakeRequester requester) {
        this.service = service;
        this.terms = terms;
        this.requester = requester;
    }

    @Operation(summary = "The terms of use in force, to show before an upload")
    @GetMapping("/terms")
    IntakeDtos.TermsResponse terms() {
        IntakeTerms.Current current = terms.current();
        return new IntakeDtos.TermsResponse(current.version(), current.sha256(), current.text());
    }

    @Operation(summary = "Who is logged in, whether they are an admin, and an e-mail suggestion from the login")
    @GetMapping("/me")
    IntakeDtos.MeResponse me() {
        IntakeRequester.Identity identity = requester.current();
        return new IntakeDtos.MeResponse(identity.id(), identity.admin(), identity.suggestedEmail());
    }

    @Operation(summary = "Submit files; needs both confirmations and the version and hash of the terms shown")
    @PostMapping(path = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    IntakeDtos.SubmissionResponse submit(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam String termsVersion,
                                         @RequestParam String termsSha256,
                                         @RequestParam(defaultValue = "false") boolean confirmedRights,
                                         @RequestParam(defaultValue = "false") boolean confirmedNoStudentData,
                                         @RequestParam(defaultValue = "false") boolean consentPublication,
                                         @RequestParam(defaultValue = "false") boolean consentExternalAi,
                                         @RequestParam(required = false) String contactEmail,
                                         @RequestParam(required = false) String note) {
        return service.submit(requester.current().id(), new IntakeService.NewSubmission(termsVersion, termsSha256,
                confirmedRights, confirmedNoStudentData, consentPublication, consentExternalAi, contactEmail, note,
                files));
    }

    @Operation(summary = "The caller's own submissions, newest first")
    @GetMapping("/submissions")
    List<IntakeDtos.SubmissionResponse> mine() {
        return service.mine(requester.current().id());
    }

    @Operation(summary = "All submissions, newest first (platform admins only)")
    @GetMapping("/admin/submissions")
    List<IntakeDtos.SubmissionResponse> all() {
        requireAdmin();
        return service.all();
    }

    @Operation(summary = "Download one file of a submission (platform admins only)")
    @GetMapping("/admin/submissions/{submissionId}/files/{fileId}")
    ResponseEntity<byte[]> file(@PathVariable UUID submissionId, @PathVariable UUID fileId) {
        requireAdmin();
        IntakeFile file = service.file(submissionId, fileId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(file.getBytes().length);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(file.getOriginalName(), StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(file.getBytes(), headers, HttpStatus.OK);
    }

    @Operation(summary = "Delete a submission with its files, e.g. on the submitter's request (platform admins only)")
    @DeleteMapping("/admin/submissions/{submissionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable UUID submissionId) {
        requireAdmin();
        service.delete(submissionId);
    }

    private void requireAdmin() {
        if (!requester.current().admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admins only");
        }
    }
}
