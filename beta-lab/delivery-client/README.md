# Owner-beta delivery client

Standalone Java 21 + Gson delivery foundation. This directory is not included
in production source sets. It does not attach to a JVM, define classes, execute
downloaded code, write artifacts to disk, or configure the native launcher.

## API

```java
var delivery = new BetaDeliveryClient(
    URI.create("https://the-configured-auth-origin/"),
    pinnedRawEd25519PublicKey, existingInstallerGrant, expectedDeviceId, 7L);
var catalog = delivery.fetchCatalog();
try (var release = delivery.download(catalog)) {
    ByteBuffer firstPayload = release.artifact("payload-a"); // read-only
    // A separately reviewed host may consume the verified bytes in memory.
}
```

The origin, pinned 32-byte public key, exact installer token and expected
numeric UID/device must come from the existing trusted launcher context. The
component never reads a credential store or infers the owner from a label.
`verifyManifest(token)` is a network-free alternative to `fetchCatalog()`.
`VerifiedCatalog` exposes release ID, client version, runtime digest, catalog
hash, expiry and immutable artifact metadata. Only the verifier constructs it,
and a catalog cannot be passed to another delivery-client instance.

`MemoryRelease.artifact(id)` returns a read-only `ByteBuffer`. Its `close()`
clears retained arrays, including outstanding views, and prevents subsequent
access. Caller-created copies remain the caller's cleanup responsibility.
Failed multi-artifact downloads clear every accumulated array. Successful
bytes are not cached on disk or automatically handed to a class loader.

## Protocol and bounds

The verifier follows `auth-service/app/beta_delivery.py` schema
`celeste-beta-release-2` and the existing `GrantSigner` CST/Ed25519 format:

- Both installer and manifest signatures must match the configured pinned key,
  expected audiences, numeric UID/device, validity window and exact fields.
- The manifest's installer SHA-256 and JTI must bind the exact original token.
  Manifest lifetime is at most 120 seconds and cannot exceed installer expiry.
- Four sorted entries are required: `bootstrap-a`, `bootstrap-b`, `payload-a`,
  `payload-b`, with matching bootstrap/payload roles and exact relative URLs
  `/v1/beta/artifacts/<release_id>/<id>`. No filename or arbitrary URL is accepted.
- The release is separate from the installer's production payload identity.
  Runtime identity, metadata, entry order and transport digests/sizes are bound
  by the SHA-256 of Python-compatible canonical public-catalog JSON.
- Duplicate keys, wrong JSON types, invalid UTF-8/base64url, unexpected fields,
  excessive nesting, malformed hashes and invalid sizes fail closed.
- Each artifact is at most 64 MiB; the four declared sizes total at most
  128 MiB. Responses stream into a bounded buffer with cancellation on overflow
  or a 25-second whole-transfer deadline. The HTTPS transport never redirects.
- Artifact requests send the original installer bearer and the exact signed
  manifest header. Final URI, status 200, content type/encoding/length, actual
  size, SHA-256 and current grant expiry are checked before bytes are returned.

The catalog's `runtime_sha256` is the server-signed expected canonical runtime
digest, not either transport hash. Computing that digest over the retained
runtime entries remains the separate integrity adapter's responsibility. The
server still rechecks live owner/license/device state on each real request;
offline verification cannot establish live revocation state. This component
does not implement runtime authorization or heartbeats.

## Offline verification

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --no-daemon --console=plain -I beta-lab/delivery-client/check.init.gradle betaDeliveryClientCheck
.\auth-service\.venv\Scripts\python.exe beta-lab/delivery-client/generate_fixture.py --check
```

The Java task compiles with `-Xlint:all -Werror` and runs offline fixture
transport, cryptographic and bounded-subscriber tests. Its checked-in golden
vector was generated through the actual Python server manifest route,
`GrantSigner` and `_read_release`, using only deterministic fixture material,
temporary files and an in-memory identity stub. The escaped version includes
`<>&`, quote and backslash to detect canonical-JSON incompatibility.

Verified locally: 308 Java checks passed, including the actual server golden
signatures, binding/expiry/schema/path/size/hash failures, denied/redirected
responses, partial cleanup and streaming overflow/cancellation. No live
network, real credentials, deployment, attach, code execution or Minecraft
runtime test was performed by these checks.
