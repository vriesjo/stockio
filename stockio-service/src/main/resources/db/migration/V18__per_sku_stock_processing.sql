CREATE TABLE stock_ledger_run (
    id UUID PRIMARY KEY,
    snapshot_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'PARTIAL', 'COMPLETED', 'SUPERSEDED')),
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    last_error VARCHAR(1000)
);

CREATE TABLE stock_ledger_work (
    run_id UUID NOT NULL REFERENCES stock_ledger_run(id),
    sku VARCHAR(64) NOT NULL,
    completed_at TIMESTAMPTZ,
    sku_count INT NOT NULL DEFAULT 0 CHECK (sku_count BETWEEN 0 AND 1),
    lot_count INT NOT NULL DEFAULT 0 CHECK (lot_count >= 0),
    PRIMARY KEY (run_id, sku)
);

CREATE TABLE stock_ledger_coordinator (
    id INT PRIMARY KEY CHECK (id = 1),
    run_id UUID REFERENCES stock_ledger_run(id),
    owner UUID,
    lease_until TIMESTAMPTZ
);
INSERT INTO stock_ledger_coordinator (id) VALUES (1);

CREATE TABLE stock_receipt_followup (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES stock_ledger_run(id),
    sku VARCHAR(64) NOT NULL,
    as_of_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    last_error VARCHAR(1000)
);
CREATE INDEX idx_stock_receipt_followup_pending
    ON stock_receipt_followup(created_at) WHERE dispatched_at IS NULL;

CREATE INDEX idx_demand_order_line_sku ON demand_order_line(sku);
CREATE INDEX idx_stock_reservation_stock ON stock_reservation(stock_id);
CREATE INDEX idx_open_backorder_sku_fifo ON open_backorder(sku, priority, placed_date, created_at, order_id);
