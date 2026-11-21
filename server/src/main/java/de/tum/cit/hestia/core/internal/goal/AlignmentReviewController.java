package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.AlignmentLinkInfo;
import de.tum.cit.hestia.core.AlignmentReview;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review list of a course ({@code /core/api/courses/{courseId}/reviews}): alignment links whose
 * goal changed or was removed since the link was made or last confirmed. Members read it, editors
 * confirm a link ({@code POST .../reviews/{linkId}/confirm} with the goal version they checked),
 * which removes it from the list.
 */
@RestController
@RequestMapping("/api/courses/{courseId}/reviews")
class AlignmentReviewController {

    private final AlignmentService alignment;

    AlignmentReviewController(AlignmentService alignment) {
        this.alignment = alignment;
    }

    @GetMapping
    public List<AlignmentReview> list(@PathVariable UUID courseId) {
        return alignment.openReviews(courseId);
    }

    /** The goal version the teacher checked; 409 if the goal changed again since. */
    record Confirmation(@NotNull Integer goalVersion) {}

    @PostMapping("/{linkId}/confirm")
    public AlignmentLinkInfo confirm(@PathVariable UUID courseId, @PathVariable UUID linkId,
            @Valid @RequestBody Confirmation confirmation) {
        return alignment.confirm(courseId, linkId, confirmation.goalVersion());
    }
}
