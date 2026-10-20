CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE products (
                          id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          sku                 VARCHAR(64) NOT NULL UNIQUE,
                          gtin_ean            VARCHAR(14) UNIQUE,
                          title               VARCHAR(255) NOT NULL,
                          description         TEXT,
                          status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED', 'DISCONTINUED')),
                          category           VARCHAR(20) NOT NULL DEFAULT 'H'
                              CHECK (category IN ('V', 'H')),
                          price_cents         BIGINT NOT NULL CHECK (price_cents >= 0),
                          currency            CHAR(3) NOT NULL DEFAULT 'EUR',
                          attributes          JSONB NOT NULL DEFAULT '{}'::jsonb, -- Bv. {"content": "180", "unit": "ml"}
                          created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
