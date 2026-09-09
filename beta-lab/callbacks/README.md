# Detachable core callbacks

`DetachableCallbacks` is a JDK-only parent bridge. One proxy is registered per game-owner identity,
stable name, and listener interface; the proxy is generated in the interface's classloader. Fabric
retains that proxy and the parent invocation handler. Closing its registration clears the payload
listener reference without mutating Fabric's listener arrays or repeatedly adding callbacks.

Abstract callbacks must return void. Public interface default methods execute on the proxy, including
Minecraft's synchronous resource-reloader defaults. Their scheduled work therefore refers back to the
game-owned proxy; after detach its abstract callbacks are inert. A slot cannot accept a replacement
until pending invocations and asynchronous default-method results have finished. This also prevents
an old queued reload from unexpectedly invoking the next runtime.

`close()` is nonblocking and safe from inside a callback. It cannot revoke a call that already captured
its listener or cancel work the listener itself scheduled. `detached()` completes after close and the
bridge's in-flight calls drain. Await it outside callbacks before releasing a payload. Listener failures
are propagated, not swallowed. A registrar that fails leaves its slot unavailable because registration
may have partially succeeded; retrying blindly could leave duplicate callbacks.

`BetaCoreCallbacks` uses this bridge only for Celeste's menu tick and fonts reloader on the late-start
path. Normal startup continues to register directly. `CelesteClient.coreCallbacksDetached()` exposes
retirement of these two subscriptions, not retirement of the whole client.

`BetaSubscriptions.open()` enables the same bridge for the late payload's module tick/render,
friend/interface ticks, gameplay/aura disconnect, world-visual extraction/draw, and main/arrow/combat HUD
listeners. Existing listener bodies and registration order are preserved. HUD proxies use the public
`HudElementRegistry.addLast` API once per stable ID and remain inert after detach; later payloads reuse
them. The scope closes after feature shutdown, and `CelesteClient.subscriptionsDetached()` separately
exposes its drainage. Registration after close rejects instead of falling back to a permanent direct
listener. Chams/volume stopping, disconnect, and resource-reloader subscriptions use the same scope.
Client cleanup explicitly releases their private framebuffer, vertex buffer, and allocator after
feature shutdown and before detaching lifecycle callbacks; both cleanup paths are idempotent and
attempt remaining releases if one release fails. Auth remains outside this scope. Render-frame data,
queued client tasks, and caches still need separate retirement before full classloader unloading;
scope drainage does not assert that GPU command execution or arbitrary queued payload tasks finished.

Offline check: add `-I beta-lab/callbacks/check.init.gradle betaCallbacksCheck` to the project Gradle
command. The fixture covers identity/reuse, close inside and during callbacks, delayed default methods,
exception propagation, and partial registration. It does not start Minecraft or establish in-game QA.
