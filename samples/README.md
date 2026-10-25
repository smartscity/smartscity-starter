# Executable examples

All ID applications are grouped under the unpublished [samples/id aggregator](id/README.md).

Build/install production artifacts with JDK 21: `./mvnw install`. Apply the [MySQL schema](../starter/smartscity-starter-id/boot2-3/src/main/resources/db/mysql.sql) to an isolated database, then set `ID_DATABASE_URL`, `ID_DATABASE_USER` and optionally `ID_DATABASE_PASSWORD`.

- Boot 2.7 default: `./mvnw -f samples/id/id-example/pom.xml package`, then `java -jar samples/id/id-example/target/smartscity-id-example-0.1.0-SNAPSHOT.jar`.
- Boot 3: build that same sample with `-Dspring-boot.version=3.5.16` on JDK 21, then run its JAR on a compatible JDK.
- Boot 4: `./mvnw -f samples/id/id-example-boot4/pom.xml package`, then `java -jar samples/id/id-example-boot4/target/smartscity-id-example-boot4-0.1.0-SNAPSHOT.jar`.

Both executable artifacts carry the same small application source and YAML in their own standard directories. They use the matching starter and demonstrate public ID generation/parsing/readiness and context-owned shutdown. They are not published. Do not put secrets in source control. A supplied JDBC URL must refer to a database you own and prepared for the sample.

Both samples use the complete configuration from the [ID user guide](../docs/id/USER_GUIDE.md) and have their own standard source/resource trees. Sample execution for the family revision is NOT_RUN under the latest user instruction.
