use std::env;

fn main() {
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=target/azookey-server.lib");
    // link dll
    let project_dir = env::var("CARGO_MANIFEST_DIR").unwrap();
    println!("cargo:rustc-link-search={}/target/", project_dir);
    println!("cargo:rustc-link-lib=azookey-server");
}
