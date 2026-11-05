package org.example.order.lifecycle.db.service;

import org.example.order.fix.model.VerificationRecord;
import java.util.Optional;

/** Methods must run inside the publisher's transaction, holding the row lock until commit. */
public interface CompletionStore {
    Optional<VerificationRecord> lockNextCompletion();
    void markPublished(String hierarchyKey);
}
