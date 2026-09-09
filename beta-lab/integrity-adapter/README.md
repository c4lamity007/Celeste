# Retained-memory integrity adapter

This focused Java 21 harness compiles the **production** `ArtifactIntegrity` and
`Crypto` classes with a compile-only `CelesteClient` stand-in. It does not start
Minecraft, alter authentication, load a real payload, or publish anything.

Run from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File beta-lab/integrity-adapter/run-tests.ps1
```

## Integration API and trust contract

The trusted bootstrap lifecycle calls
`ArtifactIntegrity.installVerifiedMemorySource(source)` exactly once, **before
the first authentication `digest()` call**, after verifying both archives against
trusted release metadata. The first `digest()` call permanently pins the source
choice, even if that digest fails. Installation is rejected after disk selection;
an authenticated disk artifact cannot later be replaced by a memory snapshot.
`VerifiedMemorySource.visitParts(PartVisitor)` synchronously invokes
`visitor.accept(Map<String, byte[]>)` once for each of the **two** retained parts.
Use complete original entry names and exact retained entry bytes, including
manifests, signature files and other metadata. The adapter does not trust or
accept a caller-computed expected digest. Existing authentication still compares
the recomputed digest using its existing checks.

The lifecycle/source must hold the same lifetime lock used for load/release while
visiting, prevent concurrent mutation during the visit, and throw `IOException`
after closure or revocation. Map values are **borrowed**, not transferred; the
adapter never wipes or mutates them. It creates private bounded copies during
the visit, hashes them after the visit in global entry-name order, then wipes the
copies on success or failure. Do not provide a stale independent copy that can
diverge from the loader's retained bytes. Reject duplicate archive entries before
putting them in maps; ordinary maps cannot represent overwritten duplicates.

Installation is one-shot and has no reset/replacement path. Before any digest,
the trusted lifecycle can install memory exactly once. The first auth digest
atomically pins either disk (when nothing was installed) or that installed
memory source. Installation and source pinning share one synchronization lock,
so a concurrent install/digest race cannot authenticate disk and subsequently
switch heartbeat verification to memory. Closing/revoking a selected memory
source makes subsequent digests fail. There is **no disk fallback after
installation**, including when `celeste.payload.parts` points at a valid jar.
`digestVerifiedMemory(source)` is a direct non-installing test/helper entry point;
it does not pin or change the selected source. Normal auth remains on its
existing `digest()` entry point. Pinning preserves disk lookup behavior: it pins
the disk-versus-memory choice, not a `celeste.payload.parts` property value.

The source interface is a trusted in-process capability, not a sandbox against
arbitrary malicious code already executing in the JVM. Verification of release
signatures/hashes and session authorization remain lifecycle/auth responsibilities.

## Compatibility and limits

Disk-backed methods remain unchanged. Memory hashing shares the exact existing
`protectedEntry` predicate and framing routine: global Java string ordering;
big-endian 32-bit UTF-8 name-byte length, UTF-8 name, big-endian 64-bit content
length, then content. Directories are excluded like `JarEntry.isDirectory()`.
The **only excluded file** is `META-INF/.r/4d7a9c2e`. Protected duplicate names
across or within enumerated parts fail; excluded descriptor duplicates do not.

Memory-only defensive release bounds are fixed: exactly two parts, 65,536 total
enumerated entries, 1,024 UTF-16 name characters, 8 MiB per entry, and 128 MiB
combined entry content. These exceed the smaller feasibility-loader defaults
and accommodate the measured current two-part release (44,771 entries,
77,487,732 expanded bytes); lifecycle loader limits must independently permit
that verified release. Counts and bytes include excluded
entries. Every call recomputes from current retained bytes; no digest is cached.
Visitor errors remain sticky even if an invalid source swallows them, and the
visitor is revoked after the synchronous source visit.

## Focused verification

The suite compares split JARs, a single JAR, directories, and retained maps using
the real production implementation and an independent framing reference. It
covers shuffled part/entry ordering, Unicode names and binary content, metadata
exclusion/protection, duplicates across and within archives/maps, empty protected
sets, exact/over-limit boundaries, invalid/late visitors, snapshot ownership,
fresh recomputation, one-shot installation, and fail-closed source release.
Parity checks invoke the unchanged private disk methods without selecting the
JVM's authentication source. Separate fresh-JVM modes exercise real `digest()`
disk fallback, rejection of memory installation after a first disk digest
(including failure), and failed-memory no-fallback behavior. A 128-round
concurrent install/digest test uses fresh isolated production class definitions
without resetting any static fields, and verifies one consistent pinned source
for the initial digest and subsequent heartbeat in every round.
An inexpensive synthetic fixture reproduces the measured release's per-part
entry counts and expanded byte totals without expanding the real release jars.

Generated fixture jars/classes stay under ignored `build/`. This is component
verification, not an in-game/auth-service integration test.
