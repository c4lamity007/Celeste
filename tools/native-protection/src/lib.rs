use aes_gcm::{Aes256Gcm, KeyInit, Nonce, aead::{Aead, Payload}};

pub use native_protection_macros::text;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InvalidText;

#[inline(never)]
pub fn decode(
    ciphertext: &[u8],
    first: &[u8; 32],
    second: &[u8; 32],
    nonce: &[u8; 12],
    context: &[u8; 16],
) -> Result<String, InvalidText> {
    let mut key = [0_u8; 32];
    let first = std::hint::black_box(first);
    let second = std::hint::black_box(second);
    for index in 0..32 {
        key[index] = first[index] ^ second[index];
    }
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| InvalidText)?;
    let plain = cipher.decrypt(Nonce::from_slice(nonce), Payload {
        msg: std::hint::black_box(ciphertext),
        aad: context,
    }).map_err(|_| InvalidText)?;
    String::from_utf8(plain).map_err(|_| InvalidText)
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::{rngs::OsRng, RngCore};

    fn record(value: &[u8]) -> (Vec<u8>, [u8; 32], [u8; 32], [u8; 12], [u8; 16]) {
        let mut key = [0; 32];
        let mut first = [0; 32];
        let mut nonce = [0; 12];
        let mut context = [0; 16];
        OsRng.fill_bytes(&mut key);
        OsRng.fill_bytes(&mut first);
        OsRng.fill_bytes(&mut nonce);
        OsRng.fill_bytes(&mut context);
        let mut second = [0; 32];
        for i in 0..32 { second[i] = key[i] ^ first[i]; }
        let ciphertext = Aes256Gcm::new_from_slice(&key).unwrap()
            .encrypt(Nonce::from_slice(&nonce), Payload { msg: value, aad: &context }).unwrap();
        (ciphertext, first, second, nonce, context)
    }

    #[test]
    fn roundtrip_empty_unicode_and_nul() {
        for value in ["", "javaw.exe", "Minecraft ☄", "a\0b"] {
            let (ciphertext, a, b, nonce, context) = record(value.as_bytes());
            assert_eq!(decode(&ciphertext, &a, &b, &nonce, &context).unwrap(), value);
        }
    }

    #[test]
    fn any_record_component_tamper_is_rejected() {
        let (ciphertext, a, b, nonce, context) = record(b"protected-record");
        for index in 0..ciphertext.len() {
            let mut changed = ciphertext.clone();
            changed[index] ^= 1;
            assert_eq!(decode(&changed, &a, &b, &nonce, &context), Err(InvalidText));
        }
        let mut wrong_key = a;
        wrong_key[0] ^= 1;
        assert_eq!(decode(&ciphertext, &wrong_key, &b, &nonce, &context), Err(InvalidText));
        let mut wrong_nonce = nonce;
        wrong_nonce[0] ^= 1;
        assert_eq!(decode(&ciphertext, &a, &b, &wrong_nonce, &context), Err(InvalidText));
        let mut wrong_context = context;
        wrong_context[0] ^= 1;
        assert_eq!(decode(&ciphertext, &a, &b, &nonce, &wrong_context), Err(InvalidText));
    }

    #[test]
    fn invalid_utf8_and_short_records_are_rejected() {
        let (ciphertext, a, b, nonce, context) = record(&[255]);
        assert_eq!(decode(&ciphertext, &a, &b, &nonce, &context), Err(InvalidText));
        for length in 0..16 {
            assert_eq!(decode(&ciphertext[..length], &a, &b, &nonce, &context), Err(InvalidText));
        }
    }
}

