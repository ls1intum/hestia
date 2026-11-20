package de.tum.cit.hestia.core.internal.goal;

import de.tum.cit.hestia.core.DomainModule;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores a {@link DomainModule} as its lower-case key ({@code materials}, {@code teaching}, ...). */
@Converter
class DomainModuleConverter implements AttributeConverter<DomainModule, String> {

    @Override
    public String convertToDatabaseColumn(DomainModule module) {
        return module == null ? null : module.key();
    }

    @Override
    public DomainModule convertToEntityAttribute(String key) {
        return key == null ? null : DomainModule.ofKey(key);
    }
}
