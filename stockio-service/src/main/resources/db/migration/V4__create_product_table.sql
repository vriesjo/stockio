-- Optioneel: UUID extensie inschakelen als dit nog niet is gebeurd
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE products (
    -- Unieke identificaties
                          id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                          sku                 VARCHAR(64) NOT NULL UNIQUE,
                          gtin_ean            VARCHAR(14) UNIQUE, -- EAN / UPC streepjescode (optioneel)

    -- Basisinformatie
                          title               VARCHAR(255) NOT NULL,
                          description         TEXT,

    -- Status & Categorisering
                          status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED', 'DISCONTINUED')),
                          category           VARCHAR(20) NOT NULL DEFAULT 'H'
                              CHECK (category IN ('V', 'H')),

    -- Financiën & Voorraad
                          price_cents         BIGINT NOT NULL CHECK (price_cents >= 0), -- Altijd opslaan in centen/kleinste eenheid
                          currency            CHAR(3) NOT NULL DEFAULT 'EUR',

    -- Flexibele & variabele eigenschappen
                          attributes          JSONB NOT NULL DEFAULT '{}'::jsonb, -- Bv. {"content": "180", "unit": "ml"}

    -- Audit trails / Metadata
                          created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
