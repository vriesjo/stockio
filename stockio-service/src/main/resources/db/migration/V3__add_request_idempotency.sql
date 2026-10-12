ALTER TABLE allocation_run
    ADD COLUMN request_id UUID,
    ADD COLUMN request_hash VARCHAR(64);

UPDATE allocation_run
SET request_id = id,
    request_hash = repeat('0', 64);

ALTER TABLE allocation_run
    ALTER COLUMN request_id SET NOT NULL,
    ALTER COLUMN request_hash SET NOT NULL,
    ADD CONSTRAINT uq_allocation_run_request_id UNIQUE (request_id);
