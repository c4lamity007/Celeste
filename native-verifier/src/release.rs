pub fn base_url() -> &'static str {
    native_protection::text!("https://qb0f90cf11f4d1d52.fly.dev")
}
pub fn server_key() -> &'static str {
    native_protection::text!("rhLhkicLy3laX7_Q7rSv9OGVyrSHr1OyEZW8_hJfoKY")
}
pub fn launcher_channel_id() -> &'static str {
    native_protection::text!("a39fbbf8e5c344edb39ee82d")
}
pub fn launcher_channel_digest() -> &'static str {
    native_protection::text!("d779134f7ae0ce556f097057fbf940f266e6029eae21623bd841ca8a2ec4305b")
}
pub fn launcher_protocol_version() -> &'static str {
    native_protection::text!("1.0.0")
}
pub fn self_check(build: &str, product: &str) -> anyhow::Result<serde_json::Value> {
    use base64::Engine;
    let key = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(server_key())?;
    anyhow::ensure!(
        key.len() == 32,
        "{}",
        native_protection::text!("Invalid public verification key")
    );
    let key: [u8; 32] = key
        .try_into()
        .map_err(|_| anyhow::anyhow!("{}", native_protection::text!("Invalid key length")))?;
    ed25519_dalek::VerifyingKey::from_bytes(&key)?;
    anyhow::ensure!(
        base_url().starts_with(native_protection::text!("https://")) && !base_url().ends_with('/'),
        "{}",
        native_protection::text!("Invalid public origin")
    );
    anyhow::ensure!(
        launcher_channel_digest().len() == 64
            && launcher_channel_digest()
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b)),
        "{}",
        native_protection::text!("Invalid launcher identity")
    );
    Ok(
        serde_json :: json ! ({ (native_protection :: text ! ("schema")) : (native_protection :: text ! ("celeste-native-self-check-1")) , (native_protection :: text ! ("product")) : product , (native_protection :: text ! ("build")) : build , (native_protection :: text ! ("channel")) : launcher_channel_id () , (native_protection :: text ! ("channel_digest")) : launcher_channel_digest () , (native_protection :: text ! ("protocol")) : launcher_protocol_version () , (native_protection :: text ! ("origin")) : base_url () , (native_protection :: text ! ("public_key_bytes")) : key . len () , (native_protection :: text ! ("network_requested")) : false , (native_protection :: text ! ("credentials_accessed")) : false }),
    )
}
