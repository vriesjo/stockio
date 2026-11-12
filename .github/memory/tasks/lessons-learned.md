# Lessons learned

- ...
- When removing the Spring Boot plugin from a Kotlin library module, explicitly align the imported Boot BOM's `kotlin.version` with the Kotlin plugin (`kotlin.coreLibrariesVersion`). Otherwise dependency management can downgrade compiler/build-tools transitives and fail classpath snapshotting before Kotlin compilation starts.
- Use a dedicated `RuntimeException`, rather than `IllegalStateException`, for repository coordination signals that callers must catch unchanged. Spring persistence translation can wrap `IllegalStateException` in `InvalidDataAccessApiUsageException`, hiding the intended retry signal.
- Spring Boot 3.5 skips `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` bean exists. JDBC, JPA, Flyway, and JobRunr then need an explicit Hikari `DataSource`. With both drivers, mark the JPA `transactionManager` `@Primary`; `@Transactional` looks up `TransactionManager` by type and fails when the R2DBC manager is also present.
- Spring Boot 3.5 skips `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` bean exists. JDBC, JPA, Flyway, and JobRunr then need an explicit Hikari `DataSource`. With both drivers, mark the JPA `transactionManager` `@Primary`; `@Transactional` looks up `TransactionManager` by type and fails when the R2DBC manager is also present.
