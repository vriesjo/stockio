CREATE TABLE allocation_run (
    id UUID PRIMARY KEY,
    run_type VARCHAR(40) NOT NULL,
    as_of_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE allocation_record (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES allocation_run (id),
    sequence_number INTEGER NOT NULL,
    order_id VARCHAR(100) NOT NULL,
    customer VARCHAR(200) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    priority INTEGER NOT NULL CHECK (priority > 0),
    placed_date DATE NOT NULL,
    required_delivery_date DATE NOT NULL,
    requested_quantity INTEGER NOT NULL CHECK (requested_quantity > 0),
    allocated_from_inventory INTEGER NOT NULL CHECK (allocated_from_inventory >= 0),
    allocated_from_inbound INTEGER NOT NULL CHECK (allocated_from_inbound >= 0),
    backordered_quantity INTEGER NOT NULL CHECK (backordered_quantity >= 0),
    status VARCHAR(40) NOT NULL,
    CONSTRAINT uq_allocation_record_run_order UNIQUE (run_id, order_id)
);

CREATE INDEX ix_allocation_record_run_sequence
    ON allocation_record (run_id, sequence_number);

CREATE TABLE allocation_source (
    id UUID PRIMARY KEY,
    allocation_record_id UUID NOT NULL REFERENCES allocation_record (id) ON DELETE CASCADE,
    source_type VARCHAR(20) NOT NULL,
    source_id VARCHAR(100) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    source_date DATE NOT NULL,
    warehouse_location VARCHAR(100)
);

CREATE INDEX ix_allocation_source_record
    ON allocation_source (allocation_record_id);

CREATE TABLE open_backorder (
    order_id VARCHAR(100) PRIMARY KEY,
    customer VARCHAR(200) NOT NULL,
    sku VARCHAR(100) NOT NULL,
    priority INTEGER NOT NULL CHECK (priority > 0),
    placed_date DATE NOT NULL,
    required_delivery_date DATE NOT NULL,
    original_quantity INTEGER NOT NULL CHECK (original_quantity > 0),
    remaining_quantity INTEGER NOT NULL CHECK (remaining_quantity > 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_open_backorder_priority_fifo
    ON open_backorder (priority, placed_date, created_at, order_id);
