# Native literal protection

Original project code is proprietary under the root LICENSE.txt. RustCrypto,
syn, quote, rand, and their dependencies retain their own licenses.

`native_protection::text!("value")` encrypts a UTF-8 literal during compilation
using AES-256-GCM with an independent OS-random key, nonce, and authenticated
context for each macro expansion. Generated target code contains ciphertext and
two key shares, not the original literal. Runtime decoding is authenticated and
initialized once per call site using a thread-safe cache. Integrity failure
aborts; there is no plaintext fallback. Fresh compilation randomizes records;
Cargo can reuse an unchanged compilation artifact.

This is static string obfuscation, not confidential key storage or remote client
attestation. Both key shares ship with the program, and used strings remain
recoverable from runtime memory. The split key is not an additional encryption
algorithm or a claim of twice the security. No debugger detection, termination
of other processes, executable-memory packing, or import-table manipulation is used.

Protect runtime string expressions explicitly. Rust compile-time attributes,
match patterns, format syntax, and third-party strings need separate handling;
the operating system's required native structures and import names remain
readable. Scan final release PE bytes, including UTF-16 representations, rather
than treating source macro use or symbol stripping as proof of coverage.

Cryptographic implementation: https://docs.rs/aes-gcm/0.10.3/aes_gcm/
Cache contract: https://doc.rust-lang.org/std/sync/struct.OnceLock.html
