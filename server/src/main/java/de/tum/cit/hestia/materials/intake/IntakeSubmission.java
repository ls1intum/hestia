package de.tum.cit.hestia.materials.intake;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One submission of research material. Besides who submitted it, the row records what they
 * accepted: the terms version and its hash, both confirmations and the two optional consents.
 */
@Entity
@Table(schema = "materials", name = "intake_submission")
public class IntakeSubmission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "submitter_id", nullable = false)
    private String submitterId;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "note")
    private String note;

    @Column(name = "terms_version", nullable = false)
    private String termsVersion;

    @Column(name = "terms_sha256", nullable = false)
    private String termsSha256;

    @Column(name = "confirmed_rights", nullable = false)
    private boolean confirmedRights;

    @Column(name = "confirmed_no_student_data", nullable = false)
    private boolean confirmedNoStudentData;

    @Column(name = "consent_publication", nullable = false)
    private boolean consentPublication;

    @Column(name = "consent_external_ai", nullable = false)
    private boolean consentExternalAi;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IntakeSubmission() {
    }

    IntakeSubmission(String submitterId, String contactEmail, String note, IntakeTerms.Current terms,
                     boolean consentPublication, boolean consentExternalAi, Instant createdAt) {
        this.submitterId = submitterId;
        this.contactEmail = contactEmail;
        this.note = note;
        this.termsVersion = terms.version();
        this.termsSha256 = terms.sha256();
        // Only constructed after both confirmations were checked; the table enforces it as well.
        this.confirmedRights = true;
        this.confirmedNoStudentData = true;
        this.consentPublication = consentPublication;
        this.consentExternalAi = consentExternalAi;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getSubmitterId() {
        return submitterId;
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

    public boolean isConfirmedRights() {
        return confirmedRights;
    }

    public boolean isConfirmedNoStudentData() {
        return confirmedNoStudentData;
    }

    public boolean isConsentPublication() {
        return consentPublication;
    }

    public boolean isConsentExternalAi() {
        return consentExternalAi;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
