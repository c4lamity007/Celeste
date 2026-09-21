use proc_macro2::{TokenStream, TokenTree, Group, Delimiter};
use quote::{quote, ToTokens};
use sha2::{Digest, Sha256};
use std::{fs, path::Path};
use syn::{parse_quote, punctuated::Punctuated, visit_mut::{self, VisitMut}, Expr, Lit, Token};
use syn::parse::Parser;

#[derive(Default)]
struct Protect {
    values: Vec<String>,
}

fn release_name(value: &str) -> Option<&'static str> {
    match value {
        "BASE_URL" => Some("base_url"),
        "SERVER_KEY" => Some("server_key"),
        "LAUNCHER_CHANNEL_ID" => Some("launcher_channel_id"),
        "LAUNCHER_CHANNEL_DIGEST" => Some("launcher_channel_digest"),
        "LAUNCHER_PROTOCOL_VERSION" => Some("launcher_protocol_version"),
        _ => None,
    }
}

fn release_tokens(input: TokenStream) -> TokenStream {
    let values: Vec<_> = input.into_iter().collect();
    let mut output = TokenStream::new();
    for (index, value) in values.iter().enumerate() {
        match value {
            TokenTree::Group(group) => {
                let mut replaced = Group::new(group.delimiter(), release_tokens(group.stream()));
                replaced.set_span(group.span());
                output.extend([TokenTree::Group(replaced)]);
            }
            TokenTree::Ident(ident) if index >= 3
                && values[index - 3].to_string() == "release"
                && values[index - 2].to_string() == ":"
                && values[index - 1].to_string() == ":" => {
                if let Some(name) = release_name(&ident.to_string()) {
                    output.extend([TokenTree::Ident(syn::Ident::new(name, ident.span()))]);
                    output.extend([TokenTree::Group(Group::new(Delimiter::Parenthesis, TokenStream::new()))]);
                } else { output.extend([value.clone()]); }
            }
            _ => output.extend([value.clone()]),
        }
    }
    output
}

fn test_only(attributes: &[syn::Attribute]) -> bool {
    attributes.iter().any(|a| a.path().is_ident("test")
        || a.path().is_ident("cfg") && a.meta.to_token_stream().to_string() == "cfg (test)")
}

