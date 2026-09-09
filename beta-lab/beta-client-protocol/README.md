# Beta client authentication protocol checks

This fixture compiles and calls the production `AuthServiceClient`, `Crypto`,
credential and release classes against an offline HTTP transport. It uses fresh
Ed25519 test keys and verifies both real installation proofs; it never reads a
real credential, collects hardware or sends network requests.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --no-daemon --console=plain -I beta-lab/beta-client-protocol/check.init.gradle betaClientProtocolCheck
```

Checks cover the unchanged production transcript, beta catalog/release/version
binding, signatures, device and license identities, nonce freshness, required
fields, malformed signed context, wrong download rights, and beta-specific 409
wording. Malformed beta context must raise `GeneralSecurityException`, which the
separate auth lifecycle suite checks causes immediate lock rather than outage
grace. The latest focused run passed 226 checks with Java 21 warnings as errors.

Output is under ignored `build/module-qa/beta-client-protocol/`. This is protocol
verification, not an end-to-end launch against a deployed beta release.
