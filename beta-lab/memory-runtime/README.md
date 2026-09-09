# Two-part memory runtime: isolated feasibility beta

Plain Java 21 API; no Gradle dependency and no changes to production launchers,
authentication, native code, or Minecraft. This library receives bytes from a
future authorized caller. It performs no HTTP requests and writes no payload JAR,
class, resource, or temporary file. It does not attach to processes, instrument
Minecraft, or implement native loading.

## Run the focused checks

From this repository's root in PowerShell, using a full JDK 21:

```powershell
.\beta-lab\memory-runtime\run-tests.ps1 -JdkHome 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
```

The script compiles only this isolated directory. It writes the runtime/test
classes to its ignored `build/test-classes/`. Fixture sources, class output, and
both fixture JARs are generated entirely in memory by the Java test executable.
No fixture files or downloads are used.

## API

```java
var namespaces = new TwoPartMemoryClassLoader.Namespaces(
    Set.of("example.privatebeta.payload."),
    Set.of("assets/privatebeta/", "data/privatebeta/")
);
try (var runtime = TwoPartMemoryClassLoader.open(
        firstJarBytes, trustedFirstSha256Hex,
        secondJarBytes, trustedSecondSha256Hex,
        namespaces, TwoPartMemoryClassLoader.Limits.betaDefaults(),
        applicationClassLoader)) {
    Class<?> entry = runtime.loadClass("example.privatebeta.payload.Entry");
    // Invocation/lifecycle contract belongs to the authorized caller.
}
```

Supply exactly two non-empty, disjoint ordinary JAR/ZIP parts. Each part must
contain at least one file; use independent trusted SHA-256 hexadecimal digests
from an authenticated source. Computing a digest from a newly downloaded payload
does not independently establish its authenticity. The loader copies input arrays
and never modifies the caller's arrays; the caller should release/clear its own
download buffers as appropriate.

All entry names must be canonical relative slash-separated paths. Traversal,
absolute paths, backslashes, control characters, repeated separators, duplicate
file/directory identities, and duplicate entries across the two parts are
rejected. Directory entries count toward the global entry limit. Decompressed
per-entry and combined byte limits are enforced while reading, including entries
whose size is absent from the local ZIP header; raw part sizes are bounded too.

Class package prefixes end in `.` and are child-only: a missing payload class
cannot fall back to a same-named parent class. Other classes are parent-only.
Resources under the class-package paths or explicitly supplied resource prefixes
are payload-only; other resource lookups are parent-only. Resources are exposed
as revocable streams and custom `memoryjar:` URLs without files or networking.

`visitRetainedParts(RetainedPartVisitor)` synchronously visits both original part
maps, in argument order, under the loader's lifetime monitor. It preserves exact
entry provenance including directory/metadata entries and shares the actual
loader-owned arrays through structurally unmodifiable views. Callbacks must not
mutate or retain borrowed maps/arrays, start asynchronous reads, or wait for a
thread that needs this loader. Copy inside the callback if another component
needs separately owned bytes. Concurrent close waits for both visits; nested
visits fail with `IOException`, reentrant close fails with `IllegalStateException`,
and callback exceptions always release the guard. Visits after close fail.

The [memory/integrity integration fixture](../memory-integrity/README.md) connects
this visitor to actual production `ArtifactIntegrity` without reparsing or an
independent integrity copy. Root metadata such as `fabric.mod.json` remains
unsupported by the unchanged namespace policy.

## Deliberate constraints and caveats

- Trusted-code boundary, **not a security sandbox**. Loaded code has ordinary JVM
  process permissions, including the ability to use unrelated filesystem/network
  APIs. Namespace restrictions do not isolate malicious code.
- This is not a Fabric/Knot/Mixin implementation. No Minecraft integration,
  remapping, mod discovery, entrypoint wiring, or live-game compatibility has been
  verified. A later two-stage caller must supply its own supported lifecycle.
- Close is synchronized and idempotent: it rejects subsequent loader calls,
  revokes issued **payload** streams/URLs, overwrites owned entry arrays with
  zeroes, clears their map, and reports zero retained bytes/entries. It does not
  revoke parent-owned streams or bytes already copied by callers.
- Already-defined classes and already-resolved JVM references remain usable.
  Lazy class/dependency resolution after close may fail with a linkage error.
  Close cannot stop payload threads, undo state changes, forcibly unload classes,
  or wipe JIT/runtime copies. Class unloading is GC-dependent after all loader,
  class, instance, URL, stream, and thread references are released. Buffer wiping
  is best-effort JVM hygiene, not a cryptographic erasure guarantee; swap, crash
  dumps, VM internals, and diagnostic tooling are outside this API's control.
- No JAR signatures, manifest class paths/sealing, multi-release variants, module
  descriptors, ZIP64, split-disk archives, or native-library entries are supported.
  Standard manifests are treated as ordinary resource bytes and need explicit
  `META-INF/` permission. Only one part may carry `META-INF/MANIFEST.MF`; there is
  no silent duplicate-manifest exception. Local ZIP headers supply the entry view;
  central records, names, CRCs, sizes, compression methods and counts must agree.
  An ordinary end directory is required. This is not a
  general ZIP repair or archive-signature validation tool.
- Local helper/test class files are expected. Keeping downloaded payloads in
  memory is not a claim of invisibility, non-inspectability, or anti-detection.

The executable suite checks cross-part class dependencies/resources, parent
delegation boundaries, both hashes, duplicate entries/manifests, archive and
decompression limits, invalid paths/namespaces, directory handling, unsupported
payload formats, input ownership, URL escaping, close-time byte zeroing, revoked
streams/URLs, and post-close/concurrent access. It also asserts the important
limitation that an already-loaded class remains callable after close.
