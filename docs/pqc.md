# Post-Quantum Cryptography (PQC) Support

Post-Quantum Cryptography (PQC) provides cryptographic algorithms that are
secure against future decryption threats from quantum computers. The AlloyDB
Java Connector supports securing the transport layer (TLS 1.3 mTLS connection)
using post-quantum key exchange mechanisms (such as ML-KEM / Kyber).

Depending on your target Java Runtime (JRE) version, PQC support is either
native or requires registering a secure post-quantum cryptography provider.

> **Post-quantum key exchange is opt-in before JDK 27, and never breaks a
> connection.** When it is unavailable the connector completes a classical
> TLS 1.3 handshake instead. Read
> [Verifying PQC Is in Effect](#verifying-pqc-is-in-effect) before assuming a
> deployment is protected, and see
> [Failing closed](#failing-closed) if a silent fallback is unacceptable.

## 1. Native JRE Support (JDK 27+)

[JEP 527][jep-527] adds the hybrid post-quantum key agreement groups
`X25519MLKEM768`, `SecP256r1MLKEM768`, and `SecP384r1MLKEM1024` to the default
`SunJSSE` provider. It is delivered in JDK 27. Only `X25519MLKEM768` is
enabled by default; the other two must be enabled explicitly. Once both the
client JDK and the AlloyDB server-side proxy support it, the AlloyDB Java
Connector will negotiate `X25519MLKEM768` automatically with no additional
code or dependencies.

To change which groups are offered, use the `jdk.tls.namedGroups` system
property:

```
-Djdk.tls.namedGroups=X25519MLKEM768,X25519,secp256r1
```

> **Note:** JDK 24 ships ML-KEM as a standalone cryptographic primitive via
> [JEP 496][jep-496], but does **not** wire it into the TLS stack. JDK 17
> through JDK 26 all require the provider-registration steps in Section 2
> below to use PQC on TLS connections.

## 2. JREs Without Native PQC (JDK 17 through JDK 26)

On these releases, the default JRE provider (`SunJSSE`) does not support
post-quantum key exchange groups. To secure connections with PQC you must do
two things: register the Bouncy Castle JSSE provider in your application, and
set the `alloydbTlsProvider` connection property so the connector uses it.

> **JDK 17 is the floor.** The connector supports Java 8 and Java 11, but
> neither can negotiate ML-KEM: Bouncy Castle reaches it through the
> `javax.crypto.KEM` API, which was never backported that far. On those
> runtimes the steps below still move AlloyDB connections onto Bouncy Castle,
> but the handshake stays classical and nothing reports that it did. See
> [Version requirements](#version-requirements) for the JDK 17 details.

The AlloyDB Java Connector is designed with **isolated provider selection**.
When `alloydbTlsProvider` asks for Bouncy Castle, the connector requests an
`SSLContext` from the registered `BCJSSE` provider for its own connections
only, without altering the global JRE provider list or the TLS behavior of
anything else in the application.

Selection is opt-in rather than automatic. Registering `BCJSSE` alone does not
change how the connector connects, so an application that registers Bouncy
Castle for unrelated reasons keeps its AlloyDB connections on the JRE's TLS
stack until it says otherwise.

### Using Bouncy Castle JSSE

Bouncy Castle is a trusted, pure Java-based cryptography stack. It does not
load or rely on any native platform libraries, making it portable and
compatible with restricted serverless or containerized cloud environments (like
Google Cloud Run).

#### Version requirements

| Runtime | Minimum Bouncy Castle | Notes |
| --- | --- | --- |
| JDK 21 and later | 1.81 | ML-KEM hybrid groups in TLS landed in the 1.80 / 1.81 line. |
| JDK 17 | 1.84 | 1.84 is the release that reaches ML-KEM through the KEM API on Java 17, matching Oracle's backport of that API to JDK 17. |

Bouncy Castle reaches ML-KEM through the `javax.crypto.KEM` API
([JEP 452][jep-452]), which shipped in JDK 21 and was later backported to JDK
17 update releases. On JDK 17 you therefore need **both** Bouncy Castle 1.84 or
newer **and** a JDK 17 update that contains the KEM API backport. An older JDK
17 update, or Bouncy Castle 1.81 through 1.83 on JDK 17, falls back to a
classical handshake without reporting an error.

> **JDK 11 and earlier cannot negotiate ML-KEM**, because the KEM API
> ([JEP 452][jep-452]) was never backported that far. The connector still
> supports those runtimes for everything else; they simply get a classical
> handshake.

#### 1. Add Dependencies

Add Bouncy Castle Cryptography and TLS providers to your project dependencies:

**Maven (`pom.xml`):**

```xml
<dependency>
  <groupId>org.bouncycastle</groupId>
  <artifactId>bcprov-jdk18on</artifactId>
  <version>1.84</version>
</dependency>
<dependency>
  <groupId>org.bouncycastle</groupId>
  <artifactId>bctls-jdk18on</artifactId>
  <version>1.84</version>
</dependency>
```

Use 1.84 or later; the versions above are the minimum, not a pin.

**Gradle (`build.gradle`):**
```groovy
implementation group: 'org.bouncycastle', name: 'bcprov-jdk18on', version: '1.84'
implementation group: 'org.bouncycastle', name: 'bctls-jdk18on', version: '1.84'
```

#### 2. Register Security Providers

Register both the JCE (`BouncyCastleProvider`) and JSSE
(`BouncyCastleJsseProvider`) layers early during application startup, and
pass the BC JCE provider explicitly to the JSSE constructor:

```java
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import java.security.Security;

public class ExampleApplication {
    public static void main(String[] args) {
        BouncyCastleProvider bcProvider = new BouncyCastleProvider();
        // Appends to providers list, preserving default JVM socket behavior for standard web traffic.
        // Passing bcProvider into the JSSE constructor avoids BCJSSE picking up SunJCE's ML-KEM
        // implementation (which it disables) on JDK 24+. See bc-java issue #2252.
        Security.addProvider(bcProvider);
        Security.addProvider(new BouncyCastleJsseProvider(bcProvider));

        // Startup the application...
    }
}
```

Omitting the `bcProvider` argument is the most common reason a correctly
configured deployment still negotiates a classical group.

#### 3. Point the Connector at the Provider

Registering `BCJSSE` does not by itself change how the connector connects. Set
the `alloydbTlsProvider` connection property as well:

```java
config.addDataSourceProperty("alloydbTlsProvider", "BOUNCY_CASTLE");
```

| Value | Behavior |
| --- | --- |
| `JDK` (default) | Always use the JRE default provider, even when BCJSSE is registered. |
| `AUTO` | Use BCJSSE when it is registered, otherwise the JRE default provider. |
| `BOUNCY_CASTLE` | Require BCJSSE. Fail the connection instead of falling back. |

`BOUNCY_CASTLE` is the value to prefer for a deployment that intends to use
PQC: a missing or unusable provider fails every connection attempt with an
`IllegalStateException` naming the property, rather than completing a quiet
classical handshake. The failure surfaces on the first connection rather than
at startup, so register the provider before the pool opens one. `AUTO` suits an
artifact that has to run both with and without Bouncy Castle on the classpath,
and logs once when it does not find the provider.

The default is `JDK` rather than `AUTO` so that registering Bouncy Castle for
some other part of an application -- another library, a FIPS requirement --
never silently moves AlloyDB traffic onto a different TLS implementation. The
choice of TLS stack is always the application's to make.

### Failing closed

`JDK` and `AUTO` never fail a connection over key exchange: if post-quantum
groups are unavailable, the handshake proceeds with classical cryptography.
`BOUNCY_CASTLE` closes the largest gap by refusing to connect when BCJSSE is
missing or unusable, with an error naming the property.

It does not guarantee that a post-quantum group was negotiated. There is no
JSSE API for the negotiated key exchange group yet (see [JDK-8388519][jdk-api]),
so neither the connector nor your application can assert on it
programmatically. If the server does not offer a hybrid group, the handshake
still succeeds with a classical one.

## How It Works Under the Hood

When initiating a secure socket, the connector reads `alloydbTlsProvider`:

1. `JDK` requests `SSLContext.getInstance("TLSv1.3")` from the JVM's default
   provider.
2. `AUTO` and `BOUNCY_CASTLE` request
   `SSLContext.getInstance("TLSv1.3", "BCJSSE")`, so the Bouncy Castle secure
   engine establishes the database mTLS connection. `AUTO` falls back to the
   default provider when `BCJSSE` is absent, logging once that it did;
   `BOUNCY_CASTLE` raises an `IllegalStateException` instead.

The JCA registry is consulted on every connection, so an application that
registers BCJSSE after its first connection is picked up on the next one, and
one that unregisters it falls back on the next one.

Key manager, trust manager and hostname verification are unchanged by the
provider switch: the connector builds them from the default provider and
relies on `setEndpointIdentificationAlgorithm("HTTPS")` in both cases.
`ConnectionSocketBouncyCastleTest` covers that combination with a real
handshake.

### The extra round trip

Bouncy Castle offers `X25519MLKEM768` in the ClientHello's `supported_groups`
list, but sends a key share only for a classical group (`x25519`). A server
that prefers the hybrid group therefore answers with a HelloRetryRequest, and
the connector sends a second ClientHello carrying the 1216-byte ML-KEM key
share:

```
ClientHello #1   340 bytes   key_share: x25519
   <- HelloRetryRequest
ClientHello #2  1524 bytes   key_share: X25519MLKEM768
```

Each connection that negotiates a post-quantum group under BCJSSE costs one
extra round trip. Connection pools amortize it, but it is measurable on a
cold pool and it is the expected behavior, not a misconfiguration. The native
JDK 27+ path does not pay it: `SunJSSE` sends the hybrid key share in the
first ClientHello.

### Certificate errors under BCJSSE

Endpoint identification works under BCJSSE, but it reports failures
confusingly. The JRE trust manager checks the SNI name the connector sets
(the instance address) and, when that check fails, falls back to the socket's
peer host -- which BCJSSE leaves null. A server certificate that does not
match the address therefore surfaces as:

```
java.security.cert.CertificateException: Hostname or IP address is undefined.
```

rather than as a name mismatch. The connection fails closed either way, and
under the JRE default provider the same certificate produces the clearer
message. Read this error as "the server's certificate did not match the
address being connected to," and check that error against the JRE default
provider (`alloydbTlsProvider=JDK`) before investigating further.

## Verifying PQC Is in Effect

When the connector selects BCJSSE, it logs the following once, at INFO, on the
first connection that uses it:

```
Using the Bouncy Castle JSSE provider (BCJSSE) for AlloyDB TLS connections.
```

When `alloydbTlsProvider` is `AUTO` and `BCJSSE` is not registered, it instead
logs once, at INFO, that the connection is falling back:

```
alloydbTlsProvider is set to AUTO, but the Bouncy Castle JSSE provider
(BCJSSE) is not registered. Using the default JSSE provider, which means
AlloyDB connections will not use post-quantum key exchange.
```

At DEBUG the connector also logs the provider it selected for each instance,
and the negotiated protocol and cipher suite for each connection. None of these
report the key exchange group, which is the thing that makes a handshake
post-quantum.

**Neither does BCJSSE's own logging.** Bouncy Castle logs through
`java.util.logging` rather than `javax.net.debug`, but even at `ALL` on
`org.bouncycastle` it names only the selected protocol version and cipher
suite, plus the *names* of the ClientHello extensions:

```
FINEST: ClientHello extensions: ... supported_groups(10) key_share(51) ...
FINE:   notified of selected cipher suite: TLS_CHACHA20_POLY1305_SHA256
```

There is no log line, and no JSSE API (see [JDK-8388519][jdk-api]), that
reports the negotiated group under BCJSSE. Confirming it means looking at the
wire.

**JDK 27+ using the default `SunJSSE` provider** is the exception -- it names
the group in its handshake trace:

```
-Djavax.net.debug=ssl:handshake
```

**Under BCJSSE**, capture the handshake and read the `key_share` extension of
the ServerHello, which TLS 1.3 sends in the clear:

```
tcpdump -i any -s0 -w handshake.pcap 'tcp port 5433'
```

Open the capture in Wireshark and select the ServerHello; its `key_share`
extension names the group the server chose. A shortcut that needs no
dissector: a post-quantum handshake under BCJSSE always shows **two**
ClientHello records, the second roughly 1.5 KB, because of the
HelloRetryRequest described in [The extra round trip](#the-extra-round-trip).
A single small ClientHello means a classical group was agreed.

If the group is classical (`x25519`, `secp256r1`, etc.), check, in order:

1. `alloydbTlsProvider` is set to `BOUNCY_CASTLE` (or to `AUTO` with `BCJSSE`
   registered). It defaults to `JDK`, which never uses Bouncy Castle. On
   JDK 27+ the default is what you want and no property is needed.
2. `BCJSSE` is registered, and the `BouncyCastleProvider` was passed to the
   `BouncyCastleJsseProvider` constructor.
3. Your Bouncy Castle and JDK versions meet the table above.
4. `jdk.tls.namedGroups` has not been narrowed to exclude the hybrid groups.
   Do not try to *force* the hybrid group by narrowing it either -- see below.
5. The server offers a hybrid group. Both peers must support one.

> **Do not narrow `jdk.tls.namedGroups` under BCJSSE.** It is tempting to list
> `X25519MLKEM768` first to skip the HelloRetryRequest, but Bouncy Castle also
> derives the offered ECDSA signature schemes from the enabled groups. Dropping
> `secp256r1` from the list stops the client from offering
> `ecdsa_secp256r1_sha256`, and a server presenting an ECDSA P-256 certificate
> then fails the handshake outright:
>
> ```
> tls: peer doesn't support any of the certificate's signature algorithms
> ```
>
> Leave the property unset and let the HelloRetryRequest do its job.

[jep-527]: https://openjdk.org/jeps/527
[jep-496]: https://openjdk.org/jeps/496
[jep-452]: https://openjdk.org/jeps/452
[jdk-api]: https://bugs.openjdk.org/browse/JDK-8388519
