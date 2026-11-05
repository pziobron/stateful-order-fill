package org.example.order.lifecycle.db.service;

/** Applies one input report in a database transaction. */
public interface LifecycleProcessor {
    void process(String payload);
}
