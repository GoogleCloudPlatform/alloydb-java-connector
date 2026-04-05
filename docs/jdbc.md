# Connecting to AlloyDB using JDBC

## Setup and Usage

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
  <version>1.3.0</version>
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
implementation group: 'com.google.cloud', name: 'alloydb-jdbc-connector', version: '1.3.0'
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
    config.setJdbcUrl(String.format("jdbc:postgresql:///%s", System.getenv("ALLOYDB_DB")));
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

### Automatic IAM Database Authentication

The Java Connector supports [IAM database authentication][iam-authn].

Make sure to
[configure your AlloyDB Instance to allow IAM authentication][configure-iam-authn]
and [add an IAM database user][add-iam-user]. Now, you can connect using
user or service account credentials instead of a password.
When setting up the connection, set the `alloydbEnableIAMAuth` connection property
to `true` and the `user` field should be formatted as follows:

- For an IAM user account, use the full user's email address,
e.g., `db-user@example.com`.
- For a service account, strip off the `.gserviceaccount.com` suffix, e.g.,
`my-sa@my-project.iam.gserviceaccount.com` should be `my-sa@my-project.iam`.

#### Example

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class ExampleApplication {

  private HikariDataSource dataSource;

  HikariDataSource getDataSource() {
    HikariConfig config = new HikariConfig();

    // There is no need to set a host on the JDBC URL
    // since the Connector will resolve the correct IP address.
    config.setJdbcUrl(String.format("jdbc:postgresql:///%s", System.getenv("ALLOYDB_DB")));
    config.setUsername(System.getenv("ALLOYDB_IAM_USER"));

    // Tell the driver to use the AlloyDB Java Connector's SocketFactory
    // when connecting to an instance
    config.addDataSourceProperty("socketFactory",
        "com.google.cloud.alloydb.SocketFactory");
    // Tell the Java Connector which instance to connect to.
    config.addDataSourceProperty("alloydbInstanceName",
        System.getenv("ALLOYDB_INSTANCE_NAME"));
    config.addDataSourceProperty("alloydbEnableIAMAuth", "true");

    this.dataSource = new HikariDataSource(config);
  }
  // Use DataSource as usual ...
}
```

[iam-authn]: https://cloud.google.com/alloydb/docs/manage-iam-authn
[configure-iam-authn]: https://cloud.google.com/alloydb/docs/manage-iam-authn#enable
[add-iam-user]: https://cloud.google.com/alloydb/docs/manage-iam-authn#create-user

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
    config.setJdbcUrl(String.format("jdbc:postgresql:///%s", System.getenv("ALLOYDB_DB")));
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

### Admin API

#### Service Endpoint

The Java Connector supports setting the Admin API Service Endpoint with the
`alloydbAdminServiceEndpoint` JDBC connection property. This feature is
used by applications that need to connect to a Google Cloud API other
than the GCP public API.

The `alloydbAdminServiceEndpoint` property specifies a network address that
the AlloyDB API service uses to service the actual API requests,
for example `"googleapis.example.com:443"`.

If this option is not set, the connector will use the default service address
as follows:

```
DEFAULT_ENDPOINT = "alloydb.googleapis.com:443"
```

For more information, see the [underlying client library documentation][client-docs].

[client-docs]: https://cloud.google.com/java/docs/reference/google-cloud-alloydb/latest/com.google.cloud.alloydb.v1beta#alloydbadminclient_1

##### Example

```java
config.addDataSourceProperty("alloydbAdminServiceEndpoint",
    "NEW_API_SERVICE_ENDPOINT");
