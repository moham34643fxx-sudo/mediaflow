use adblock::lists::{parse_filter, ParseOptions, ParsedLine};
use adblock::{Engine, FilterSet};
use jni::objects::{JByteArray, JObject, JString};
use jni::sys::{jboolean, jlong, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::panic::{catch_unwind, AssertUnwindSafe};

const MAX_RULES_BYTES: usize = 8 * 1024 * 1024;
const MAX_COMPILED_BYTES: usize = 32 * 1024 * 1024;
const MAX_COSMETIC_INPUT_ITEMS: usize = 128;
const MAX_COSMETIC_ITEM_BYTES: usize = 256;

#[derive(Default, Deserialize, Serialize)]
pub struct ListStats {
    pub network_rules: usize,
    pub cosmetic_rules: usize,
    pub invalid_rules: usize,
}

impl ListStats {
    fn total_rules(&self) -> usize {
        self.network_rules + self.cosmetic_rules + self.invalid_rules
    }
}

#[derive(Default, Deserialize, Serialize)]
pub struct EngineStats {
    pub lists: Vec<ListStats>,
}

impl EngineStats {
    pub fn network_rules(&self) -> usize {
        self.lists.iter().map(|list| list.network_rules).sum()
    }

    pub fn cosmetic_rules(&self) -> usize {
        self.lists.iter().map(|list| list.cosmetic_rules).sum()
    }

    pub fn invalid_rules(&self) -> usize {
        self.lists.iter().map(|list| list.invalid_rules).sum()
    }

    pub fn accepted_rules(&self) -> usize {
        self.network_rules() + self.cosmetic_rules()
    }

    pub fn is_usable_baseline(&self) -> bool {
        self.lists.len() == 2
            && self.lists.iter().all(|list| {
                let attempted = list.total_rules();
                list.network_rules >= 1_000
                    && attempted > 0
                    && list.invalid_rules.saturating_mul(4) <= attempted
            })
            && self.network_rules() >= 10_000
            && self.cosmetic_rules() >= 500
    }
}

pub struct CompiledEngine {
    pub engine: Engine,
    pub stats: EngineStats,
}

fn validate_list(text: &str) -> Option<ListStats> {
    if text.is_empty() || text.len() > MAX_RULES_BYTES || !text.starts_with("[Adblock Plus") {
        return None;
    }

    let mut stats = ListStats::default();
    let options = ParseOptions::default();
    for line in text.lines() {
        let candidate = line.trim();
        if candidate.is_empty() || candidate.starts_with('!') || candidate.starts_with("[Adblock") {
            continue;
        }
        match parse_filter(candidate, false, options) {
            Ok(ParsedLine::Network(_)) => stats.network_rules += 1,
            Ok(ParsedLine::Cosmetic(_)) => stats.cosmetic_rules += 1,
            Err(_) => stats.invalid_rules += 1,
        }
    }
    Some(stats)
}

pub fn compile_lists(easylist: String, easyprivacy: String) -> Option<CompiledEngine> {
    let (filters, stats) = parse_baseline_lists(easylist, easyprivacy)?;
    Some(CompiledEngine {
        engine: Engine::new_with_filter_set(filters),
        stats,
    })
}

pub fn parse_baseline_lists(
    easylist: String,
    easyprivacy: String,
) -> Option<(FilterSet, EngineStats)> {
    let stats = EngineStats {
        lists: vec![validate_list(&easylist)?, validate_list(&easyprivacy)?],
    };
    if !stats.is_usable_baseline() {
        return None;
    }

    let mut filter_set = FilterSet::new(false);
    filter_set.add_filter_list(easylist, ParseOptions::default());
    filter_set.add_filter_list(easyprivacy, ParseOptions::default());
    Some((filter_set, stats))
}

struct NativeEngine {
    engine: Engine,
    stats: EngineStats,
}

fn read_jstring(env: &mut JNIEnv<'_>, value: JString<'_>) -> Option<String> {
    env.get_string(&value).ok().map(Into::into)
}

fn new_java_string(env: &mut JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

unsafe fn engine_ref<'a>(handle: jlong) -> Option<&'a NativeEngine> {
    if handle == 0 {
        None
    } else {
        (handle as *const NativeEngine).as_ref()
    }
}

fn create_from_lists(easylist: String, easyprivacy: String) -> jlong {
    let Some(compiled) = compile_lists(easylist, easyprivacy) else {
        return 0;
    };
    Box::into_raw(Box::new(NativeEngine {
        engine: compiled.engine,
        stats: compiled.stats,
    })) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeCreateFromLists(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    easylist: JByteArray<'_>,
    easyprivacy: JByteArray<'_>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(easylist) = env.convert_byte_array(easylist) else {
            return 0;
        };
        let Ok(easyprivacy) = env.convert_byte_array(easyprivacy) else {
            return 0;
        };
        if easylist.len() > MAX_RULES_BYTES || easyprivacy.len() > MAX_RULES_BYTES {
            return 0;
        }
        let (Ok(easylist), Ok(easyprivacy)) =
            (String::from_utf8(easylist), String::from_utf8(easyprivacy))
        else {
            return 0;
        };
        create_from_lists(easylist, easyprivacy)
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeCreateFromCompiled(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    compiled: JByteArray<'_>,
    stats_json: JString<'_>,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        let Ok(bytes) = env.convert_byte_array(compiled) else {
            return 0;
        };
        if bytes.is_empty() || bytes.len() > MAX_COMPILED_BYTES {
            return 0;
        }
        let Some(stats_json) = read_jstring(&mut env, stats_json) else {
            return 0;
        };
        if stats_json.len() > 16_384 {
            return 0;
        }
        let Ok(stats) = serde_json::from_str::<EngineStats>(&stats_json) else {
            return 0;
        };
        if !stats.is_usable_baseline() {
            return 0;
        }
        let mut engine = Engine::default();
        if engine.deserialize(&bytes).is_err() {
            return 0;
        }
        Box::into_raw(Box::new(NativeEngine { engine, stats })) as jlong
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeSerialize(
    env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jni::sys::jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = (unsafe { engine_ref(handle) }) else {
            return std::ptr::null_mut();
        };
        let bytes = engine.engine.serialize();
        if bytes.is_empty() || bytes.len() > MAX_COMPILED_BYTES {
            return std::ptr::null_mut();
        }
        let Ok(output) = env.new_byte_array(bytes.len() as i32) else {
            return std::ptr::null_mut();
        };
        let java_bytes = bytes.iter().map(|byte| *byte as i8).collect::<Vec<_>>();
        if env.set_byte_array_region(&output, 0, &java_bytes).is_err() {
            return std::ptr::null_mut();
        }
        output.into_raw()
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeStats(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = (unsafe { engine_ref(handle) }) else {
            return std::ptr::null_mut();
        };
        let Ok(json) = serde_json::to_string(&engine.stats) else {
            return std::ptr::null_mut();
        };
        new_java_string(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeShouldBlock(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    url: JString<'_>,
    source_url: JString<'_>,
    request_type: JString<'_>,
    method: JString<'_>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = (unsafe { engine_ref(handle) }) else {
            return 0;
        };
        let (Some(url), Some(source_url), Some(request_type), Some(method)) = (
            read_jstring(&mut env, url),
            read_jstring(&mut env, source_url),
            read_jstring(&mut env, request_type),
            read_jstring(&mut env, method),
        ) else {
            return 0;
        };
        if url.len() > 8_192 || source_url.len() > 8_192 {
            return 0;
        }
        let Ok(request) = adblock::request::Request::new(&url, &source_url, &request_type, &method)
        else {
            return 0;
        };
        if engine.engine.check_network_request(&request).should_block() {
            1
        } else {
            0
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeShouldBlockDetailed(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    url: JString<'_>,
    source_url: JString<'_>,
    request_type: JString<'_>,
    method: JString<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = (unsafe { engine_ref(handle) }) else {
            return std::ptr::null_mut();
        };
        let (Some(url), Some(source_url), Some(request_type), Some(method)) = (
            read_jstring(&mut env, url),
            read_jstring(&mut env, source_url),
            read_jstring(&mut env, request_type),
            read_jstring(&mut env, method),
        ) else {
            return std::ptr::null_mut();
        };
        if url.len() > 8_192 || source_url.len() > 8_192 {
            return std::ptr::null_mut();
        }
        let Ok(request) = adblock::request::Request::new(&url, &source_url, &request_type, &method)
        else {
            return std::ptr::null_mut();
        };

        let result = engine.engine.check_network_request(&request);
        let blocked = result.should_block();
        let matched_rule = if blocked {
            result.filter.as_ref()
        } else {
            result.exception.as_ref().or(result.filter.as_ref())
        };
        let rule_id = matched_rule.and_then(stable_rule_id);
        let rule_attribution = match (matched_rule.is_some(), rule_id.is_some()) {
            (false, _) => "no_match",
            (true, true) => "source_location_or_hash",
            (true, false) => "debug_metadata_unavailable",
        };
        let Ok(json) = serde_json::to_string(&serde_json::json!({
            "blocked": blocked,
            "matchedRuleId": rule_id,
            "ruleAttribution": rule_attribution,
        })) else {
            return std::ptr::null_mut();
        };
        new_java_string(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}

fn stable_rule_id(info: &adblock::sourcemap::FilterRuleDebugInfo) -> Option<String> {
    if let Some(location) = info.source_location.as_ref() {
        return Some(format!(
            "filter-list-{}-line-{}",
            location.source_index, location.line_number
        ));
    }
    let raw_line = info.raw_line.as_deref()?;
    let hash = raw_line.bytes().fold(0xcbf29ce484222325u64, |hash, byte| {
        (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3)
    });
    Some(format!("filter-hash-{hash:016x}"))
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeCosmeticResources(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    url: JString<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = (unsafe { engine_ref(handle) }) else {
            return std::ptr::null_mut();
        };
        let Some(url) = read_jstring(&mut env, url) else {
            return std::ptr::null_mut();
        };
        if url.len() > 8_192 {
            return std::ptr::null_mut();
        }
        let resources = engine.engine.url_cosmetic_resources(&url);
        // Never expose or execute filter-list scriptlets/procedural actions in WebView.
        let safe_resources = serde_json::json!({
            "hide_selectors": resources.hide_selectors,
            "exceptions": resources.exceptions,
            "generichide": resources.generichide,
        });
        let Ok(json) = serde_json::to_string(&safe_resources) else {
            return std::ptr::null_mut();
        };
        new_java_string(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeHiddenSelectors(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
    url: JString<'_>,
    classes_json: JString<'_>,
    ids_json: JString<'_>,
    exceptions_json: JString<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(engine) = (unsafe { engine_ref(handle) }) else {
            return std::ptr::null_mut();
        };
        let (Some(url), Some(classes_json), Some(ids_json), Some(exceptions_json)) = (
            read_jstring(&mut env, url),
            read_jstring(&mut env, classes_json),
            read_jstring(&mut env, ids_json),
            read_jstring(&mut env, exceptions_json),
        ) else {
            return std::ptr::null_mut();
        };
        if url.len() > 8_192
            || classes_json.len() > 32_768
            || ids_json.len() > 32_768
            || exceptions_json.len() > 32_768
        {
            return std::ptr::null_mut();
        }
        let Ok(classes) = serde_json::from_str::<Vec<String>>(&classes_json) else {
            return std::ptr::null_mut();
        };
        let Ok(ids) = serde_json::from_str::<Vec<String>>(&ids_json) else {
            return std::ptr::null_mut();
        };
        let Ok(exceptions) =
            serde_json::from_str::<std::collections::HashSet<String>>(&exceptions_json)
        else {
            return std::ptr::null_mut();
        };
        if classes.len() > MAX_COSMETIC_INPUT_ITEMS
            || ids.len() > MAX_COSMETIC_INPUT_ITEMS
            || classes
                .iter()
                .chain(ids.iter())
                .any(|value| value.len() > MAX_COSMETIC_ITEM_BYTES)
        {
            return std::ptr::null_mut();
        }
        let selectors = engine
            .engine
            .hidden_class_id_selectors(&classes, &ids, &exceptions);
        let Ok(json) = serde_json::to_string(&selectors) else {
            return std::ptr::null_mut();
        };
        new_java_string(&mut env, &json)
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_zdownloader_app_adblock_NativeAdblock_nativeDestroy(
    _env: JNIEnv<'_>,
    _this: JObject<'_>,
    handle: jlong,
) {
    if handle != 0 {
        let _ = catch_unwind(AssertUnwindSafe(|| unsafe {
            drop(Box::from_raw(handle as *mut NativeEngine));
        }));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use adblock::request::Request;

    const TEST_RULES: &str = "[Adblock Plus 2.0]\n||ads.test^$script\n@@||ads.test/allowed.js$script,domain=publisher.test\npublisher.test##.ad-slot\n##.generic-ad\n";

    fn test_engine() -> Engine {
        let mut filter_set = FilterSet::new(false);
        filter_set.add_filter_list(TEST_RULES.to_owned(), ParseOptions::default());
        Engine::new_with_filter_set(filter_set)
    }

    #[test]
    fn request_context_and_exception_rules_are_respected() {
        let engine = test_engine();
        let blocked = Request::new(
            "https://ads.test/ad.js",
            "https://publisher.test/article",
            "script",
            "GET",
        )
        .unwrap();
        let allowed = Request::new(
            "https://ads.test/allowed.js",
            "https://publisher.test/article",
            "script",
            "GET",
        )
        .unwrap();
        assert!(engine.check_network_request(&blocked).should_block());
        assert!(!engine.check_network_request(&allowed).should_block());
    }

    #[test]
    fn first_party_resources_and_main_documents_are_not_blanket_blocked() {
        let engine = test_engine();
        let first_party = Request::new(
            "https://publisher.test/app.js",
            "https://publisher.test/article",
            "script",
            "GET",
        )
        .unwrap();
        let document = Request::new(
            "https://publisher.test/article",
            "https://publisher.test/article",
            "document",
            "GET",
        )
        .unwrap();
        assert!(!engine.check_network_request(&first_party).should_block());
        assert!(!engine.check_network_request(&document).should_block());
    }

    #[test]
    fn cosmetic_rules_are_scoped_to_the_requested_site() {
        let engine = test_engine();
        let resources = engine.url_cosmetic_resources("https://publisher.test/article");
        assert!(resources.hide_selectors.contains(".ad-slot"));
        let selectors = engine.hidden_class_id_selectors(
            ["generic-ad"],
            std::iter::empty::<&str>(),
            &resources.exceptions,
        );
        assert!(selectors.iter().any(|selector| selector == ".generic-ad"));

        let other_site = engine.url_cosmetic_resources("https://unrelated.test/article");
        assert!(other_site.hide_selectors.is_empty());
    }

    #[test]
    fn bundled_lists_must_be_large_and_parseable_enough_to_activate() {
        let tiny = "[Adblock Plus 2.0]\n||ads.test^\n".to_owned();
        assert!(compile_lists(tiny.clone(), tiny).is_none());
    }

    #[test]
    fn bundled_youtube_ad_break_rule_blocks_api_but_not_content_media() {
        let compiled = compile_lists(
            include_str!("../../app/src/main/assets/adblock/easylist.txt").to_owned(),
            include_str!("../../app/src/main/assets/adblock/easyprivacy.txt").to_owned(),
        )
        .expect("The bundled filter lists must compile");

        let page = "https://www.youtube.com/watch?v=mediaflow-adblock-probe";
        let ad_break = Request::new(
            "https://www.youtube.com/youtubei/v1/player/ad_break?probe=1",
            page,
            "xmlhttprequest",
            "POST",
        )
        .unwrap();
        assert!(
            compiled
                .engine
                .check_network_request(&ad_break)
                .should_block(),
            "The bundled EasyList rule must block YouTube's ad-break API request"
        );

        let ad_media = Request::new(
            "https://ads4media.online/mediaflow-ad-probe.mp4",
            page,
            "media",
            "GET",
        )
        .unwrap();
        assert!(
            compiled
                .engine
                .check_network_request(&ad_media)
                .should_block(),
            "A third-party media request matching a bundled ad rule must be blocked"
        );

        let content_video = Request::new(
            "https://rr.googlevideo.com/videoplayback?id=content",
            page,
            "media",
            "GET",
        )
        .unwrap();
        assert!(
            !compiled
                .engine
                .check_network_request(&content_video)
                .should_block(),
            "YouTube content media must remain allowed"
        );
    }

    #[test]
    fn diagnostic_rule_id_exposes_only_a_stable_location_or_hash() {
        let with_location = adblock::sourcemap::FilterRuleDebugInfo {
            raw_line: Some("||ads.test^$script".to_owned()),
            source_location: Some(adblock::sourcemap::SourceLocation {
                source_index: 1,
                line_number: 42,
            }),
        };
        assert_eq!(
            stable_rule_id(&with_location).as_deref(),
            Some("filter-list-1-line-42")
        );

        let without_location = adblock::sourcemap::FilterRuleDebugInfo {
            raw_line: Some("||ads.test^$script".to_owned()),
            source_location: None,
        };
        let hash = stable_rule_id(&without_location).expect("raw filter has a stable id");
        assert!(hash.starts_with("filter-hash-"));
        assert!(!hash.contains("ads.test"));
        assert_eq!(stable_rule_id(&Default::default()), None);
    }
}
