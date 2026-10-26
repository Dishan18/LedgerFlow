package com.ledgerflow.api.support;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Base class for entities whose UUID is generated in Java before insert.
 *
 * <p>Spring Data decides between {@code persist} and {@code merge} by asking whether the id
 * is null. Our ids are never null, so without this it would {@code merge} every new row
 * (an extra SELECT, and cascades configured for PERSIST would be skipped).
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
