# AlloyDB Java Connector

[![CI][ci-badge]][ci-build]

[ci-badge]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/actions/workflows/ci.yml/badge.svg?event=push
[ci-build]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/actions/workflows/ci.yml?query=event%3Apush+branch%3Amain

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

```maven-pom
<!-- Add the connector with the latest version -->
<dependency>
  <groupId>com.google.cloud.alloydb</groupId>
  <artifactId>alloydb-jdbc-connector</artifactId>
  <version>0.1.0</version>
</dependency>

<!-- Add the driver with the latest version -->
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
  <version>42.6.0</version>
</dependency>
```

#### Gradle

Include the following the project's `gradle.build`

```gradle
# Add connector with the latest version
compile('com.google.cloud.alloydb:alloydb-jdbc-connector:0.1.0')
# Add driver with the latest version
compile('org.postgresql:postgresql:42.6.0')
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

[e2e]: https://github.com/GoogleCloudPlatform/alloydb-java-connector/blob/main/alloydb-jdbc-connector/src/test/java/com/google/cloud/alloydb/ITSocketFactoryTest.java

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

Please note that this project is released with a Contributor Code of Conduct. By participating in
this project you agree to abide by its terms. See [Code of Conduct][code-of-conduct] for more
information.

## License

Apache 2.0 - See [LICENSE][license] for more information.

## Notice

Java is a registered trademark of Oracle and/or its affiliates.
