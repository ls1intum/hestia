package de.tum.cit.hestia.learninggoalhub.goal;

import de.tum.cit.hestia.learninggoalhub.document.DocumentKind;
import de.tum.cit.hestia.learninggoalhub.document.HighlightRect;
import de.tum.cit.hestia.learninggoalhub.extraction.SourceMatchQuality;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record GoalSourceResponse(Long documentId, String filename, String displayName,
                                 @Schema(nullable = true) DocumentKind documentKind,
                                 String snippet, Integer page, boolean contentAvailable, boolean grounded,
                                 @Schema(nullable = true) SourceMatchQuality groundingQuality,
                                 EvidenceKind evidenceKind,
                                 @Schema(nullable = true) String figureDescription,
                                 @Schema(nullable = true) List<HighlightRect> highlightRects) {
    static GoalSourceResponse from(GoalSource s, boolean contentAvailable, String figureDescription) {
        return new GoalSourceResponse(s.getDocument().getId(), s.getDocument().getFilename(),
                s.getDocument().getDisplayName(), s.getDocument().getKind(), s.getSnippet(), s.getPage(), contentAvailable, s.isGrounded(),
                s.getGroundingQuality(), s.getEvidenceKind(), figureDescription, s.getHighlightRects());
    }
}
