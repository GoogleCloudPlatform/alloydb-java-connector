# Post-Quantum Cryptography (PQC) Support

Post-Quantum Cryptography (PQC) provides cryptographic algorithms that are
secure against future decryption threats from quantum computers. The AlloyDB
Java Connector supports securing the transport layer (TLS 1.3 mTLS connection)
using post-quantum key exchange mechanisms (such as ML-KEM / Kyber).

Depending on your target Java Runtime (JRE) version, PQC support is either
native or requires registering a secure post-quantum cryptography provider.

## 1. Native JRE Support (JDK 27+)

JEP 527 adds the hybrid post-quantum key agreement groups `X25519MLKEM768`,
`SecP256r1MLKEM768`, and `SecP384r1MLKEM1024` to the default `SunJSSE`
provider. These will ship in the upcoming JDK 27 release (GA targeted for
September 2026). Once both the client JDK and the AlloyDB server-side proxy
support it, the AlloyDB Java Connector will negotiate `X25519MLKEM768`
automatically with no additional code or dependencies.

If the named group is supported but not enabled by default in your deployment,
you can enable it explicitly via the `jdk.tls.namedGroups` system property:

```
-Djdk.tls.namedGroups=X25519MLKEM768,X25519,secp256r1
```

> **Note:** JDK 24 ships ML-KEM as a standalone cryptographic primitive via
> JEP 496, but does **not** wire it into the TLS stack. JDK 17 through JDK 26
> all require the provider-registration steps in Section 2 below to use PQC
> on TLS connections.

## 2. JREs Without Native PQC (JDK 17 through JDK 26)

On these releases, the default JRE provider (`SunJSSE`) does not support
post-quantum key exchange groups. To secure connections with PQC, you must
register the Bouncy Castle JSSE provider in your application.

> **JDK 11 and earlier are not supported for PQC.** Bouncy Castle's TLS
> ML-KEM hybrid groups rely on the `javax.crypto.KEM` API (JEP 452), which
> was backported only as far as JDK 17. Earlier runtimes will fall back to a
> classical handshake even with BCJSSE registered.

The AlloyDB Java Connector is designed with **isolated provider dynamic
selection**. When initiating a TLS connection, it checks whether the Bouncy
Castle JSSE provider (`BCJSSE`) is registered and, if so, requests an
`SSLContext` from it without altering the global JRE provider list. If
`BCJSSE` is not registered, the connector falls back to the default JRE
provider.

### Using Bouncy Castle JSSE

Bouncy Castle is a trusted, pure Java-based cryptography stack. It does not
load or rely on any native platform libraries, making it portable and
compatible with restricted serverless or containerized cloud environments (like
Google Cloud Run).

ML-KEM and ML-KEM hybrid groups in TLS were added to Bouncy Castle in the
1.80 / 1.81 release line. Pin a version at or above 1.81.

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

## How It Works Under the Hood

The connector implements dynamic runtime discovery. When initiating a secure
socket:

1. It checks whether `BCJSSE` is registered via `Security.getProvider("BCJSSE")`.
   If so, it requests `SSLContext.getInstance("TLSv1.3", "BCJSSE")` and the
   Bouncy Castle secure engine establishes the database mTLS connection.
2. Otherwise, it falls back to the JVM's default provider via
   `SSLContext.getInstance("TLSv1.3")`.

## Verifying PQC Is in Effect

When the connector selects BCJSSE, it emits a one-time INFO log at the first
connection:

```
Using Bouncy Castle JSSE provider (BCJSSE) for AlloyDB TLS connection.
```

To confirm a post-quantum group was actually negotiated on the wire, inspect
the established session. On JDK 27+ (`SunJSSE`) the named group is exposed
directly via `SSLSession`; on BCJSSE it is reported in the cipher suite /
session debug output. The simplest cross-runtime check is to enable JSSE
debug logging and look for `X25519MLKEM768` (or another ML-KEM hybrid group)
in the handshake trace:

```
-Djavax.net.debug=ssl:handshake
```

If you only see classical groups (`x25519`, `secp256r1`, etc.), PQC is not in
effect — confirm that BCJSSE is registered (or that you are on JDK 27+) and
that `jdk.tls.namedGroups` has not been narrowed to exclude the hybrid
groups.
