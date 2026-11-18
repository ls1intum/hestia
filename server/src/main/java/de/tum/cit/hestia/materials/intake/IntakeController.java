package de.tum.cit.hestia.materials.intake;

import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Material intake, the first step of the workflow: a logged-in teacher reads the terms of use,
 * confirms them and uploads the material of a past course. There is deliberately no endpoint that
 * lists or downloads other people's submissions; the project owner exports them on the server
 * ({@code infra/scripts/export-intake.sh}).
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

    @Operation(summary = "Who is logged in, and an e-mail suggestion from the login")
    @GetMapping("/me")
    IntakeDtos.MeResponse me() {
        IntakeRequester.Identity identity = requester.current();
        return new IntakeDtos.MeResponse(identity.id(), identity.suggestedEmail());
    }

    @Operation(summary = "Upload a past course's material; needs the confirmation and the version and hash of the terms shown")
    @PostMapping(path = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    IntakeDtos.SubmissionResponse submit(@RequestParam("files") List<MultipartFile> files,
                                         @RequestParam String termsVersion,
                                         @RequestParam String termsSha256,
                                         @RequestParam(defaultValue = "false") boolean confirmedTerms,
                                         @RequestParam(defaultValue = "false") boolean consentResearch,
                                         @RequestParam(defaultValue = "false") boolean consentReplicationPackage,
                                         @RequestParam(required = false) String courseTitle,
                                         @RequestParam(required = false) String contactEmail,
                                         @RequestParam(required = false) String note) {
        return service.submit(requester.current().id(), new IntakeService.NewSubmission(termsVersion, termsSha256,
                confirmedTerms, consentResearch, consentReplicationPackage, courseTitle, contactEmail, note, files));
    }

    @Operation(summary = "The caller's own submissions, newest first")
    @GetMapping("/submissions")
    List<IntakeDtos.SubmissionResponse> mine() {
        return service.mine(requester.current().id());
    }
}
