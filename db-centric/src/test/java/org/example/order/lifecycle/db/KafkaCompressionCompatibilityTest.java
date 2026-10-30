package org.example.order.lifecycle.db;

import org.apache.kafka.common.compress.Compression;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.MemoryRecords;
import org.apache.kafka.common.record.SimpleRecord;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies patched codecs through the Kafka client's actual record batch format. */
class KafkaCompressionCompatibilityTest {
    @ParameterizedTest
    @EnumSource(value = CompressionType.class, names = {"SNAPPY", "LZ4"})
    void roundTripsKafkaRecordBatch(CompressionType type) {
        byte[] key = "hierarchy-1".getBytes(StandardCharsets.UTF_8);
        // Cross compression block boundaries as well as exercising empty payloads.
        byte[] payload = "order-fill-event".repeat(20_000).getBytes(StandardCharsets.UTF_8);
        MemoryRecords encoded = MemoryRecords.withRecords(Compression.of(type).build(),
                new SimpleRecord(1L, key, payload), new SimpleRecord(2L, key, new byte[0]));
        MemoryRecords decoded = MemoryRecords.readableRecords(encoded.buffer().duplicate());
        var batches = decoded.batches().iterator();
        var batch = batches.next();
        batch.ensureValid();
        assertEquals(type, batch.compressionType());
        var records = batch.iterator();
        var first = records.next();
        assertEquals(1L, first.timestamp());
        assertArrayEquals(key, bytes(first.key()));
        assertArrayEquals(payload, bytes(first.value()));
        var second = records.next();
        assertEquals(2L, second.timestamp());
        assertArrayEquals(key, bytes(second.key()));
        assertArrayEquals(new byte[0], bytes(second.value()));
        assertFalse(records.hasNext());
        assertFalse(batches.hasNext());
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] result = new byte[buffer.remaining()];
        buffer.duplicate().get(result);
        return result;
    }
}
