use adblock::lists::ParseOptions;
use adblock::resources::{PermissionMask, Resource, ResourceType};
use adblock::{Engine, FilterSet};
use base64::{engine::Engine as _, prelude::BASE64_STANDARD};
use mediaflow_adblock::parse_baseline_lists;
use std::fs;
use std::path::PathBuf;
use std::time::Instant;

const PLAYER_SCRIPTLET: &str = include_str!("../../resources/mediaflow-youtube-player.js");
const PLAYER_SCRIPTLET_NAME: &str = "mediaflow-youtube-player.js";
const PLAYER_SCRIPTLET_RULE: &str = "youtube.com##+js(mediaflow-youtube-player)";
const PLAYER_SCRIPTLET_PERMISSION: PermissionMask = PermissionMask::from_bits(1);
const PLAYER_SCRIPTLET_MAX_BYTES: usize = 16 * 1024;
const YOUTUBE_ORIGINS: [&str; 3] = [
    "https://youtube.com",
    "https://www.youtube.com",
    "https://m.youtube.com",
];

fn add_trusted_player_rule(filters: &mut FilterSet) {
    filters.add_filter_list(
        PLAYER_SCRIPTLET_RULE.to_owned(),
        ParseOptions {
            permissions: PLAYER_SCRIPTLET_PERMISSION,
            ..ParseOptions::default()
        },
    );
}

fn register_trusted_player_resource(engine: &mut Engine) {
    // The app updater never calls this or grants this permission. Template resources
    // cannot be used for redirects. This resource has no arguments or dependencies.
    engine.use_resources([Resource {
        name: PLAYER_SCRIPTLET_NAME.to_owned(),
        aliases: vec![],
        kind: ResourceType::Template,
        content: BASE64_STANDARD.encode(PLAYER_SCRIPTLET),
        dependencies: vec![],
        permission: PLAYER_SCRIPTLET_PERMISSION,
    }]);
}

fn render_player_scriptlet(engine: &Engine) -> Result<String, String> {
    let output = engine
        .url_cosmetic_resources(YOUTUBE_ORIGINS[0])
        .injected_script;
    if output.len() > PLAYER_SCRIPTLET_MAX_BYTES
        || output != format!("try {{\n{PLAYER_SCRIPTLET}\n}} catch ( e ) {{ }}\n")
        || YOUTUBE_ORIGINS[1..]
            .iter()
            .any(|origin| engine.url_cosmetic_resources(origin).injected_script != output)
    {
        return Err("unexpected document-start scriptlet from bundled filters".to_owned());
    }
    Ok(output)
}

fn main() {
    if let Err(error) = run() {
        eprintln!("baseline_compile_failed={error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let mut args = std::env::args_os().skip(1);
    let easylist_path = PathBuf::from(args.next().ok_or("missing EasyList path")?);
    let easyprivacy_path = PathBuf::from(args.next().ok_or("missing EasyPrivacy path")?);
    let output_path = PathBuf::from(args.next().ok_or("missing output path")?);
    let stats_path = PathBuf::from(args.next().ok_or("missing statistics output path")?);
    let script_path = PathBuf::from(
        args.next()
            .ok_or("missing document-start script output path")?,
    );
    if args.next().is_some() {
        return Err("unexpected extra argument".to_owned());
    }

    let started = Instant::now();
    let easylist = fs::read_to_string(&easylist_path).map_err(|error| error.to_string())?;
    let easyprivacy = fs::read_to_string(&easyprivacy_path).map_err(|error| error.to_string())?;
    let (mut filters, stats) = parse_baseline_lists(easylist, easyprivacy)
        .ok_or("baseline lists failed structural, size, or parser validation")?;
    add_trusted_player_rule(&mut filters);
    let mut engine = Engine::new_with_filter_set(filters);
    register_trusted_player_resource(&mut engine);
    let script = render_player_scriptlet(&engine)?;
    // Serialized engines do not include resource storage. Runtime filtering has
    // no executable resource and the only rendering step is the one above.
    let bytes = engine.serialize();
    if bytes.is_empty() || bytes.len() > 32 * 1024 * 1024 {
        return Err("compiled baseline size is outside the accepted bounds".to_owned());
    }

    if let Some(parent) = output_path.parent() {
        fs::create_dir_all(parent).map_err(|error| error.to_string())?;
    }
    let temporary = output_path.with_extension("dat.tmp");
    fs::write(&temporary, &bytes).map_err(|error| error.to_string())?;
    if output_path.exists() {
        fs::remove_file(&output_path).map_err(|error| error.to_string())?;
    }
    fs::rename(&temporary, &output_path).map_err(|error| error.to_string())?;
    let stats_json = serde_json::to_vec(&stats).map_err(|error| error.to_string())?;
    if stats_path.exists() {
        fs::remove_file(&stats_path).map_err(|error| error.to_string())?;
    }
    fs::write(&stats_path, stats_json).map_err(|error| error.to_string())?;
    fs::write(&script_path, &script).map_err(|error| error.to_string())?;

    println!(
        "network_rules={} cosmetic_rules={} invalid_rules={} compiled_bytes={} scriptlet_bytes={} compile_ms={}",
        stats.network_rules(),
        stats.cosmetic_rules(),
        stats.invalid_rules(),
        bytes.len(),
        script.len(),
        started.elapsed().as_millis()
    );
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_permissioned_rule_can_render_the_bundled_resource() {
        let mut filters = FilterSet::new(false);
        filters.add_filter_list(PLAYER_SCRIPTLET_RULE.to_owned(), ParseOptions::default());
        let mut engine = Engine::new_with_filter_set(filters);
        register_trusted_player_resource(&mut engine);
        assert!(engine
            .url_cosmetic_resources(YOUTUBE_ORIGINS[0])
            .injected_script
            .is_empty());
    }

    #[test]
    fn build_renders_only_the_expected_script_and_runtime_has_no_resource() {
        let mut filters = FilterSet::new(false);
        add_trusted_player_rule(&mut filters);
        let mut engine = Engine::new_with_filter_set(filters);
        register_trusted_player_resource(&mut engine);
        let rendered = render_player_scriptlet(&engine).expect("trusted output for all origins");
        assert!(!rendered.is_empty());
        for site in ["https://example.com", "https://youtube.com.example.com"] {
            assert!(engine
                .url_cosmetic_resources(site)
                .injected_script
                .is_empty());
        }

        let mut restored = Engine::default();
        restored
            .deserialize(&engine.serialize())
            .expect("runtime snapshot");
        assert!(restored
            .url_cosmetic_resources(YOUTUBE_ORIGINS[0])
            .injected_script
            .is_empty());
    }

    #[test]
    fn brave_scriptlet_exception_prevents_generation_for_that_host() {
        let mut filters = FilterSet::new(false);
        add_trusted_player_rule(&mut filters);
        filters.add_filter_list(
            "m.youtube.com#@#+js(mediaflow-youtube-player)".to_owned(),
            ParseOptions::default(),
        );
        let mut engine = Engine::new_with_filter_set(filters);
        register_trusted_player_resource(&mut engine);
        assert!(engine
            .url_cosmetic_resources("https://m.youtube.com")
            .injected_script
            .is_empty());
        assert!(render_player_scriptlet(&engine).is_err());
    }
}
