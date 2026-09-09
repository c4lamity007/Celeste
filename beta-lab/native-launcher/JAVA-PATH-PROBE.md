# Offline Java launcher path probe

```powershell
python beta-lab/native-launcher/check_java_paths.py --java-home 'C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
```

This compiles a tiny Java 21 `OfflinePathProbe`, packages its class into fixture
JARs and invokes `java -jar`. It never loads the real agent, calls the Attach API,
finds a PID, reads credentials, accesses the network or changes a game instance.
All generated files and detailed `results.json`/logs stay under an isolated,
timestamped `build/module-qa/java-paths-*` directory. Nothing is deleted.

Cases compare ordinary Win32 and verbatim `\\?\` paths containing spaces, Latin
Unicode, Japanese/Omega characters, and an ASCII path longer than 315 characters.
An additional ASCII-launcher control passes target paths as UTF-8/base64 data,
then verifies `Path.of(...).toRealPath()`, `JarFile` access and `Files.isSameFile`
inside Java. This separates JVM launch argument encoding from NIO path handling.
There is no available authorized UNC share in this fixture, so UNC runtime access
is explicitly untested.

Exit codes:

- `0`: all ordinary JAR launches and NIO controls pass.
- `1`: an ordinary spaces/Latin/long baseline or NIO identity control failed.
- `2`: baseline and NIO controls pass, but ordinary non-native-codepage Unicode
  launch fails; the report retains that compatibility limitation.

Verbatim launch failures are reported as expected observations, not counted as a
baseline failure. The test does not call the launcher's Rust conversion function;
its purpose is to establish the JVM boundary behavior independently.

Observed with Adoptium Java 21.0.11 on this Windows host (native encoding Cp1252):
ordinary paths with spaces, Latin Unicode and a 328-character ASCII path launch.
Their verbatim counterparts reach main-class lookup and fail with
`ClassNotFoundException`. Japanese/Omega names fail earlier in direct `-jar`
because native command-line decoding substitutes unsupported characters. All
NIO controls succeed and normalize ordinary/verbatim forms to the same ordinary
real path. No claim about a live attachment follows from these offline results.
