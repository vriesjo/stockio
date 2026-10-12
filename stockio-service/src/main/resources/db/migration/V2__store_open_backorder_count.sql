ALTER TABLE allocation_run
    ADD COLUMN open_backorder_count INTEGER NOT NULL DEFAULT 0;
