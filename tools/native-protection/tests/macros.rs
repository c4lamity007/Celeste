use native_protection::text;

fn shared() -> &'static str { text!("shared native string ☄") }

#[test]
fn macro_returns_original_utf8_and_stable_borrow() {
    assert_eq!(text!(""), "");
    assert_eq!(text!("a\0b"), "a\0b");
    assert_eq!(shared(), "shared native string ☄");
    assert!(std::ptr::eq(shared().as_ptr(), shared().as_ptr()));
}

#[test]
fn concurrent_initialization_is_consistent() {
    fn cold() -> &'static str { text!("cold concurrent native text") }
    let barrier = std::sync::Arc::new(std::sync::Barrier::new(32));
    let handles: Vec<_> = (0..32).map(|_| {
        let barrier = barrier.clone();
        std::thread::spawn(move || { barrier.wait(); cold() })
    }).collect();
    let values: Vec<_> = handles.into_iter().map(|h| h.join().unwrap()).collect();
    for value in &values { assert!(std::ptr::eq(*value, values[0])); }
    assert_eq!(values[0], "cold concurrent native text");
}

#[test]
fn identical_literal_sites_have_independent_caches() {
    fn first() -> &'static str { text!("independent-expansion") }
    fn second() -> &'static str { text!("independent-expansion") }
    assert_eq!(first(), second());
    assert!(!std::ptr::eq(first(), second()));
    assert!(std::ptr::eq(first(), first()));
    assert!(std::ptr::eq(second(), second()));
}
