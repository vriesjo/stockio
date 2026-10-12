CREATE TABLE stock (
                       id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Reference to product catalog
                       sku                 VARCHAR(64) NOT NULL REFERENCES products(sku) ON UPDATE CASCADE ON DELETE RESTRICT,

    -- Physical location in warehouse
                       warehouse_code      VARCHAR(50) NOT NULL DEFAULT 'MAIN',

    -- Inventory Counts
                       quantity_on_hand    INT NOT NULL DEFAULT 0 CHECK (quantity_on_hand >= 0),
                       quantity_reserved   INT NOT NULL DEFAULT 0 CHECK (quantity_reserved >= 0),

    -- Calculated available stock: On Hand minus Reserved
                       quantity_available  INT GENERATED ALWAYS AS (quantity_on_hand - quantity_reserved) STORED,

    -- Audit Timestamps
                       created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Ensure available quantity never drops below zero
                       CONSTRAINT chk_reserved_lte_on_hand CHECK (quantity_reserved <= quantity_on_hand)
);

-- Indexes for performance
CREATE INDEX idx_stock_sku ON stock(sku);
CREATE INDEX idx_stock_warehouse ON stock(warehouse_code);
