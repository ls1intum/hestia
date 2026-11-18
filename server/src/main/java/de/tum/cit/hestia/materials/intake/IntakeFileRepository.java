package de.tum.cit.hestia.materials.intake;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface IntakeFileRepository extends JpaRepository<IntakeFile, UUID> {

    @Query("""
            select new de.tum.cit.hestia.materials.intake.IntakeFileInfo(
                f.id, f.submissionId, f.originalName, f.contentType, f.sizeBytes, f.sha256)
            from IntakeFile f where f.submissionId in :submissionIds order by f.createdAt, f.originalName""")
    List<IntakeFileInfo> findInfoBySubmissionIds(Collection<UUID> submissionIds);

    Optional<IntakeFile> findByIdAndSubmissionId(UUID id, UUID submissionId);
}
