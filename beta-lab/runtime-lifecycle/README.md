# Observable beta runtime lifecycle

After host verification and resource/hook preparation, call `BetaRuntime.start()`. Merely receiving
the handle does not mean Celeste is active. `ready()` completes only after the usual mandatory auth
path and authorized service initialization succeed. `status()` returns JDK string values for lifecycle
phase, failure, and current authentication status; it contains no license/session credential fields.

`stop()` requests a client-thread shutdown and returns the same observation as `stopped()`. The latter
also completes on terminal authentication failure/revocation, allowing the host to retire its resource
layer automatically. Initialization/auth failures remain the exceptional completion cause; cleanup
failures are preserved as suppressed causes. A normal stop while still waiting for authentication
cancels readiness without pretending the runtime became active.

Successful stop completion covers local cleanup, core/scoped callback drainage, and observed auth
worker termination. The auth termination observer is bounded to 30 seconds. Exceptional stop completion
means the host must inspect the failure; it is not proof that cleanup succeeded. Neither outcome proves
JVM class unloading, queued arbitrary payload-task completion, retired render-frame data, or GPU command
quiescence. A resource/loader owner must handle those additional boundaries separately.

`initializeAfterGameStarted()` remains as a legacy void entrypoint. New hosts should use the facade and
its stages. Repeated starts on one handle do not duplicate initialization. A failed second Celeste
instance does not own or stop the first. Independent existing startup copies in another classloader
still require the host's global compatibility/preflight checks.

Run the deterministic lifecycle state checks with
`-I beta-lab/runtime-lifecycle/check.init.gradle betaRuntimeLifecycleCheck`. Run the existing auth
lifecycle runner for the production bootstrap's failure-observer and worker-stop checks. These are
offline checks, not an in-game startup/unload test.
