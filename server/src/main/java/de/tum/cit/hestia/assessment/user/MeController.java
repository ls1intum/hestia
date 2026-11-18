package de.tum.cit.hestia.assessment.user;

import de.tum.cit.hestia.assessment.user.UserDtos.MeResponse;
import de.tum.cit.hestia.core.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Tag(name = "Identity", description = "Who the caller is, and how much LLM quota they have left.")
public class MeController {

    private final CurrentUser currentUser;
    private final LlmQuotaService quota;

    public MeController(CurrentUser currentUser, LlmQuotaService quota) {
        this.currentUser = currentUser;
        this.quota = quota;
    }

    @Operation(
        summary = "The logged-in user",
        description = """
            Doubles as the login probe the client uses before entering the app (401 without a \
            session), and supplies the remaining quota so the UI can warn before a run is spent. \
            `is_admin` is the platform-admin flag.""")
    @GetMapping("/me")
    public MeResponse me() {
        return new MeResponse(currentUser.userId(), currentUser.displayName(), currentUser.isAdmin(),
            quota.remainingFor(currentUser.userId()));
    }
}