```

#### Quota project

The Java Connector supports setting the project ID for quota and billing
with the `alloydbQuotaProject` property. If not specified, defaults to the
project sourced from environment.

For more information, see the [documentation][quota-project-doc].

[quota-project-doc]: https://cloud.google.com/docs/quota/set-quota-project

##### Example

```java
config.addDataSourceProperty("alloydbQuotaProject", "PROJECT_NAME");
```

### Specifying IP Type

The `alloydbIpType` argument is used to specify the type of IP to be used. 
Options are: `PRIVATE` (default) and `PUBLIC`. For example, `alloydbIpType=PUBLIC` will 
use the instance's Public IP.

#### Example

```java
config.addDataSourceProperty("alloydbIpType", "PUBLIC");
```

### SSH Tunnel

The Java Connector supports connecting to AlloyDB instances through an SSH
tunnel. This is useful when the AlloyDB instance has a private IP address that
is not directly reachable from the application's network, but is reachable from
a GCE VM or other bastion host within the VPC.

When SSH tunneling is configured, the connector opens an SSH connection to the
specified bastion host and forwards database traffic through the tunnel to
reach the AlloyDB instance's private IP address.

**Prerequisites:**

- A GCE VM or bastion host that can reach the AlloyDB instance's private IP
- An SSH private key that is authorized to connect to the bastion host
- The `org.apache.sshd:sshd-core` dependency added to your project

#### Adding the SSH Dependency

The SSH tunnel feature requires Apache MINA SSHD, which is an optional
dependency. Add it to your project:

Check the parent `pom.xml` for the tested version (property `sshd.version`).

##### Maven

```maven-pom
<dependency>
  <groupId>org.apache.sshd</groupId>
  <artifactId>sshd-core</artifactId>
  <version>${sshd.version}</version> <!-- see parent pom.xml for tested version -->
</dependency>
```

On Java versions before 15, the JVM does not include native Ed25519 support.
If your `known_hosts` file contains Ed25519 host keys or your SSH private key
uses Ed25519, you must also add the EdDSA provider:

```maven-pom
<dependency>
  <groupId>net.i2p.crypto</groupId>
  <artifactId>eddsa</artifactId>
  <version>0.3.0</version>
</dependency>
```

##### Gradle

```gradle
implementation group: 'org.apache.sshd', name: 'sshd-core', version: '2.12.1' // see pom.xml for tested version
```

On Java versions before 15, if your `known_hosts` file contains Ed25519 host
keys or your SSH private key uses Ed25519, you must also add the EdDSA provider:

```gradle
implementation group: 'net.i2p.crypto', name: 'eddsa', version: '0.3.0'
```

#### Example using JDBC Connection Properties

```java
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

public class ExampleApplication {

  private HikariDataSource dataSource;

  HikariDataSource getDataSource() {
    HikariConfig config = new HikariConfig();

    config.setJdbcUrl(String.format("jdbc:postgresql:///%s", System.getenv("ALLOYDB_DB")));
    config.setUsername(System.getenv("ALLOYDB_USER"));
    config.setPassword(System.getenv("ALLOYDB_PASS"));

    config.addDataSourceProperty("socketFactory",
        "com.google.cloud.alloydb.SocketFactory");
    config.addDataSourceProperty("alloydbInstanceName",
        System.getenv("ALLOYDB_INSTANCE_NAME"));

    // SSH tunnel configuration
    config.addDataSourceProperty("alloydbSshHost", "34.72.1.2");
    config.addDataSourceProperty("alloydbSshUser", "myuser");
    config.addDataSourceProperty("alloydbSshPrivateKeyPath", "/home/user/.ssh/id_rsa");
    // Optional: specify a known hosts file (defaults to ~/.ssh/known_hosts)
    config.addDataSourceProperty("alloydbSshKnownHostsPath", "/home/user/.ssh/known_hosts");

    this.dataSource = new HikariDataSource(config);
  }

  // Use DataSource as usual ...

}
```

#### Example using a Named Connector

```java
ConnectorConfig config = new ConnectorConfig.Builder()
    .withSshHost("34.72.1.2")
    .withSshUser("myuser")
    .withSshPrivateKeyPath("/home/user/.ssh/id_rsa")
    .withSshKnownHostsPath("/home/user/.ssh/known_hosts")
    .build();

ConnectorRegistry.register("my-connector", config);
```

#### Host Key Verification

By default, the connector verifies the bastion host's key against
`~/.ssh/known_hosts`. You can specify a custom known hosts file using the
`alloydbSshKnownHostsPath` property or `withSshKnownHostsPath()` builder
method. If no known hosts file is found, the connector will fail with an
error — host key verification is always required.

To populate your known hosts file for a GCE VM, run:

```bash
ssh-keyscan <BASTION_IP> >> ~/.ssh/known_hosts
```

## Configuration Reference

- See [Configuration Reference](configuration.md)
