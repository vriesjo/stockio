CREATE TABLE allocation_job_tracking (
    job_id UUID PRIMARY KEY,
    request_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL
        CHECK (status IN ('PUBLISHING', 'SUBMISSION_UNKNOWN', 'ENQUEUED', 'PROCESSING', 'SUCCEEDED', 'FAILED')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
