# Post-Quantum Cryptography (PQC) Support

Post-Quantum Cryptography (PQC) provides cryptographic algorithms that are
secure against future decryption threats from quantum computers. The AlloyDB
Java Connector supports securing the transport layer (TLS 1.3 mTLS connection)
using post-quantum key exchange mechanisms (such as ML-KEM / Kyber).

Depending on your target Java Runtime (JRE) version, PQC support is either
native or requires selecting a JSSE provider that offers it.

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
code or configuration.

To change which groups are offered, use the `jdk.tls.namedGroups` system
property:

```
-Djdk.tls.namedGroups=X25519MLKEM768,X25519,secp256r1
```

> **Note:** JDK 24 ships ML-KEM as a standalone cryptographic primitive via
> [JEP 496][jep-496], but does **not** wire it into the TLS stack. Java 8
> through JDK 26 all require the step in Section 2 below to use PQC on TLS
> connections.

## 2. JREs Without Native PQC (Java 8 through JDK 26)

On these releases the default JRE provider (`SunJSSE`) does not support
post-quantum key exchange groups. The connector can instead serve its
connections from [Conscrypt][conscrypt], a JSSE provider backed by BoringSSL,
which offers `X25519MLKEM768` on every Java version this connector supports.

**There is nothing to add to your build.** Conscrypt is already a dependency of
the AlloyDB Java Connector. There are no providers to register and no
application startup code to write. Set one connection property:

```java
config.addDataSourceProperty("alloydbTlsProvider", "CONSCRYPT");
```

| Value | Behavior |
| --- | --- |
| `JDK` (default) | Always use the JRE's default provider. On JDK 27+ this is already post-quantum; before that it is not. |
| `AUTO` | Get post-quantum key exchange wherever it is available: the JRE's own provider on JDK 27+, otherwise Conscrypt where its native library loads, otherwise a classical handshake. |
| `CONSCRYPT` | Require Conscrypt. Fail the connection instead of falling back. |

`CONSCRYPT` is the value to prefer for a deployment that intends to use PQC: an
unusable provider fails every connection attempt with a `UserConfigException`
naming the property, rather than completing a quiet classical handshake. The failure surfaces on the first connection rather than at
startup. `AUTO` suits a single artifact deployed to a mix of platforms, some of
which Conscrypt does not support; it logs once when it does not find the
provider.

`AUTO` names an outcome, not an implementation. It reaches for Conscrypt only to
fill the gap before JDK 27, so on a JRE that negotiates ML-KEM natively it stays
on the default provider and does not load Conscrypt's native library at all. As
runtimes move to JDK 27 and later, `AUTO` stops substituting a TLS
implementation without anyone changing configuration. `CONSCRYPT` names an
implementation and so is honored on every JRE, which is what makes it the way to
exercise Conscrypt deliberately.

The default is `JDK` so that selecting a different TLS implementation for
AlloyDB traffic is always a deliberate choice. The connector requests an
`SSLContext` from Conscrypt for its own connections only, as a `Provider`
instance rather than by name, so the JRE's global provider list and the TLS
behavior of everything else in the application are untouched.

### Platform support

Conscrypt is a native library, published for these platforms:

| Operating system | Architectures |
| --- | --- |
| Linux (glibc) | x86-64, ARM64 |
| macOS | x86-64, ARM64 |
| Windows | x86-64 |

Anywhere else — Alpine and other musl-based images, 32-bit platforms, s390x,
ppc64le — the library will not load. `AUTO` falls back to the JRE provider and
logs that it did; `CONSCRYPT` fails the connection. There is no post-quantum
option for those platforms before JDK 27.

Three build configurations also prevent the library from loading:

