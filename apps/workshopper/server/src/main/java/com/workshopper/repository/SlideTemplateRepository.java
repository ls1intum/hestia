package com.workshopper.repository;

import com.workshopper.model.SlideTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SlideTemplateRepository extends JpaRepository<SlideTemplateEntity, String> {

    /** The template's file name, without loading its file data. */
    @org.springframework.data.jpa.repository.Query("select t.fileName from SlideTemplateEntity t where t.id = :id")
    java.util.Optional<String> findFileNameById(@org.springframework.data.repository.query.Param("id") String id);
}
