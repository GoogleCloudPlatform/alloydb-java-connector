# Connector Configuration Reference

The AlloyDB Java Connector internally manages one or more Connectors. Most
applications don't need to configure these connectors. However, when an  
application needs advanced configuration or lifecycle management of the 
AlloyDB Connector, then the application may need to configure multiple 
AlloyDB Java Connectors. 

Each AlloyDB Java Connector is responsible for establishing connections to the
application's AlloyDB instances. A Connector holds a distinct Google Cloud 
credentials. When an application configures more than one connector, it can
create connections to multiple AlloyDB instances using distinct Google
Cloud credentials and IAM configuration.

## Unnamed Connectors

Most applications use unnamed connectors configured using JDBC connection 
properties. When the application establishes a new JDBC connection, the 
AlloyDB Java Connector will use an internal, unnamed connector configured with
the Google Cloud credentials that match the JDBC connection properties, creating
a new Connector if necessary. Then, the Java connector handles the lifecycle
of this unnamed connector without requiring intervention from the application.

## Named Connectors

The AlloyDB Java Connector allows applications to configure named connectors.
The application indicates that a JDBC connection should use a named connector by
setting the `alloydbNamedConnector` JDBC connection property when creating a
JDBC connection. 

An application may need to use named connectors if:

- It needs to connect to the AlloyDB API using credentials 
  other than the Application Default Credentials.
- It needs to connect to multiple AlloyDB instances using different
  credentials.
- It uses a non-standard AlloyDB API service URL.
- It needs to precisely control when connectors start and stop. 
- It needs to reset the entire connector configuration without restarting 
  the application.

### Registering and using a Named Connector

The application calls `ConnectorRegistry.register()` to register the named
connector configuration.

```java
GoogleCredentials myCredentials = GoogleCredentials.create(authToken);

ConnectorConfig config = new ConnectorConfig.Builder()
  .withTargetPrincipal("example@project.iam.googleapis.com")
  .withDelegates(Arrays.asList("delegate@project.iam.googleapis.com"))
  .withGoogleCredentials(myCredentials)
  .build();

ConnectorRegistry.register("my-connector",config);
```

Then the application tells a database connection to use a named connector by 
adding the `alloydbNamedConnector` to the JDBC connection properties by adding
`alloydbNamedConnector` to the JDBC URL:

```java
String jdbcUrl = "jdbc:postgresql:///<DATABASE_NAME>?"+
    +"alloydbInstanceName=<INSTANCE_NAME>"
    +"&alloydbNamedConnector=my-connector"
    +"&socketFactory=com.google.cloud.alloydb.SocketFactory"
    +"&user=<DB_USER>&password=<PASSWORD>";
```

Or by adding `alloydbNamedConnector` to the JDBC connection properties:

```java
HikariConfig config = new HikariConfig();

config.setJdbcUrl("jdbc:postgresql:///postgres");
config.setUsername(System.getenv("ALLOYDB_USER"));
config.setPassword(System.getenv("ALLOYDB_PASS"));

config.addDataSourceProperty("socketFactory",
    "com.google.cloud.alloydb.SocketFactory");
config.addDataSourceProperty("alloydbInstanceName",
    System.getenv("ALLOYDB_INSTANCE_NAME"));

config.addDataSourceProperty("alloydbNamedConnector","my-connector");

HikariDataSource dataSource = new HikariDataSource(config);
```

