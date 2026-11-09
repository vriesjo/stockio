UPDATE allocation_job_tracking
SET status = 'PROCESSING'
WHERE status IN ('PUBLISHING', 'SUBMISSION_UNKNOWN', 'ENQUEUED');

ALTER TABLE allocation_job_tracking DROP CONSTRAINT allocation_job_tracking_status_check;

ALTER TABLE allocation_job_tracking
    ADD CONSTRAINT allocation_job_tracking_status_check
        CHECK (status IN ('PROCESSING', 'SUCCEEDED', 'FAILED'));
