# AGENTS.md

Instructions for coding agents working in this repository. Human-facing docs live in `README.md`, `CONTRIBUTING.md`, `docs/jdbc.md`, and `docs/configuration.md`.

## Project overview

The AlloyDB Java Connector (`com.google.cloud:alloydb-jdbc-connector`) is a JDBC `SocketFactory` for the PostgreSQL driver. It opens an mTLS 1.3 connection to an AlloyDB instance using IAM credentials. Callers do not configure TLS certificates.

- Library code is in `alloydb-jdbc-connector/`. Package: `com.google.cloud.alloydb`.
- `SocketFactory` is the JDBC entry point. It delegates to `InternalConnectorRegistry`.
- `ConnectorRegistry` and `ConnectorConfig` are the supported way to register a named connector.
- `samples/` are standalone examples. They are not a module of the parent build.
- Versions are owned by release-please (`java-yoshi`). Do not edit version numbers, `{x-version-update:...}` markers, `Version.java`, or `CHANGELOG.md` by hand.

## Setup

- Use the repo Maven wrapper (`./mvnw`) from the repository root. Do not require a preinstalled `mvn`.
- JDK 8 is the language level. CI also builds on 11, 17, 21, and 25. Do not use APIs or syntax newer than Java 8 (`var`, records, text blocks, `List.of`, `Map.of`).
- Do not add a system-wide Maven install step. `./mvnw` downloads the pinned Maven.

## Build and test

Run these from the repository root:

```bash
./mvnw -B -ntp -DskipTests -Dclirr.skip=true -Denforcer.skip=true -Dmaven.javadoc.skip=true install
scripts/format.sh          # google-java-format
scripts/lint.sh            # fmt check; must pass before a change is done
scripts/test_units.sh      # unit tests plus 75% JaCoCo line coverage
scripts/check_dependencies.sh
scripts/check_clirr.sh     # binary compatibility of the public API
```

For a single unit test:

```bash
./mvnw -B -ntp -Dclirr.skip=true -Denforcer.skip=true -Dtest=ConnectorConfigTest test
```

- Fix formatter, compiler, Error Prone, and unit-test failures before finishing.
- Error Prone runs on JDK 21+ with `-Werror` behavior (`failOnWarning`). A clean JDK 8 compile is not enough.
- Do not run integration tests unless the environment below is already configured. They need a live AlloyDB instance.

Test commands:

```bash
./scripts/test_units.sh                      # unit tests; no AlloyDB instance needed
./scripts/test_system.sh --skip-private-ip   # integration tests over public IP; VPC-only tests are skipped
./scripts/test_system.sh                     # full integration suite; needs VPC access to the instance
```

Integration tests (`scripts/test_system.sh`, Maven profile `enable-integration-tests`) need Application Default Credentials (`gcloud auth application-default login`) and the variables in `.envrc.example`:

- `ALLOYDB_DB`, `ALLOYDB_USER`, `ALLOYDB_PASS`
- `ALLOYDB_INSTANCE_NAME` (must have public IP enabled)
- `ALLOYDB_IAM_USER`, `ALLOYDB_IMPERSONATED_USER`
- `ALLOYDB_INSTANCE_IP`, `ALLOYDB_PSC_INSTANCE_URI` (full suite only; not needed with `--skip-private-ip`)

## Code style

- Format with `scripts/format.sh`. The check is `com.spotify.fmt:fmt-maven-plugin` (Google Java Style). Do not hand-format or introduce a second formatter.
- Every new Java, shell, XML, and YAML source file needs the Apache 2.0 header used by neighboring files (`Copyright <year> Google LLC`).
- Match existing types: immutable value objects, private constructors, and `Builder` with `with*` methods. Use `com.google.common.base.Objects` for `equals` and `hashCode` where the surrounding class does.
- Tests use JUnit 4 and Truth (`assertThat`). Do not add JUnit 5 or another assertion library.
- Log with SLF4J. Do not use `System.out` or `java.util.logging`.
- Keep the PostgreSQL JDBC driver `provided`. Applications supply it. Do not move it to `compile`.
- Prefer small package-private helpers over new public types. `InternalConnectorRegistry` is the internal facade; do not grow the public surface to reach it.

## Testing

- Unit tests sit next to the class they cover and are named `*Test`. They must not contact AlloyDB, the metadata server, or the network.
- Integration tests are named `IT*`. They run only under `-Penable-integration-tests`.
- Integration tests that connect to the instance use `alloydbIpType=PUBLIC` (or `IpType.PUBLIC`) so they run from outside the VPC.
- A new `IT*` test that needs VPC access (private IP, PSC, or a direct connection) must call `PrivateIpAssumptions.assumePrivateIpAvailable()` at the start of `@Before`, so `--skip-private-ip` skips it.
- Add or update a unit test for every behavior change, including bug fixes.
- `scripts/test_units.sh` fails when JaCoCo line coverage drops below 75%. Do not lower that threshold.
- Native-image code under `com.google.cloud.alloydb.nativeimage` is excluded from the coverage run. Do not "fix" coverage by deleting that exclude.

## Public API and compatibility

Clirr checks only these types. Treat them as stable binary API:

- `com.google.cloud.alloydb.ConnectorConfig`
- `com.google.cloud.alloydb.ConnectorRegistry`
- `com.google.cloud.alloydb.SocketFactory`

Do not remove methods, change signatures, or narrow visibility on those types in a non-major change. Run `scripts/check_clirr.sh` after any edit to them.

JDBC property names and `ConnectorConfig.Builder` methods are user-facing. Document new ones in `docs/configuration.md` and `docs/jdbc.md`.

## Security

- Do not commit credentials, `.envrc`, service-account JSON, or real instance URIs. `.envrc.example` stays placeholder-only.
- Do not log access tokens, passwords, private keys, or certificate private material.
- Do not weaken TLS, disable certificate checks, or accept plaintext connections.

## Pull requests

- All changes go through a GitHub pull request. A Contributor License Agreement is required (https://cla.developers.google.com/).
- Describe the user-visible behavior change. Link the issue when there is one.
- Do not commit generated `target/` output or Maven wrapper downloads.
