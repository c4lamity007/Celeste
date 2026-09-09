# Loader-to-integrity integration fixture

This Java 21 suite compiles the actual `TwoPartMemoryClassLoader`, production
`ArtifactIntegrity`/`Crypto`, and the existing compile-only `CelesteClient` stub.
It starts no game, fetches no payload, changes no auth behavior, and performs no
attachment, native execution, or launcher/server work.

```powershell
.\beta-lab\memory-integrity\run-tests.ps1 -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\beta-lab\memory-runtime\run-tests.ps1 -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
```

## Exact API and bridge

The loader exposes `public synchronized void visitRetainedParts(RetainedPartVisitor
visitor) throws IOException`; its nested functional interface declares
`void accept(Map<String, byte[]> entries) throws IOException`.

After the trusted lifecycle verifies release metadata and `open(...)` checks both
archive SHA-256 hashes, the bridge is:

```java
ArtifactIntegrity.VerifiedMemorySource source =
    visitor -> loader.visitRetainedParts(visitor::accept);

String measured = ArtifactIntegrity.digestVerifiedMemory(source); // optional, non-installing
ArtifactIntegrity.installVerifiedMemorySource(source); // once, BEFORE the first digest()/auth check
```

Normal production authentication keeps its existing `digest()` path and digest
comparison. Installation cannot replace a previously selected disk or memory
source. Closing the installed loader fails future integrity calls without disk
fallback. The adapter creates its own bounded temporary snapshot during the
visit, recomputes the canonical digest, and wipes its copies afterward.

The visitor receives exactly two original part views, in `open()` argument order.
It includes original directory entries (empty arrays), metadata, classes, and
resources. Maps are structurally unmodifiable; their values are the **same
loader-owned byte arrays**, not reparsed/caller-owned integrity duplicates.
Map iteration order is unspecified.

Array values are borrowed, mutable Java arrays. Trusted callbacks must not
modify or retain arrays/views or pass them to asynchronous work. Both callbacks
hold the loader's load/resource/close monitor. Concurrent close waits; reentrant
close throws `IllegalStateException`; nested visits and post-close visits throw
`IOException`. Checked and unchecked callback errors always release the guard.
Callbacks must not wait on another thread that needs the loader monitor.

A resource provider can copy selected `assets/...` values into its own input map
inside the callback and own those copies' cleanup. Such copies do not replace the
loader-backed integrity source. This suite verifies extraction, not actual
Minecraft provider installation or reload.

## Verification and explicit packaging gap

Generated two-part fixtures have hashes signed using a fresh local Ed25519 key;
the test caller verifies that signature before passing hashes to the loader.
This tests the contract, not production key distribution/session authorization.
Classes and resources work across parts. Real retained-memory hashing equals
both independent canonical framing and the unchanged production disk hashing
routine (invoked directly for reference, without pinning the auth source).

Additional checks cover original provenance and exact array identity, complete
metadata/directories, immutable map structure, provider extraction, caller-buffer
cleanup, checked/runtime/Error callbacks, reentrant calls, actual monitor-blocked
close, clearing both maps and the combined index, post-close integrity failure,
and one-shot installation with no fallback despite valid disk reference JARs.

Namespaces remain unchanged. Fixture metadata is under explicitly approved
`META-INF/`; exact root names such as `fabric.mod.json` and `pack.mcmeta` remain
unsupported. Real packaging, namespace/limit configuration, resource reloads, and
game lifecycle integration still require separate deliberate work. No root-wide
resource permission was introduced.

Fixture source/classes and archive construction are generated in memory using
shared test-only helpers. Two **synthetic disk-reference JARs** are written under
ignored `build/fixtures/integration-*` solely for parity checks; compiled harness
classes go under ignored `build/test-classes`. No downloaded/real payload is
written, and the loader itself still performs no filesystem writes.
