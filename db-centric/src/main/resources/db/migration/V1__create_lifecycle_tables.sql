CREATE TABLE hierarchies (
    hierarchy_key text PRIMARY KEY,
    first_processed_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_processed_at timestamptz NOT NULL
);

CREATE TABLE orders (
    hierarchy_key text NOT NULL REFERENCES hierarchies,
    order_id text NOT NULL,
    parent_order_id text,
    definition_received boolean NOT NULL DEFAULT false,
    expected_quantity bigint NOT NULL DEFAULT 0 CHECK (expected_quantity >= 0),
    exec_id text,
    trade_date timestamptz,
    transaction_time timestamp(0),
    transaction_nanos integer,
    currency text,
    PRIMARY KEY (hierarchy_key, order_id),
    FOREIGN KEY (hierarchy_key, parent_order_id) REFERENCES orders (hierarchy_key, order_id),
    CHECK ((order_id = hierarchy_key AND parent_order_id IS NULL)
        OR (order_id <> hierarchy_key AND parent_order_id IS NOT NULL AND parent_order_id = hierarchy_key)),
    CHECK ((transaction_time IS NULL AND transaction_nanos IS NULL)
        OR (transaction_time IS NOT NULL AND transaction_nanos IS NOT NULL
            AND transaction_nanos BETWEEN 0 AND 999999999))
);

CREATE TABLE fills (
    hierarchy_key text NOT NULL,
    order_id text NOT NULL,
    fill_id text NOT NULL,
    transaction_time timestamp(0) NOT NULL,
    transaction_nanos integer NOT NULL CHECK (transaction_nanos BETWEEN 0 AND 999999999),
    quantity bigint NOT NULL CHECK (quantity > 0),
    price double precision NOT NULL,
    PRIMARY KEY (hierarchy_key, order_id, fill_id),
    FOREIGN KEY (hierarchy_key, order_id) REFERENCES orders
);

CREATE TABLE processed_input (
    source_id text NOT NULL,
    topic text NOT NULL,
    partition_id integer NOT NULL CHECK (partition_id >= 0),
    record_offset bigint NOT NULL CHECK (record_offset >= 0),
    PRIMARY KEY (source_id, topic, partition_id, record_offset)
);

CREATE TABLE outbox (
    event_id uuid PRIMARY KEY,
    hierarchy_key text NOT NULL UNIQUE REFERENCES hierarchies,
    topic text NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL,
    published_at timestamptz
);
CREATE INDEX outbox_pending_idx ON outbox (created_at, event_id) WHERE published_at IS NULL;

-- A child counts its own fills; the root counts every fill in its hierarchy.
CREATE VIEW order_quantities AS
SELECT o.hierarchy_key, o.order_id, o.expected_quantity,
       COALESCE(SUM(f.quantity), 0)::bigint AS filled_quantity
FROM orders o
LEFT JOIN fills f ON f.hierarchy_key = o.hierarchy_key AND
    (f.order_id = o.order_id OR o.order_id = o.hierarchy_key)
GROUP BY o.hierarchy_key, o.order_id, o.expected_quantity;

CREATE VIEW order_lifecycle AS
SELECT *, CASE
    WHEN expected_quantity > 0 AND filled_quantity >= expected_quantity THEN 'FILLED'
    ELSE 'NOT_FILLED'
END AS status
FROM order_quantities;