impl VisitMut for Protect {
    fn visit_attribute_mut(&mut self, _: &mut syn::Attribute) {}
    fn visit_item_const_mut(&mut self, _: &mut syn::ItemConst) {}
    fn visit_item_static_mut(&mut self, _: &mut syn::ItemStatic) {}
    fn visit_item_mod_mut(&mut self, node: &mut syn::ItemMod) {
        if !test_only(&node.attrs) { visit_mut::visit_item_mod_mut(self, node); }
    }
    fn visit_item_fn_mut(&mut self, node: &mut syn::ItemFn) {
        if node.sig.constness.is_none() && !test_only(&node.attrs) {
            visit_mut::visit_item_fn_mut(self, node);
        }
    }
    fn visit_expr_mut(&mut self, node: &mut Expr) {
        if matches!(node, Expr::Const(_)) { return; }
        if let Expr::Lit(literal) = node {
            if let Lit::Str(value) = &literal.lit {
                if !value.value().is_empty() {
                    let original = value.clone();
                    self.values.push(value.value());
                    *node = parse_quote!(native_protection::text!(#original));
                    return;
                }
            }
        }
        visit_mut::visit_expr_mut(self, node);
    }
    fn visit_expr_match_mut(&mut self, node: &mut syn::ExprMatch) {
        visit_mut::visit_expr_match_mut(self, node);
        for arm in &mut node.arms {
            if arm.guard.is_some() { continue; }
            let patterns = match &arm.pat {
                syn::Pat::Or(value) => value.cases.iter().collect::<Vec<_>>(),
                value => vec![value],
            };
            let mut literals = Vec::new();
            for pattern in patterns {
                match pattern {
                    syn::Pat::Lit(syn::ExprLit { lit: Lit::Str(value), .. }) => literals.push(value.clone()),
                    _ => { literals.clear(); break; }
                }
            }
            if literals.is_empty() { continue; }
            let binding = syn::Ident::new(&format!("__dax_string_pattern_{}", self.values.len()), proc_macro2::Span::call_site());
            for value in &literals { self.values.push(value.value()); }
            arm.pat = parse_quote!(#binding);
            let condition: Expr = parse_quote!([#(native_protection::text!(#literals)),*].contains(&#binding));
            arm.guard = Some((Default::default(), Box::new(condition)));
        }
    }
    fn visit_macro_mut(&mut self, node: &mut syn::Macro) {
        let name = node.path.segments.last().unwrap().ident.to_string();
        if name == "json" {
            node.tokens = self.json_tokens(node.tokens.clone());
            return;
        }
        if matches!(name.as_str(), "text" | "include_bytes" | "include_str" | "concat" | "env" | "option_env" | "matches") {
            return;
        }
        let parser = Punctuated::<Expr, Token![,]>::parse_terminated;
        if let Ok(mut arguments) = parser.parse2(node.tokens.clone()) {
            let first = if matches!(name.as_str(), "format" | "print" | "println" | "eprint" | "eprintln" | "panic") { 1 }
                else if matches!(name.as_str(), "write" | "writeln") { 2 }
                else if matches!(name.as_str(), "bail" | "anyhow") { 1 }
                else if name == "ensure" { 2 }
                else if name == "vec" { 0 }
                else { return; };
            let message_index = if name == "ensure" { Some(1) }
                else if matches!(name.as_str(), "bail" | "anyhow") { Some(0) }
                else { None };
            if let Some(index) = message_index {
                if arguments.len() == index + 1 {
                    if let Some(Expr::Lit(syn::ExprLit { lit: Lit::Str(literal), .. })) = arguments.iter().nth(index) {
                        if !literal.value().contains(['{', '}']) {
                            let literal = literal.clone();
                            self.values.push(literal.value());
                            let mut prefix: Vec<_> = arguments.iter().take(index).cloned().collect();
                            for argument in &mut prefix { self.visit_expr_mut(argument); }
                            node.tokens = if prefix.is_empty() {
                                quote!("{}", native_protection::text!(#literal))
                            } else { quote!(#(#prefix,)* "{}", native_protection::text!(#literal)) };
                            return;
                        }
                    }
                }
            }
            for (index, argument) in arguments.iter_mut().enumerate() {
                if index >= first || name == "ensure" && index == 0 { self.visit_expr_mut(argument); }
            }
            node.tokens = arguments.into_token_stream();
        }
    }
}

impl Protect {
    fn json_tokens(&mut self, tokens: TokenStream) -> TokenStream {
        let mut output = TokenStream::new();
        let values: Vec<_> = tokens.into_iter().collect();
        for (index, token) in values.iter().enumerate() {
            match token {
                TokenTree::Literal(literal) => {
                    if let Ok(literal) = syn::parse2::<syn::LitStr>(literal.to_token_stream()) {
                        self.values.push(literal.value());
                        output.extend(quote!((native_protection::text!(#literal))));
                    } else { output.extend([token.clone()]); }
                }
                TokenTree::Group(group) if index == 0 || values[index - 1].to_string() != "!" => {
                    let mut group_out = Group::new(group.delimiter(), self.json_tokens(group.stream()));
                    group_out.set_span(group.span());
                    output.extend([TokenTree::Group(group_out)]);
                }
                _ => output.extend([token.clone()]),
            }
        }
        output
    }
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mut records = Vec::new();
    for name in std::env::args().skip(1) {
        let path = Path::new(&name).canonicalize()?;
        if path.extension().and_then(|s| s.to_str()) != Some("rs") { return Err("Rust files only".into()); }
        let original = fs::read_to_string(&path)?;
        let mut file = syn::parse_file(&original)?;
        let mut visitor = Protect::default();
        visitor.visit_file_mut(&mut file);
        let updated = release_tokens(file.into_token_stream()).to_string() + "\n";
        syn::parse_file(&updated)?;
        fs::write(&path, &updated)?;
        records.push(serde_json::json!({
            "file": path.file_name().unwrap().to_string_lossy(),
            "before": format!("{:x}", Sha256::digest(original.as_bytes())),
            "after_unformatted": format!("{:x}", Sha256::digest(updated.as_bytes())),
            "protected_sites": visitor.values.len(),
            "protected_values": visitor.values,
        }));
    }
    println!("{}", serde_json::to_string_pretty(&records)?);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expressions_wrapped_but_compiletime_syntax_preserved() {
        let mut file: syn::File = syn::parse_quote! {
            #[path = "other.rs"] mod other;
            const KEEP: &str = "constant";
            fn run() { sink("runtime"); format!("prefix {}", "argument"); bail!("failure"); }
            #[cfg(test)] mod tests { fn fixture() { sink("fixture"); } }
        };
        let mut visitor = Protect::default();
        visitor.visit_file_mut(&mut file);
        assert_eq!(visitor.values, ["runtime", "argument", "failure"]);
        let output = file.to_token_stream().to_string();
        assert!(output.contains("const KEEP : & str = \"constant\""));
        assert!(output.contains("path = \"other.rs\""));
        assert!(output.contains("format ! (\"prefix {}\""));
        assert!(output.contains("sink (\"fixture\")"));
    }

    #[test]
    fn release_calls_rewritten_inside_nested_macros_without_touching_other_names() {
        let input: TokenStream = "json!({\"key\": crate::release::BASE_URL, \"other\": other::BASE_URL})".parse().unwrap();
        let value = release_tokens(input).to_string();
        assert!(value.contains("release :: base_url ()"));
        assert!(value.contains("other :: BASE_URL"));
    }

    #[test]
    fn ensure_condition_and_non_test_build_functions_are_protected() {
        let mut file: syn::File = syn::parse_quote! {
            #[cfg(not(test))] fn run() { ensure!(text("claim") == "value", "message"); }
        };
        let mut visitor = Protect::default();
        visitor.visit_file_mut(&mut file);
        assert_eq!(visitor.values, ["message", "claim", "value"]);
        let once = file.to_token_stream().to_string();
        let mut again = Protect::default();
        again.visit_file_mut(&mut file);
        assert!(again.values.is_empty());
        assert_eq!(once, file.to_token_stream().to_string());
    }

    #[test]
    fn json_wraps_dynamic_keys_values_and_calls_without_changing_format_macros() {
        let mut file: syn::File = syn::parse_quote! {
            fn run() { json!({"key": "value", "nested": [get("field"), format!("{}", 1)]}); }
        };
        let mut visitor = Protect::default();
        visitor.visit_file_mut(&mut file);
        assert_eq!(visitor.values, ["key", "value", "nested", "field"]);
        let once = file.to_token_stream().to_string();
        assert!(once.contains("format ! (\"{}\" , 1)"));
        let mut again = Protect::default();
        again.visit_file_mut(&mut file);
        assert!(again.values.is_empty());
        assert_eq!(once, file.to_token_stream().to_string());
    }

    #[test]
    fn simple_string_match_arms_become_cached_guards() {
        let mut file: syn::File = syn::parse_quote! {
            fn run(value: &str) -> u8 { match value { "first" | "second" => 1, "guarded" if test() => 2, _ => 3 } }
        };
        let mut visitor = Protect::default();
        visitor.visit_file_mut(&mut file);
        assert_eq!(visitor.values, ["first", "second"]);
        let once = file.to_token_stream().to_string();
        let mut again = Protect::default();
        again.visit_file_mut(&mut file);
        assert!(again.values.is_empty());
        assert_eq!(once, file.to_token_stream().to_string());
    }
}
