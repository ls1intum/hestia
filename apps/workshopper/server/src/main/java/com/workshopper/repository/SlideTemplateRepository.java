package com.workshopper.repository;

import com.workshopper.model.SlideTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface SlideTemplateRepository extends JpaRepository<SlideTemplateEntity, String> {

    /** The template's file name, without loading its file data. */
    @Query("select t.fileName from SlideTemplateEntity t where t.id = :id")
    Optional<String> findFileNameById(@Param("id") String id);
}
