CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE product_embeddings (
    sku VARCHAR(64) PRIMARY KEY REFERENCES products(sku),
    model VARCHAR(100) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding VECTOR(768) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX product_embeddings_hnsw_idx
    ON product_embeddings USING hnsw (embedding vector_cosine_ops);

CREATE TABLE product_knowledge_chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sku VARCHAR(64) NOT NULL REFERENCES products(sku),
    model VARCHAR(100) NOT NULL,
    source_url TEXT NOT NULL,
    source_title TEXT NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    embedding VECTOR(768) NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT unique_product_knowledge_source UNIQUE (sku, source_url, content_hash)
);

CREATE INDEX product_knowledge_chunks_hnsw_idx
    ON product_knowledge_chunks USING hnsw (embedding vector_cosine_ops);
