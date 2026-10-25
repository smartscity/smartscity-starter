# Release process

No artifact has been published by this task. Publishing requires a separately authorized release; the workflow is prepared, not remotely executed.

## Preparation

1. Validate the current compatibility/evidence matrix and migration guide. Resolve version-specific limitations explicitly; a successful build does not validate unexecuted databases/JDKs.
2. Set one non-SNAPSHOT version consistently in the root, parent, BOM and every child parent declaration. Run `python3 build/check-release.py <version>`.
3. Verify the repository URL/SCM metadata in the parent and BOM matches the real repository. The configured URL is the intended Smartscity project identity; this checkout has no configured Git remote. Confirm ownership of the `com.smartscity` Central namespace.
4. Configure a protected GitHub environment named `maven-central`, repository variable `MAVEN_CENTRAL_ENABLED=true`, and environment secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `MAVEN_GPG_KEY` and `MAVEN_GPG_PASSPHRASE`. Use a Central user token and an authorized signing key. The setup-java action writes server credentials via environment references; no secrets belong in source control.
5. Complete CI and review its database/runtime evidence before manually dispatching `Stage Maven Central release` with the committed version.

The release profile attaches source and Javadoc JARs from each module's standard source tree, signs using Maven GPG, and uses Central Publishing 0.12.0. `autoPublish=false` uploads for validation and requires a separate manual release in Central. Only the published build parent, production BOM, SDK and two combined starters are selected. The starter family aggregator is excluded and keeps deploy skipped even in the release profile. Root/test/sample/benchmark projects are not published. Normal builds skip deploy and never activate signing/upload tooling.

Source/Javadoc and the Central bundle can be rehearsed locally without upload using `-Prelease -Dgpg.skip=true -DskipTests -DskipPublishing=true verify` on selected production projects. Such a rehearsal does not prove signing, credentials, namespace ownership, Central validation or remote publication. Final signing/staging remains NOT_RUN until explicitly executed.

The public BOM contains only Smartscity production versions. Dependency convergence uses each build module's selected Boot BOM internally; applications retain their own external dependency management. Reproducible JARs use a fixed `project.build.outputTimestamp`, pinned plugins and Maven distribution checksum; byte-identical artifacts must be checked on repeated builds before making a reproducibility claim.

Reference: [Sonatype's Maven publishing documentation](https://central.sonatype.org/publish/publish-portal-maven/).