When using a named connector, the JDBC connection uses the connector 
configuration from the named connector. It ignores all connector configuration 
properties in the JDBC connection properties. See the full list of
[connector configuration properties](#connector-configuration) below.

The test [ITNamedConnectorTest.java](connector-example)
contains a working example showing how create and use a named connector.

[connector-example]: ../alloydb-jdbc-connector/src/test/java/com/google/cloud/alloydb/ITNamedConnectorTest.java

### Closing Named Connectors

The application closes a named connector by calling `ConnectorRegistry.close()`.
This stops the certificate refresh process for that connector. Subsequent 
attempts to connect using the named connector will fail. Existing open database 
connections will continue work until they are closed.

```java
ConnectorRegistry.close("my-connector");
```

### Updating a Named Connector's Configuration

The application may update the configuration of a named connector.

The application first calls `ConnectorRegistry.close()` and 
then `ConnectorRegistry.register()` with the new configuration. This creates a 
new connector with the new credentials.

Existing open database connections will continue work until they are closed 
using the old connector configuration. Subsequent attempts to connect using
the named connector will use the new configuration.

#### Example

First, register a named connector called "my-connector", and create
a database connection pool using the named connector.

```java
// Define the ConnectorConfig
GoogleCredentials c1 = GoogleCredentials.create(authToken);
ConnectorConfig config = new ConnectorConfig.Builder()
  .withTargetPrincipal("example@project.iam.googleapis.com")
  .withDelegates(Arrays.asList("delegate@project.iam.googleapis.com"))
  .withGoogleCredentials(c1)
  .build();

// Register it with the name "my-connector"
ConnectorRegistry.register("my-connector", config);
    
// Configure the database connection pool.
String jdbcUrl = "jdbc:postgresql:///<DATABASE_NAME>?"+
    +"alloydbInstanceName=<INSTANCE_NAME>"
    +"&alloydbNamedConnector=my-connector"
    +"&socketFactory=com.google.cloud.alloydb.SocketFactory"
    +"&user=<DB_USER>&password=<PASSWORD>";

HikariConfig config = new HikariConfig();
config.setJdbcUrl(jdbcURL);
config.setConnectionTimeout(10000); // 10s
HikariDataSource connectionPool = new HikariDataSource(config);
```

When the application needs to update the connector configuration, create
the updated ConnectorConfig. Then close the existing connector and register
a new connector with the same name.

```java
// Update the named connector configuration with new credentials.
GoogleCredentials c2 = GoogleCredentials.create(newAuthToken);
ConnectorConfig config2 = new ConnectorConfig.Builder()
    .withTargetPrincipal("application@project.iam.googleapis.com")
    .withGoogleCredentials(c2)
    .build();

// Replace the old connector named "my-connector" with a new connector
// using the new config.
ConnectorRegistry.close("my-connector");
ConnectorRegistry.register("my-connector", config2);
```

No updates to the database connection pool are required.
Existing open connections in the pool will continue to work until they are
closed. New connections will be established using the new configuration.

### Reset the Connector Registry

The application may shut down the ConnectorRegistry. This closes all existing
named and unnamed connectors, and stops internal background threads.

```java
ConnectorRegistry.reset();
```

After calling `ConnectorRegistry.reset()`, the next attempt to connect to a
database, or to `ConnectorRegistry.register()` will start a new connector
registry, restart the background threads, and create a new connector.

### Shutdown The Connector Registry

The application may shut down the ConnectorRegistry. This closes all existing
named and unnamed connectors, and stops internal background threads.

```java
ConnectorRegistry.shutdown();
```

After calling `ConnectorRegistry.shutdown()`, subsequent attempts to connect to
a database, or to `ConnectorRegistry.register()` will fail, 
throwing `IllegalStateException`.

## Configuring Google Credentials

By default, connectors will use the Google Application Default credentials to
connect to Google AlloyDB API. The application can set specific
Google Credentials in the connector configuration.

### Unnamed Connectors 
For unnamed connectors, the application can set the JDBC connection property
`alloydbGoogleCredentialsPath`. This should hold the path to a file containing
Google Credentials JSON. When the application first opens a database connection,
the connector will load the credentials will load from this file.

```java
HikariConfig config = new HikariConfig();

config.setJdbcUrl("jdbc:postgresql:///postgres");
config.setUsername(System.getenv("ALLOYDB_USER"));
config.setPassword(System.getenv("ALLOYDB_PASS"));

config.addDataSourceProperty("socketFactory",
    "com.google.cloud.alloydb.SocketFactory");
config.addDataSourceProperty("alloydbInstanceName",
    System.getenv("ALLOYDB_INSTANCE_NAME"));

config.addDataSourceProperty("alloydbGoogleCredentialsPath", 
    "/var/secrets/application.json");
```

### Named Connectors

For named connectors configured registered by calling
`ConnectorRegistry.register()`, there are multiple ways to supply
a `GoogleCredentials` instance to the connector:

- `withGoogleCredentialsPath(String path)` - Configure the connector to load
  the credentials from the file.
- `withGoogleCredentialsSupplier(Supplier<GoogleCredentials> s)` - Configure the
  connector to load GoogleCredentials from the supplier.
- `withGoogleCredentials(GoogleCredentials c)` - Configure the connector with
  an instance of GoogleCredentials.

Users may only set exactly one of these fields. If more than one field is set,
`ConnectorConfig.Builder.build()` will throw an IllegalStateException.

The credentials are loaded exactly once when the ConnectorConfig is
registered with `ConnectorRegistry.register()`.

## Configuration Property Reference

### Connector Configuration Properties

These properties configure the connector which loads AlloyDB instance 
configuration using the AlloyDB API. 

| JDBC Connection Property | Description                                                                                                                                                                                                         | Example |
|---------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------|
| alloydbTargetPrincipal   | The service account to impersonate when connecting to the database and database admin API.                                                                                                                          | `db-user@my-project.iam.gserviceaccount.com` |
| alloydbDelegates  | A comma-separated list of service accounts delegates. See [Delegated Service Account Impersonation](jdbc.md#delegated-service-account-impersonation)                                                                | `application@my-project.iam.gserviceaccount.com,services@my-project.iam.gserviceaccount.com` |
| alloydbAdminServiceEndpoint  | An alternate AlloyDB API endpoint.                                                                                                                                                                                  | `alloydb.googleapis.com:443` |
| alloydbGoogleCredentialsPath | A file path to a JSON file containing a GoogleCredentials oauth token.                                                                                                                                              | `/home/alice/secrets/my-credentials.json` |
| alloydbRefreshStrategy | Either `refresh_ahead` where certificates are refreshed in a background thread, or `lazy` where certificates are refreshed as needed. The `lazy` strategy is best when CPU isn't always available (e.g., Cloud Run) |

### Connection Configuration Properties

These properties configure the connection to a specific AlloyDB instance.

| JDBC Property Name |Description | Example  |
|------------------|---------------------|---------------------|
| alloydbInstanceName (required) | The AlloyDB Instance database server. |  `projects/<PROJECT>/locations/<REGION>/clusters/<CLUSTER>/instances/<INSTANCE>` |
| alloydbNamedConnector | The name of the named connector created using `ConnectorRegistry.register()` | `my-configuration` |
