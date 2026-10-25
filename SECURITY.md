# Security

This is an unreleased development project. Maintained release lines and a private reporting endpoint have not been established. Contact the repository owner through an existing private channel for potential duplicate IDs, stale worker ownership, dependency vulnerabilities or exposed credentials. Include the affected version, environment, impact and minimal reproduction; never publish credentials or sensitive production data.

Review the [ID contract](module/smartscity-id/README.md): worker modulo reuse and ZooKeeper session expiry impose explicit uniqueness limits. A static worker assignment or passing local tests does not establish global uniqueness.
