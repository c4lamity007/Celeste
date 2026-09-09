# Payload startup transaction

`BetaPayloadSession.startFromRetainedParts` gives the internal bootstrap a JDK-only
entrypoint: a synchronous retained-part visitor plus the verified catalog hash and
canonical runtime digest. The public class/method mapping must be carried into
the signed bootstrap when obfuscating the payload.

The session checks the same retained bytes used by the classloader, derives its
assets from them, installs the memory integrity source and beta context, registers
pipelines, and waits for an observed internal resource reload before starting
mandatory runtime authentication. `ready()` requires complete authorized service
initialization. No pack provider/profile is created or added to the pack chooser.

Stop/revocation first retires services and callbacks, then performs a normal reload
without internal assets. Their storage closes only after successful replacement.
Failure is observable through the lifecycle stages; failed removal retains bytes
that Minecraft may still own. This session requires complete host hooks and a
compatible selected game. It neither attaches an agent nor bypasses authentication.

`betaPayloadSessionCheck` runs the production orchestration against controlled
resource/auth/game boundaries. 26 checks cover readiness ordering, source-derived
assets, normal/repeated stop, stop during activation, digest mismatch, revocation,
failed asset activation/removal and scheduling failure. Digest algorithms, actual
resource-manager hooks and service lifecycle have their own component checks.
These checks do not show the complete beta running in Minecraft.
