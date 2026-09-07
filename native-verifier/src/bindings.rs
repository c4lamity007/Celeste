use crate::{InstallCredential, is_unauthorized};
use anyhow::{Result, bail};
use serde_json::Value;
use std::collections::HashSet;
use std::path::PathBuf;

pub(crate) struct Candidate {
    pub path: PathBuf,
    pub selected_instance: bool,
}

// Loading only validates the local, DPAPI-protected, server-signed certificate.
// Every candidate still has to authenticate with the current hardware on the server.
pub(crate) fn reuse<L, A>(
    candidates: Vec<Candidate>,
    load: L,
    authenticate: A,
) -> Result<Option<(InstallCredential, Value)>>
where
    L: FnMut(&std::path::Path) -> Result<InstallCredential>,
    A: FnMut(&InstallCredential) -> Result<Value>,
{
    reuse_for_key(candidates, false, load, authenticate)
}

pub(crate) fn reuse_for_key<L, A>(
    candidates: Vec<Candidate>,
    explicit_key: bool,
    mut load: L,
    mut authenticate: A,
) -> Result<Option<(InstallCredential, Value)>>
where
    L: FnMut(&std::path::Path) -> Result<InstallCredential>,
    A: FnMut(&InstallCredential) -> Result<Value>,
{
    let mut loaded = Vec::new();
    let mut selected_uids = HashSet::new();
    let mut load_error = None;
    for candidate in candidates {
        match load(&candidate.path) {
            Ok(credential) => {
                if candidate.selected_instance {
                    selected_uids.insert(credential.uid);
                }
                loaded.push(credential);
            }
            Err(error) if candidate.selected_instance => load_error = Some(error),
            Err(_) => {}
        }
    }
    if !explicit_key && selected_uids.len() > 1 {
        bail!(
            "This instance contains more than one saved license. Contact support to select the correct activation."
        );
    }
    if explicit_key {
        // The server checks the supplied key against every candidate identity.
        // Stale local UID selection must not prevent a deliberate license change.
    } else if let Some(uid) = selected_uids.iter().next() {
        loaded.retain(|credential| credential.uid == *uid);
    } else if loaded
        .iter()
        .map(|credential| credential.uid)
        .collect::<HashSet<_>>()
        .len()
        > 1
    {
        bail!(
            "More than one saved license was found. Select the original Minecraft instance for the license you want to use."
        );
    }
    let mut attempted = HashSet::new();
    let mut rejected = None;
    for credential in loaded {
        if !attempted.insert(credential.device_id.clone()) {
            continue;
        }
        match authenticate(&credential) {
            Ok(session) => return Ok(Some((credential, session))),
            Err(error) if is_unauthorized(&error) || crate::is_product_denied(&error) => {
                rejected = Some(error)
            }
            Err(error) => return Err(error),
        }
    }
    if let Some(error) = rejected {
        return Err(
            error.context("The saved activation was rejected. Enter your current Celeste license")
        );
    }
    if let Some(error) = load_error.filter(|_| !explicit_key) {
        return Err(
            error.context("The saved activation could not be opened under this Windows account")
        );
    }
    Ok(None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::anyhow;
    use serde_json::json;

    fn credential(id: &str, uid: u64) -> InstallCredential {
        InstallCredential {
            device_id: id.into(),
            uid,
            signing_private: String::new(),
            encryption_private: String::new(),
            token_seed: String::new(),
            certificate: String::new(),
            game_uuid: String::new(),
            game_name: String::new(),
        }
    }

    fn candidates(paths: &[(&str, bool)]) -> Vec<Candidate> {
        paths
            .iter()
            .map(|(path, selected)| Candidate {
                path: PathBuf::from(path),
                selected_instance: *selected,
            })
            .collect()
    }

    #[test]
    fn stale_installer_recovers_valid_runtime_without_enrollment() {
        let mut attempts = Vec::new();
        let found = reuse(
            candidates(&[("installer", true), ("runtime", true)]),
            |path| Ok(credential(path.to_str().unwrap(), 10)),
            |saved| {
                attempts.push(saved.device_id.clone());
                if saved.device_id == "installer" {
                    Err(crate::http_failure(401, "/v1/installer/start", "denied"))
                } else {
                    Ok(json!({"grant":"verified"}))
                }
            },
        )
        .unwrap()
        .unwrap();
        assert_eq!(found.0.device_id, "runtime");
        assert_eq!(attempts, ["installer", "runtime"]);
    }

    #[test]
    fn another_instance_or_user_cache_reuses_same_device() {
        let found = reuse(
            candidates(&[("user-cache", false)]),
            |_| Ok(credential("same-device", 10)),
            |_| Ok(json!({})),
        )
        .unwrap()
        .unwrap();
        assert_eq!(found.0.device_id, "same-device");
    }

    #[test]
    fn corrupt_installer_does_not_hide_valid_legacy_runtime() {
        let found = reuse(
            candidates(&[("broken", true), ("runtime", true)]),
            |path| {
                if path == std::path::Path::new("broken") {
                    Err(anyhow!("invalid envelope"))
                } else {
                    Ok(credential("runtime", 10))
                }
            },
            |_| Ok(json!({})),
        )
        .unwrap()
        .unwrap();
        assert_eq!(found.0.device_id, "runtime");
    }

    #[test]
    fn duplicate_device_is_authenticated_once() {
        let mut count = 0;
        let result = reuse(
            candidates(&[("installer", true), ("runtime", true), ("cache", false)]),
            |_| Ok(credential("same", 10)),
            |_| {
                count += 1;
                Err(crate::http_failure(401, "/v1/installer/start", "denied"))
            },
        );
        assert!(result.is_err());
        assert_eq!(count, 1);
    }

    #[test]
    fn selected_instance_never_switches_to_another_license() {
        let mut attempts = Vec::new();
        let result = reuse(
            candidates(&[("selected", true), ("other", false)]),
            |path| {
                Ok(credential(
                    path.to_str().unwrap(),
                    if path == std::path::Path::new("selected") {
                        10
                    } else {
                        7
                    },
                ))
            },
            |saved| {
                attempts.push(saved.uid);
                Err(crate::http_failure(401, "/v1/installer/start", "denied"))
            },
        );
        assert!(result.is_err());
        assert_eq!(attempts, [10]);
    }

    #[test]
    fn ambiguous_licenses_require_original_instance() {
        let result = reuse(
            candidates(&[("one", false), ("two", false)]),
            |path| {
                Ok(credential(
                    path.to_str().unwrap(),
                    if path == std::path::Path::new("one") {
                        10
                    } else {
                        7
                    },
                ))
            },
            |_| panic!("ambiguous credentials must not authenticate"),
        );
        assert!(
            result
                .unwrap_err()
                .to_string()
                .contains("original Minecraft instance")
        );
    }

    #[test]
    fn network_error_does_not_attempt_other_devices() {
        let mut count = 0;
        let result = reuse(
            candidates(&[("one", true), ("two", true)]),
            |path| Ok(credential(path.to_str().unwrap(), 10)),
            |_| {
                count += 1;
                Err(anyhow!("offline"))
            },
        );
        assert_eq!(result.unwrap_err().to_string(), "offline");
        assert_eq!(count, 1);
    }

    #[test]
    fn no_saved_binding_can_enroll() {
        let result = reuse(Vec::new(), |_| panic!("no load"), |_| panic!("no auth"));
        assert!(result.unwrap().is_none());
    }

    #[test]
    fn unreadable_unrelated_profile_does_not_block_first_activation() {
        let result = reuse(
            candidates(&[("another-windows-account", false)]),
            |_| Err(anyhow!("DPAPI rejected")),
            |_| panic!("no readable credential"),
        );
        assert!(result.unwrap().is_none());
    }
    #[test]
    fn explicit_key_tries_other_uid_after_revoked_or_wrong_product_candidate() {
        let mut attempts = Vec::new();
        let found = reuse_for_key(
            candidates(&[("old", true), ("plus", false), ("both", false)]),
            true,
            |path| {
                Ok(credential(
                    path.to_str().unwrap(),
                    if path.to_str() == Some("old") { 1 } else { 2 },
                ))
            },
            |saved| {
                attempts.push(saved.device_id.clone());
                match saved.device_id.as_str() {
                    "old" => Err(crate::http_failure(
                        401,
                        "/v1/installer/start",
                        "key mismatch",
                    )),
                    "plus" => Err(crate::http_failure(
                        403,
                        "/v1/installer/start",
                        "product denied",
                    )),
                    _ => Ok(json!({"product":"celeste"})),
                }
            },
        )
        .unwrap()
        .unwrap();
        assert_eq!(found.0.device_id, "both");
        assert_eq!(attempts, ["old", "plus", "both"]);
    }
    #[test]
    fn explicit_key_can_enroll_after_unreadable_selected_identity() {
        assert!(
            reuse_for_key(
                candidates(&[("bad", true)]),
                true,
                |_| Err(anyhow!("bad DPAPI")),
                |_| panic!("unreadable")
            )
            .unwrap()
            .is_none()
        );
    }
    #[test]
    fn network_failure_with_explicit_key_never_falls_through_to_enrollment() {
        let err = reuse_for_key(
            candidates(&[("one", true)]),
            true,
            |_| Ok(credential("one", 1)),
            |_| Err(anyhow!("offline")),
        )
        .unwrap_err();
        assert_eq!(err.to_string(), "offline");
    }
}
