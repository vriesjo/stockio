ALTER TABLE allocation_run
    ADD COLUMN completed BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE allocation_sku_lock (
    sku VARCHAR(64) PRIMARY KEY
);

CREATE TABLE allocation_sku_operation (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES allocation_run (id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    result_json TEXT NOT NULL,
    CONSTRAINT uq_allocation_sku_operation_run_sku UNIQUE (run_id, sku)
);

CREATE TABLE allocation_order_claim (
    order_id VARCHAR(100) PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES allocation_run (id) ON DELETE CASCADE,
    sku VARCHAR(64) NOT NULL
);

INSERT INTO allocation_order_claim (order_id, run_id, sku)
SELECT record.order_id, run.id, record.sku
FROM allocation_record record
JOIN allocation_run run ON run.id = record.run_id
WHERE run.run_type = 'NEW_DEMAND'
ON CONFLICT (order_id) DO NOTHING;
