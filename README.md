# AlloyDB Java Connector

[![CI][ci-badge]][ci-build]
[![Maven][maven-version-image]][maven-version-link]

[ci-badge]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/actions/workflows/ci.yaml/badge.svg?event=push
[ci-build]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/actions/workflows/ci.yaml?query=event%3Apush+branch%3Amain
[maven-version-image]: https://img.shields.io/maven-central/v/com.google.cloud/alloydb-jdbc-connector.svg
[maven-version-link]: https://central.sonatype.com/artifact/com.google.cloud/alloydb-jdbc-connector/

- [Product Documentation](https://cloud.google.com/alloydb/docs)

The _AlloyDB Java Connector_ is a Java library for connecting securely to your
AlloyDB instances. Using a Connector provides the following benefits:

* **IAM Authorization:** The Connector uses IAM to ensure only principals with valid
  permissions are allowed to connect.
* **Improved Security:** The Connector uses TLS 1.3 encryption and
  identity verification between the client connector and the server-side proxy,
  independent of the database protocol.
* **Convenience:** The Connector removes the requirement to use and distribute SSL
  certificates.

## Usage

This library provides a [socket factory][socket-factory] for use with the
[JDBC Postgres Driver][postgres-driver]. At a high level, you will need to:

1. Configure IAM permissions
1. Add the Connector and Postgres driver as dependencies
1. Configure a connection pool that configures the driver to use the Connector
   as a socket factory

For information on configuring a connection, see the [documentation][jdbc-doc].

If you're using Spring Boot, consider using the [Spring Boot AlloyDB starter][spring-boot].

[socket-factory]: https://docs.oracle.com/javase/8/docs/api/javax/net/SocketFactory.html
[postgres-driver]: https://jdbc.postgresql.org/
[jdbc-doc]: docs/jdbc.md
[spring-boot]: https://googlecloudplatform.github.io/spring-cloud-gcp/5.3.0/reference/html/index.html#alloydb

### Configuring IAM permissions

The Java Connector uses [Application Default Credentials (ADC)][adc]. For
information on how to configure Application Default Credentials, see the
[documentation][set-adc].

In addition, the associated IAM principal must have the IAM role "Cloud AlloyDB
Client" (i.e., `roles/alloydb.client`). See the [docs on AlloyDB IAM permissions][iam-docs]
for more information.

[adc]: https://cloud.google.com/docs/authentication/application-default-credentials
[set-adc]: https://cloud.google.com/docs/authentication/provide-credentials-adc
[iam-docs]: https://cloud.google.com/alloydb/docs/reference/iam-roles-permissions#roles

### Debug Logging

The Java Connector supports optional debug logging to help diagnose problems with
the background certificate refresh. To enable it, add the following to the file
`src/main/resources/application.yml`:

```
logging.level.com.google.cloud.alloydb=DEBUG
```

## Support policy

### Major version lifecycle

This project uses [semantic versioning](https://semver.org/), and uses the
following lifecycle regarding support for a major version:

**Active** - Active versions get all new features and security fixes (that
would not otherwise introduce a breaking change). New major versions are
guaranteed to be "active" for a minimum of 1 year.

**Deprecated** - Deprecated versions continue to receive security and critical
bug fixes, but do not receive new features. Deprecated versions will be
supported for 1 year.

**Unsupported** - Any major version that has been deprecated for >=1 year is
considered unsupported.

### Supported JDK versions

> Cloud Client Libraries for Java are compatible with, and tested against
> Java 8, Java 11, Java 17, and Java 21. Java 8 will continue to be supported
> by Cloud Client Libraries for Java until at least September 2026.

See [Supported Java Versions](https://cloud.google.com/java/docs/supported-java-versions).

### Release cadence

This project aims for a minimum monthly release cadence. If no new
features or fixes have been added, a new PATCH version with the latest
dependencies is released.

## Versioning

This library follows [Semantic Versioning](http://semver.org/).

## Contributing

Contributions to this library are always welcome and highly encouraged.

See [CONTRIBUTING][contributing] for more information how to get started.

[contributing]: CONTRIBUTING.md

Please note that this project is released with a Contributor Code of Conduct. By participating in
this project you agree to abide by its terms. See [Code of Conduct][code-of-conduct] for more
information.

[code-of-conduct]: CODE_OF_CONDUCT.md

## License

Apache 2.0 - See [LICENSE][license] for more information.

[license]: LICENSE

## Notice

Java is a registered trademark of Oracle and/or its affiliates.
