# Internal Celeste asset reload layer (Minecraft 1.21.11)

Celeste assets are an **internal resource-manager input**, not a selectable
texture/resource pack. Production code never creates a ResourcePackProfile,
implements/registers a ResourcePackProvider, touches ResourcePackManager choices,
or filters/hides any UI entries. The superseded selectable-provider component
has been removed. Normal logs and diagnostic resource identities remain intact.

## Integration API and host boundary

`InternalMemoryResources` starts empty/inactive. It takes one already verified
map through `installVerifiedAssets(Map<String, byte[]>)`; the map constructor
is shorthand for creation plus installation. Normal game version/registry
bootstrap must be complete before construction.

The host reload-input hook must call this once per actual client
reload, after ordinary selected ResourcePacks are collected and immediately before
the resource manager consumes that list:

```java
List<ResourcePack> actualReloadInputs = internalAssets.augmentReloadPacks(selectedPacks);
```

This hook must also run for later user-triggered reloads. The opt-in implementation
is in `../reload-hooks/`, with `MinecraftInternalResourcesHost` connecting its
canonical bridge to this layer. The actual beta bootstrap must still enable the
prepared hooks in the selected game. Registering a Celeste ResourcePackProfile or
provider is neither needed nor supported.

- Empty/inactive/closed returns the exact original input list, unchanged.
- Active returns an immutable shallow copy with one fresh Celeste-only resource
  view appended. Every other pack's identity/order is preserved; none is closed,
  replaced, mutated, filtered or removed.
- Reapplying the same layer to its already-augmented list is a no-op while its view
  remains open. Always pass fresh **original selected inputs** to future reloads;
  never cache an augmented list as the chooser's selected list.
- The actual resource manager owns/closes the injected view. Layer close clears
  only Celeste bytes, not any other pack.
- Appended Celeste resources take precedence only within namespace `celeste`.
  Vanilla/third-party namespaces are never provided by this layer.
- ResourcePackInfo identifies "Celeste internal assets" for normal diagnostics.
  Merely implementing ResourcePack for reload input does not add a chooser profile.

Once the beta bootstrap has enabled those hooks, use the host on the client thread:

```java
internalAssets.installVerifiedAssets(verifiedAssets);
var host = MinecraftInternalResourcesHost.connect(client, internalAssets);
host.activateAndReload()
        .thenRun(() -> { /* consume assets only after successful reload */ });

// Stop feature usage first. Release bytes after successful full unload/reload.
host.deactivateAndReload().thenRun(host::close);
```

The implemented `MinecraftInternalResourcesHost.activateAndReload()` and
`deactivateAndReload()` combine that boundary with the corresponding layer operation.
The host requests the normal full reload, which uses the
augmentation hook, and settles on both success and failure only after the attempt
and any required recovery has completed. Do not directly substitute
`client::reloadResources`: checked Minecraft 1.21.11 bytecode completes that future
on success but may leave it pending on failure/recovery. No reliable host failure
observer is provided by that raw future. The host observes the original Minecraft
result callback after renderer/server-pack work, then schedules settlement outside
the bridge dispatch. Successful replacement retires prior resource-manager views;
open streams may still finish. This is not a claim that all worker threads stopped.
A pre-completed fixture future does not prove actual resources loaded.

On a failed attempt containing internal assets, the host excludes them and queues
a fresh normal reload without changing pack selections. If that cleanup succeeds,
the activation still fails with its original error. The failed layer stays excluded
until explicit retry. If cleanup without internal assets also fails, Minecraft's
ordinary fallback remains available for the independent pack failure. The host
handles SplashOverlay's same-token failure replay after a throwing success callback.

## Ownership, errors and limits

Installation is one-shot while open, empty, inactive and not reloading. It copies
each asset once. Failed validation publishes no partial map and permits corrected
retry; activation while empty fails immediately. Successful installation cannot be
replaced, even after deactivation.

At the storage-layer level, async failure propagates and retains bytes/requested
active state for explicit retry/deactivation. The host separately performs the
cleanup retry described above and keeps the failed internal layer excluded.
Synchronous submission failure restores the previous active state. Overlapping
transitions and close-during-reload fail immediately. Cancelling the returned
observer cannot cancel the real reload or clear its guard early. The host must
serialize this lifecycle with other client reloads too.

Call close after a **successful** deactivate/full reload, except final process
shutdown. All Celeste views and unopened suppliers are invalidated; already opened
streams may finish and retain their array until closed/collected. Stream close
drops that reference. This is not secure JVM/GPU/OS erasure and does not dispose
of already loaded renderer objects independently of normal resource reload.

Accepted names are canonical lowercase slash-separated `assets/celeste/**` plus
optional root `pack.png`. Font JSON/PNG, shader/includes, textures and metadata
sidecars are supported. Traversal, empty segments, backslashes, control characters,
encoded paths, uppercase names and other namespaces are rejected. Incoming root
pack metadata, overlays, filters, feature flags and server/data packs are unsupported.
Root pack metadata is generated with Minecraft's current exact client format codec.

Limits: 8,192 entries, 32 MiB per entry, 256 MiB total, 512-character canonical
names. Caller must verify authorization, integrity, archive/decompression limits
and duplicate archive entries **before** supplying a map. Do not mutate input
during installation; release upstream references when appropriate to avoid duplicate
retention. Reads do not copy arrays; sorted identifier indexing happens once.
No network, filesystem extraction, authentication, loader or launcher work occurs.

Custom renderers must read through Minecraft's resource manager (or a separately
explicit adapter); this layer cannot fix code that bypasses resource APIs.

## Focused verification

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --no-daemon --console=plain -I beta-lab/resource-provider/check.init.gradle betaInternalResourceStorageCheck betaInternalResourceIntegrationCheck
```

The opt-in init script uses this project's actual mapped Minecraft dependencies,
without adding production source sets or dependencies. Classes/output are under
ignored `build/module-qa/resource-provider/`.

The storage suite exercises real ResourcePack lookup/listing, canonical names,
immutable snapshots and view/stream/shared-reference cleanup. The integration
suite uses real vanilla metadata, ResourcePackManager chooser profiles/selection
and LifecycledResourceManagerImpl to check internal asset inclusion without
chooser changes, preserving other packs/order/ownership, exact original-list
restoration, one-shot late installation, and sync/async/cancellation lifecycle.

Latest targeted run: production compilation succeeded; **50 storage/view checks,
169 internal-layer checks and 68 host lifecycle checks passed**. The host tests
use real resource managers with fixture scheduling for Minecraft's overlay and
callback boundaries; hook bytecode has its own separate checks.

The fixture-only classloader applies Fabric Loader 0.19.3's existing
PackageAccessFixer ASM visitor to in-memory named Minecraft definitions, reproducing
the development package-access fix needed after mapping splits packages. No game
JAR is edited and no real game/mod loader is launched. Production code uses no
Fabric implementation classes.

Fixture textures are marker bytes, not GPU assets. Passing these tests is not
playable startup, loaded-build identity, actual hook installation in a running game,
font/shader compilation or in-game rendering verification. Runtime integration/QA
remains pending.
