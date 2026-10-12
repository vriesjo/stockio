ALTER TABLE stock
    ADD COLUMN lot_id VARCHAR(64),
    ADD COLUMN received_date DATE;

UPDATE stock
SET lot_id = 'LEGACY-' || id::TEXT,
    received_date = CURRENT_DATE;

ALTER TABLE stock
    ALTER COLUMN lot_id SET NOT NULL,
    ALTER COLUMN received_date SET NOT NULL,
    ADD CONSTRAINT uq_stock_lot UNIQUE (sku, warehouse_code, lot_id);

CREATE TABLE stock_reservation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id VARCHAR(100) NOT NULL,
    stock_id UUID NOT NULL REFERENCES stock(id) ON DELETE RESTRICT,
    quantity INT NOT NULL CHECK (quantity > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_stock_reservation_order_lot UNIQUE (order_id, stock_id)
);

CREATE INDEX idx_stock_reservation_order ON stock_reservation(order_id);

CREATE TABLE stock_daily_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_date DATE NOT NULL,
    sku VARCHAR(64) NOT NULL REFERENCES products(sku) ON UPDATE CASCADE ON DELETE RESTRICT,
    warehouse_code VARCHAR(50) NOT NULL DEFAULT 'MAIN',
    opening_stock INT NOT NULL DEFAULT 0,
    inbound_qty INT NOT NULL DEFAULT 0,
    demand_qty INT NOT NULL DEFAULT 0,
    closing_stock INT NOT NULL DEFAULT 0,
    shortage_qty INT NOT NULL DEFAULT 0 CHECK (shortage_qty >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_daily_snapshot UNIQUE (snapshot_date, sku, warehouse_code)
);

CREATE INDEX idx_snapshots_sku_date
    ON stock_daily_snapshots(sku, snapshot_date DESC);

CREATE INDEX idx_snapshots_shortage
    ON stock_daily_snapshots(snapshot_date, shortage_qty)
    WHERE shortage_qty > 0;

CREATE TABLE stock_ledger_run (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID NOT NULL UNIQUE,
    request_hash VARCHAR(64) NOT NULL,
    from_date DATE NOT NULL,
    as_of_date DATE NOT NULL,
    sku_count INT NOT NULL,
    snapshot_count INT NOT NULL,
    shortage_quantity BIGINT NOT NULL,
    current_stock_updated BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_stock_ledger_date_range CHECK (from_date <= as_of_date)
);

CREATE TABLE stock_ledger_transaction (
    transaction_id VARCHAR(100) PRIMARY KEY,
    transaction_hash VARCHAR(64) NOT NULL,
    reservation_processed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
