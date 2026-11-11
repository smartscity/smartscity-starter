# Observability compatibility

| Artifact | Target | Bytecode | Evidence |
|---|---|---|---|
| smartscity-observability | Standalone pure policies | Java 9 | Core contract tests; broader JVM matrix not run |
| smartscity-starter-observability | Boot 2.7.18 and 3.5.16 | Java 9 | Independent consumer tests use the same JAR |
| smartscity-starter-observability-boot4 | Boot 4.0.9 | Java 17 | Independent Boot 4 consumer |

Build and executed verification use JDK 21 and the pinned wrapper. Bytecode is not proof of compatibility on all newer/older JVMs. See [testing](testing.md) for the final evidence state.

Both Servlet APIs are compile-time optional in the Boot 2.7/3 artifact. The application supplies exactly its own API. Framework-sensitive HTTP response methods are forwarded through an integration-layer proxy so Spring 5/6 return-type changes do not leak into the domain. Boot 4 has only the Jakarta adapter. External tracer access uses public provider APIs in the integration layer; domain classes use no reflection.

WebFlux, WebClient, Feign, database/Redis tracing, messaging adapters, native images, JPMS and alternate logging backends are not claimed supported. Jackson 2 is an optional sanitizer implementation even when a Boot 4 application independently uses Jackson 3. Existing framework-provided instrumentation owns its own propagation policies.

Provider adapter tests use the real profile-managed Brave and Micrometer bridge under Boot 3/4. Boot 2.7 consumer tests exercise standalone correlation; Sleuth combinations are NOT_RUN. statsd_exporter 0.28.0 on Darwin/amd64 is the executed collector target.
