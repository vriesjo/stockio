# Performance test area

This directory is reserved for performance tests. No runner or workload is
implemented yet.

Before adding tests, choose a framework, target interfaces (HTTP and/or
RabbitMQ), workload/data model, duration/concurrency, and measurable
latency/throughput/error thresholds. Document infrastructure provisioning and
cleanup, and run only against dedicated test systems.

The standalone [`publisher/`](../publisher/) can generate synthetic RabbitMQ
traffic, but its fixed-rate sample generator is not a benchmark and does not
measure results or enforce performance targets. Mock-backed browser tests in
[`e2e/`](../e2e/) are functional checks, not service performance tests.
