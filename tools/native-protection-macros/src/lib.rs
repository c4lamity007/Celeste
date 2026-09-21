use aes_gcm::{Aes256Gcm, KeyInit, Nonce, aead::{Aead, Payload}};
use proc_macro::TokenStream;
use quote::quote;
use rand::{rngs::OsRng, RngCore};
use syn::{parse_macro_input, LitStr};

#[proc_macro]
pub fn text(input: TokenStream) -> TokenStream {
    let literal = parse_macro_input!(input as LitStr);
    let mut key = [0_u8; 32];
    let mut first = [0_u8; 32];
    let mut nonce = [0_u8; 12];
    let mut context = [0_u8; 16];
    OsRng.fill_bytes(&mut key);
    OsRng.fill_bytes(&mut first);
    OsRng.fill_bytes(&mut nonce);
    OsRng.fill_bytes(&mut context);
    let mut second = [0_u8; 32];
    for index in 0..32 { second[index] = key[index] ^ first[index]; }
    let ciphertext = Aes256Gcm::new_from_slice(&key).expect("native text key")
        .encrypt(Nonce::from_slice(&nonce), Payload { msg: literal.value().as_bytes(), aad: &context })
        .expect("native text encryption");
    quote!({
        static VALUE: ::std::sync::OnceLock<::std::string::String> = ::std::sync::OnceLock::new();
        VALUE.get_or_init(|| {
            ::native_protection::decode(
                &[#(#ciphertext),*],
                &[#(#first),*],
                &[#(#second),*],
                &[#(#nonce),*],
                &[#(#context),*],
            ).unwrap_or_else(|_| ::std::process::abort())
        }).as_str()
    }).into()
}

