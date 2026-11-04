fn main() {
    // The crate compiles source files outside its own directory. Track edits to
    // these inputs explicitly so local evaluations cannot reuse an older policy.
    println!("cargo:rerun-if-changed=../../windows/crates/server/src/ai_assist");
    println!("cargo:rerun-if-changed=../../windows/crates/shared/src/conversion_policy.rs");
}
