# Celeste bootstrap

Install the authenticated, signed `Celeste.jar` into the selected Minecraft instance's `mods` folder. Fabric's ordinary mod discovery loads it; no launcher library, main-class wrapper, or profile edit is required. Keep the launcher's existing Fabric profile and other mods intact.

The normal product is **Celeste** (`celeste_bootstrap`). Its remotely delivered runtime retains the separate `celeste` identity. Celeste+ is the separate live-attachment product.

The verified target is Minecraft 1.21.11, Java 21, Fabric Loader 0.19.3 and the matching Fabric API. Fabric Loader 0.19.3 already provides MixinExtras 0.5.4; this bootstrap declares that dependency and does not flatten or impersonate it. Genuine MixinExtras installations must never be removed by filename alone.

The current signed runtime linker uses private Fabric loader APIs. Quilt is explicitly unsupported until that boundary is adapted and tested. Forge and NeoForge require distinct loader/runtime adapters; they are not enabled by changing metadata. Launcher support is separate from loader support: ordinary launchers with a compatible Fabric instance can use the same bootstrap.

Native installation authentication, fresh signed authentication on every Minecraft startup, remote stage authorizations, payload digests and in-game license enforcement remain in place. Startup does not consume a short-lived installer ticket, so installing earlier does not make the first later launch fail. The three remote loader JARs and two payload parts remain implementation artifacts under `config/celeste/runtime`; they are independent of the removed launcher discovery shim.

Run `gradlew.bat --offline :bootstrap-loader:checkBootstrapMod` for actual Fabric directory discovery and metadata/dependency checks. This focused check verifies the remapped artifact, distinct library identity and entrypoint linkage without initializing Minecraft or authentication. This isolated check does not cover a full signed-delivery game startup.
