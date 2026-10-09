package com.workshopper.repository;

import com.workshopper.model.SlideTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SlideTemplateRepository extends JpaRepository<SlideTemplateEntity, String> {
}
