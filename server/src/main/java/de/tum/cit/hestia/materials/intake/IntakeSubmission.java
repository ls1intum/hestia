package de.tum.cit.hestia.materials.intake;

import de.tum.cit.hestia.core.OrgScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One upload of a past course's material. Besides who submitted it, the row records what they
 * accepted: the terms version and its hash, the confirmation and the two research consents.
 */
@Entity
@Table(schema = "materials", name = "intake_submission")
public class IntakeSubmission extends OrgScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submitter_id", nullable = false)
    private String submitterId;

    @Column(name = "course_title")
    private String courseTitle;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "note")
    private String note;

    @Column(name = "terms_version", nullable = false)
    private String termsVersion;

    @Column(name = "terms_sha256", nullable = false)
    private String termsSha256;

    @Column(name = "confirmed_terms", nullable = false)
    private boolean confirmedTerms;

    @Column(name = "consent_research", nullable = false)
    private boolean consentResearch;

    @Column(name = "consent_replication_package", nullable = false)
    private boolean consentReplicationPackage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IntakeSubmission() {
    }

    IntakeSubmission(String submitterId, String courseTitle, String contactEmail, String note,
                     IntakeTerms.Current terms, boolean consentResearch, boolean consentReplicationPackage,
                     Instant createdAt) {
        this.submitterId = submitterId;
        this.courseTitle = courseTitle;
        this.contactEmail = contactEmail;
        this.note = note;
        this.termsVersion = terms.version();
        this.termsSha256 = terms.sha256();
        // Only constructed after the confirmation was checked; the table enforces it as well.
        this.confirmedTerms = true;
        this.consentResearch = consentResearch;
        this.consentReplicationPackage = consentReplicationPackage;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSubmitterId() {
        return submitterId;
    }

    public String getCourseTitle() {
        return courseTitle;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getNote() {
        return note;
    }

    public String getTermsVersion() {
        return termsVersion;
    }

    public String getTermsSha256() {
        return termsSha256;
    }

    public boolean isConfirmedTerms() {
        return confirmedTerms;
    }

    public boolean isConsentResearch() {
        return consentResearch;
    }

    public boolean isConsentReplicationPackage() {
        return consentReplicationPackage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