- **Uber jars that relocate packages.** Conscrypt's JNI symbols are bound to the
  `org.conscrypt` package name, so relocating it (for example with
  `maven-shade-plugin`'s `<relocation>`) breaks the native library. Exclude
  `org.conscrypt` from relocation.
- **Read-only or `noexec` temporary directories.** The native library is
  extracted from the jar at startup. Point it elsewhere with
  `-Dorg.conscrypt.native.workdir=/some/writable/path` if `java.io.tmpdir` is
  not usable.
- **GraalVM native images.** Extracting and loading a JNI library at runtime is
  not something a native image can do, and Conscrypt ships no reachability
  metadata. Leave `alloydbTlsProvider` unset in a native image. There is no
  post-quantum option for native images today: GraalVM is skipping JDK 26, 27
  and 28, so [JEP 527][jep-527]'s native support will not reach native images
  until a GraalVM built on JDK 29.

### Cost

Loading Conscrypt's native library takes roughly a quarter of a second and
around 7 MB of resident memory, paid once per JVM, on the first connection that
uses it. Connections using the default `JDK` provider never load it. This is
worth knowing for short-lived or cold-starting workloads such as Cloud Run
services and Cloud Functions, where it lands in the first connection's latency.

### FIPS

Conscrypt's published builds are not FIPS builds. A deployment that must use a
FIPS-validated TLS stack should leave `alloydbTlsProvider` unset, which keeps
AlloyDB connections on the JRE's own provider.

### Failing closed

`JDK` and `AUTO` never fail a connection over key exchange: if post-quantum
groups are unavailable, the handshake proceeds with classical cryptography.
`CONSCRYPT` closes the largest gap by refusing to connect when the provider
cannot be used, with an error naming the property.

It does not guarantee that a post-quantum group was negotiated. There is no
JSSE API for the negotiated key exchange group yet (see [JDK-8388519][jdk-api]),
so neither the connector nor your application can assert on it
programmatically. If the server does not offer a hybrid group, the handshake
still succeeds with a classical one.

## How It Works Under the Hood

When initiating a secure socket, the connector reads `alloydbTlsProvider`:

1. `JDK` requests `SSLContext.getInstance("TLSv1.3")` from the JVM's default
   provider, and never loads Conscrypt.
2. `AUTO` on JDK 27 and later behaves exactly like `JDK`, because the JRE
   already negotiates ML-KEM; Conscrypt is never resolved. On earlier versions
   it behaves like `CONSCRYPT` below, except for the fallback.
3. `AUTO` (before JDK 27) and `CONSCRYPT` build a Conscrypt `Provider` and request
   `SSLContext.getInstance("TLSv1.3", provider)` from it, so BoringSSL
   establishes the database mTLS connection. `AUTO` falls back to the default
   provider when Conscrypt is unusable, logging once that it did; `CONSCRYPT`
   raises a `UserConfigException` instead. Like any user configuration error it
   is reported as such and does not trigger a connection info refresh, because
   it will fail the same way on every attempt.

The provider is built once per JVM and reused, because a `Provider` instance is
not global state that an application can change underneath the connector.

The trust manager comes from the same provider as the `SSLContext`; the two
cannot be mixed. Hostname verification is unaffected: Conscrypt runs its own
RFC 2818 verification against the SNI name the connector sets, and rejects a
server certificate that does not match the instance address.
`ConnectionSocketConscryptTest` covers that, and covers a handshake restricted
to `X25519MLKEM768`.

Conscrypt honors the `jdk.tls.namedGroups` system property. Narrowing it to
classical groups disables post-quantum key exchange for AlloyDB connections as
well as everything else in the JVM.

## Verifying PQC Is in Effect

When the connector selects Conscrypt, it logs the following once, at INFO, on
the first connection that uses it:

```
Conscrypt will serve the AlloyDB connections that request it with
alloydbTlsProvider. Connections left on the JRE's default provider are
unaffected. Logged once per process; enable DEBUG logging to see the provider
chosen for each connection.
```

The provider is chosen per connection, so this line does not mean every AlloyDB
connection in the process uses Conscrypt. An application can run one data source
on Conscrypt and another on the JRE's provider; only the DEBUG line below
reports which served a given connection.

When `alloydbTlsProvider` is `AUTO` and Conscrypt cannot be used, it instead
logs once, at INFO, that the connection is falling back:

```
alloydbTlsProvider is set to AUTO, but Conscrypt is unavailable on this
platform. Using the default JSSE provider, which means AlloyDB connections
will not use post-quantum key exchange.
```

When `alloydbTlsProvider` is `AUTO` on JDK 27 or later, the JRE supplies
post-quantum key exchange itself and Conscrypt is never loaded. That is also
logged once, at INFO, because it rests on the Java version rather than on
anything the JRE was asked:

```
alloydbTlsProvider is set to AUTO and this JRE reports Java 27, which
negotiates post-quantum key exchange natively (JEP 527), so AlloyDB connections
use the default JSSE provider and Conscrypt is not loaded. The Java version is
the only signal available here, because JSSE does not report which key exchange
groups a provider supports. Set alloydbTlsProvider to CONSCRYPT if this runtime
does not negotiate ML-KEM.
```

A runtime that reports Java 27 or later without actually negotiating ML-KEM --
one shipping JEP 527 disabled, for instance -- would get neither Conscrypt nor
post-quantum key exchange. Setting `alloydbTlsProvider` to `CONSCRYPT` overrides
the assumption on any version.

At DEBUG the connector also logs the provider it selected for each instance,
and the negotiated protocol and cipher suite for each connection. None of these
report the key exchange group, which is the thing that makes a handshake
post-quantum. No JSSE API reports it either (see [JDK-8388519][jdk-api]), so
confirming it means looking at the wire.

**JDK 27+ using the default `SunJSSE` provider** is the exception — it names
the group in its handshake trace:

```
-Djavax.net.debug=ssl:handshake
```

**Under Conscrypt**, capture the handshake and read the `key_share` extension of
the ServerHello, which TLS 1.3 sends in the clear:

```
tcpdump -i any -s0 -w handshake.pcap 'tcp port 5433'
```

Open the capture in Wireshark and select the ServerHello; its `key_share`
extension names the group the server chose. A shortcut that needs no dissector:
Conscrypt sends the hybrid key share in its first ClientHello, which makes that
record roughly 1.4 KB instead of the few hundred bytes a classical ClientHello
occupies. A small ClientHello means no hybrid group was offered.

If the group is classical (`x25519`, `secp256r1`, etc.), check, in order:

1. `alloydbTlsProvider` is set to `CONSCRYPT` (or to `AUTO` on a platform
   Conscrypt supports). It defaults to `JDK`, which never uses Conscrypt. On
   JDK 27+ the default is what you want and no property is needed.
2. The Conscrypt INFO line above appears in your logs, and the DEBUG line names
   Conscrypt for the connection you care about -- the INFO line alone only means
   some connection in the process requested it. If the `AUTO` fallback line
   appears instead, see [Platform support](#platform-support).
3. `jdk.tls.namedGroups` has not been narrowed to exclude the hybrid groups.
4. The server offers a hybrid group. Both peers must support one.

[jep-527]: https://openjdk.org/jeps/527
[jep-496]: https://openjdk.org/jeps/496
[jdk-api]: https://bugs.openjdk.org/browse/JDK-8388519
[conscrypt]: https://github.com/google/conscrypt
