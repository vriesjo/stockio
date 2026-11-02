# Lessons learned

- ...
- When removing the Spring Boot plugin from a Kotlin library module, explicitly align the imported Boot BOM's `kotlin.version` with the Kotlin plugin (`kotlin.coreLibrariesVersion`). Otherwise dependency management can downgrade compiler/build-tools transitives and fail classpath snapshotting before Kotlin compilation starts.
