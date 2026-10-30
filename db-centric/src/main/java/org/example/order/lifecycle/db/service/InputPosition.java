package org.example.order.lifecycle.db.service;

/** sourceId must change when the input topic is recreated with offsets starting at zero. */
public record InputPosition(String sourceId, String topic, int partition, long offset) {}
