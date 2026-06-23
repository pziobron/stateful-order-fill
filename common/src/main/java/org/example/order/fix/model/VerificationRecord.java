package org.example.order.fix.model;

import java.time.Instant;

public record VerificationRecord(
    String hierarchyKey,
    String podId,
    Instant firstProcessedAt,
    Instant completedAt) {}
