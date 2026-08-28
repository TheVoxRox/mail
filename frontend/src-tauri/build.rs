fn main() {
    // beta_updater_endpoint() in src/lib.rs reads this through option_env!, which
    // is resolved at compile time and therefore baked into the build cache. Cargo
    // does not track env vars used that way on its own, so without this line a
    // changed value is silently ignored until something else forces a rebuild.
    // CI never sees it (fresh checkout, cold cache); a local build would.
    println!("cargo:rerun-if-env-changed=TAURI_UPDATER_BETA_ENDPOINT");

    tauri_build::build()
}
