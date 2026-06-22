package org.example.order.fix.model;

public record VerificationRecord(
    String hierarchyKey,
    String podId,
    String processedAt) {}
