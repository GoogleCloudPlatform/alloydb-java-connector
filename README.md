# AlloyDB Java Connector

[![CI][ci-badge]][ci-build]
[![Maven][maven-version-image]][maven-version-link]

[ci-badge]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/actions/workflows/ci.yaml/badge.svg?event=push
[ci-build]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/actions/workflows/ci.yaml?query=event%3Apush+branch%3Amain
[maven-version-image]: https://img.shields.io/maven-central/v/com.google.cloud/alloydb-jdbc-connector.svg
[maven-version-link]: https://central.sonatype.com/artifact/com.google.cloud/alloydb-jdbc-connector/

- [Product Documentation](https://cloud.google.com/alloydb/docs)

**NOTE: The Connector is currently in public preview and *may* contain breaking
changes.**

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

[socket-factory]: https://docs.oracle.com/javase/8/docs/api/javax/net/SocketFactory.html
[postgres-driver]: https://jdbc.postgresql.org/

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

### Adding the Connector as a Dependency

You'll need to add the Connector and the appropriate [Postgres Driver][pg-driver] in your
list of dependencies.

[pg-driver]: https://mvnrepository.com/artifact/org.postgresql/postgresql

#### Maven

Include the following in the project's `pom.xml`:

<!-- {x-version-update-start:alloydb-jdbc-connector:released} -->
```maven-pom
<!-- Add the connector with the latest version -->
<dependency>
  <groupId>com.google.cloud</groupId>
  <artifactId>alloydb-jdbc-connector</artifactId>
  <version>0.1.3-SNAPSHOT</version>
</dependency>
```
<!-- {x-version-update-end} -->

```maven-pom
<!-- Add the driver with the latest version -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>42.6.0</version>
</dependency>
```

#### Gradle

Include the following the project's `gradle.build`

<!-- {x-version-update-start:alloydb-jdbc-connector:released} -->
```gradle
// Add connector with the latest version
implementation group: 'com.google.cloud.alloydb', name: 'alloydb-jdbc-connector', version: '0.1.3-SNAPSHOT'
```
<!-- {x-version-update-end} -->

```gradle
// Add driver with the latest version
implementation group: 'org.postgresql', name: 'postgresql', version: '42.6.0'
```

### Configuring a Connection Pool

We recommend using [HikariCP][] for connection pooling. To use HikariCP with
the Java Connector, you will need to set the usual properties (e.g., JDBC URL,
username, password, etc) and you will need to set two Connector specific
properties:

[HikariCP]: https://github.com/brettwooldridge/HikariCP

- `socketFactory` should be set to `com.google.cloud.alloydb.SocketFactory`
- `alloydbInstanceName` should be set to the AlloyDB instance you want to
  connect to, e.g.:
```
projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>
```

Basic configuration of a data source looks like this:

``` java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class ExampleApplication {

  private HikariDataSource dataSource;

  HikariDataSource getDataSource() {
    HikariConfig config = new HikariConfig();

    // There is no need to set a host on the JDBC URL
    // since the Connector will resolve the correct IP address.
    config.setJdbcUrl("jdbc:postgresql:///postgres");
    config.setUsername(System.getenv("ALLOYDB_USER"));
    config.setPassword(System.getenv("ALLOYDB_PASS"));

    // Tell the driver to use the AlloyDB Java Connector's SocketFactory
    // when connecting to an instance/
    config.addDataSourceProperty("socketFactory",
        "com.google.cloud.alloydb.SocketFactory");
    // Tell the Java Connector which instance to connect to.
    config.addDataSourceProperty("alloydbInstanceName",
        System.getenv("ALLOYDB_INSTANCE_NAME"));

    this.dataSource = new HikariDataSource(config);
  }

  // Use DataSource as usual ...

}
```

See our [end to end test][e2e] for a full example.

See [About Pool Sizing][pool-sizing] for useful guidance on getting the best
performance from a connection pool.

[e2e]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/blob/main/alloydb-jdbc-connector/src/test/java/com/google/cloud/alloydb/ITSocketFactoryTest.java
[pool-sizing]: https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing

### Service Account Impersonation

The Java Connector supports service account impersonation with the
`alloydbTargetPrincipal` JDBC connection property. When enabled,
all API requests are made impersonating the supplied service account.
The IAM principal must have the IAM role "Service Account Token Creator"
(i.e., `roles/iam.serviceAccounts.serviceAccountTokenCreator`) on the
service account provided in the `alloydbTargetPrincipal` property.

#### Example

``` java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class ExampleApplication {

  private HikariDataSource dataSource;

  HikariDataSource getDataSource() {
    HikariConfig config = new HikariConfig();

    // There is no need to set a host on the JDBC URL
    // since the Connector will resolve the correct IP address.
    config.setJdbcUrl("jdbc:postgresql:///postgres");
    config.setUsername(System.getenv("ALLOYDB_USER"));
    config.setPassword(System.getenv("ALLOYDB_PASS"));

    // Tell the driver to use the AlloyDB Java Connector's SocketFactory
    // when connecting to an instance/
    config.addDataSourceProperty("socketFactory",
        "com.google.cloud.alloydb.SocketFactory");
    // Tell the Java Connector which instance to connect to.
    config.addDataSourceProperty("alloydbInstanceName",
        System.getenv("ALLOYDB_INSTANCE_NAME"));
    config.addDataSourceProperty("alloydbTargetPrincipal", 
        System.getenv("ALLOYDB_IMPERSONATED_USER"));

    this.dataSource = new HikariDataSource(config);
  }

  // Use DataSource as usual ...

}
```

#### Delegated Service Account Impersonation

In addition, the `alloydbDelegates` property controls impersonation delegation.
The value is a comma-separated list of service accounts containing chained
list of delegates required to grant the final access_token. If set,
the sequence of identities must have "Service Account Token Creator" capability
granted to the preceding identity. For example, if set to
`"serviceAccountB,serviceAccountC"`, the IAM principal must have the
Token Creator role on serviceAccountB. Then serviceAccountB must have the
Token Creator role on serviceAccountC. Finally, serviceAccountC must have the
Token Creator role on `alloydbTargetPrincipal`. If unset, the IAM principal
must have the Token Creator role on `alloydbTargetPrincipal`.

```java
config.addDataSourceProperty("alloydbTargetPrincipal",
    "TARGET_SERVICE_ACCOUNT");
config.addDataSourceProperty("alloydbDelegates", 
    "SERVICE_ACCOUNT_1,SERVICE_ACCOUNT_2");
```

In this example, the IAM principal impersonates SERVICE_ACCOUNT_1 which
impersonates SERVICE_ACCOUNT_2 which then impersonates the
TARGET_SERVICE_ACCOUNT.

### Admin API Service Endpoint

The Java Connector supports setting the Admin API Service Endpoint with the
`alloydbAdminServiceEndpoint` JDBC connection property. This feature is
used by applications that need to connect to a Google Cloud API other
than the GCP public API.

The `alloydbAdminServiceEndpoint` property specifies a network address that
the AlloyDB Admin API service uses to service the actual API requests,
for example `"googleapis.example.com:443"`.

If this option is not set, the connector will use the default service address
as follows:

```
DEFAULT_ENDPOINT = "alloydb.googleapis.com:443"
```

For more information, see the [underlying client library documentation](https://cloud.google.com/java/docs/reference/google-cloud-alloydb/latest/com.google.cloud.alloydb.v1beta#alloydbadminclient_1).

### Example

```java
config.addDataSourceProperty("alloydbAdminServiceEndpoint",
    "NEW_API_SERVICE_ENDPOINT");
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

We test and support at minimum, any publicly supported LTS JDK version.
Changes in supported versions will be considered a minor change, and will be
listed in the release notes.

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
