# Third-party license summary

| Component | Version | Declared license |
|---|---:|---|
| yt-dlp | 2026.10.26 | Unlicense |
| yt-dlp-ejs | 0.8.0 | Unlicense |
| Brave `adblock-rust` engine (`adblock` crate) | 0.13.6 | MPL-2.0 |
| EasyList and EasyPrivacy filter data | 2026-11-18 snapshot, commit `5efc5e9700917395bf1d643f6d074ef294f767df` | GPL-3.0-or-later or CC BY-SA 3.0 |
| FFmpeg Kit main-min 16 KB / FFmpeg | 6.1.4 / n6.1.4 | GNU LGPL v3 in the shipped configuration; accompanying GNU GPL v3 text is included |
| LAME (separate shared library; native JNI integration) | 3.100 | GNU Library General Public License v2 or later; the original COPYING is included |
| IBM Plex Sans Arabic / Inter | Vendored Google Fonts files | SIL Open Font License 1.1; full licenses packaged |
| QuickJS-KT | 1.0.15 | Apache-2.0; original license and attribution included |
| Embedded QuickJS / c-vector / Unicode data | 2026-06-04 / pinned revision / Unicode 17.0.0 | MIT / MIT / Unicode License V3 |
| Chaquopy / CPython | 17.1.0 / 3.14.8 | Chaquopy upstream license / Python Software Foundation license |
| AndroidX / Kotlin / OkHttp / Coil / Media3 and transitive Maven components | Resolved release graph | 137 components inventoried in `legal/dependencies/inventory.json`; embedded notices included |

The Android Rust library is built from `adblock-native/` using the pinned `Cargo.lock`. Its dependency graph includes MPL-2.0, MIT/Apache-2.0, Unicode-3.0, and Unlicense components; preserve the lockfile and corresponding license texts with source and candidate artifacts. The engine source is included in this repository. The bundled EasyList and EasyPrivacy snapshots retain their upstream headers and are dual-licensed under GPL-3.0-or-later or CC BY-SA 3.0; the filter updater can replace them with newer versions under the same upstream licensing. License references: [MPL-2.0](https://www.mozilla.org/MPL/2.0/), [EasyList license](https://easylist.to/pages/licence.html), [GPL-3.0-or-later](https://www.gnu.org/licenses/gpl-3.0.html), [CC BY-SA 3.0](https://creativecommons.org/licenses/by-sa/3.0/).

MediaFlow uses FFmpeg and FFmpeg Kit under the GNU Lesser General Public License, version 3. The `main-min` artifact is built with `--enable-version3` and without `--enable-gpl` or `--enable-nonfree`; a copy of both the LGPL and GPL is included with the application. See the exact [FFmpeg source](https://github.com/arthenica/FFmpeg/tree/34277e12e80031c7f89494ba543684bc1dd0be8f), [FFmpeg Kit wrapper and Android recipes](https://github.com/JamaisMagic/ffmpeg-kit-16KB/tree/c7ca204219d549f563799bacbbdfeb19e31530f2) and [CPU Features source](https://github.com/arthenica/cpu_features/tree/d3b2440fcfc25fe8e6d0d4a85f06d68e98312f5b). These references are not a substitute for the corresponding-source and recombination materials that must accompany redistribution.

The [QuickJS-KT Android recipe](https://github.com/dokar3/quickjs-kt/tree/a081755bcdb18ee6afa7ffee25b8fa976f06d31f) includes [QuickJS](https://github.com/bellard/quickjs/tree/04be246001599f5995fa2f2d8c91a0f198d3f34c) (Fabrice Bellard and Charlie Gordon), [c-vector](https://github.com/eteran/c-vector/tree/774773d4cb1e66dd736e90e7f482d4f22af464c6) (Evan Teran) and generated [Unicode 17.0.0 character data](https://www.unicode.org/Public/17.0.0/ucd/ReadMe.txt). The original license texts, compiled-source copyright headers and Unicode notice are in `assets/legal/dependencies/native-quickjs-*.txt`. The Windows-only `winpthreads` sources are not part of the Android recipe.

The app's legal viewer also includes archived notices for the Chaquopy runtime, CPython, bzip2, libffi, OpenSSL, SQLite, and xz. The Maven inventory records POM declarations and embedded notice texts; it does not guess a license when a POM is silent. This inventory does not certify native source or recombination obligations.

LAME (© 1999–2011 The LAME Project and additional contributors identified in its source) is built from the vendored LAME 3.100 sources. `libzlame.so` loads `libmp3lame.so` as a separate shared library. The complete library sources, local `config.h`, `CMakeLists.txt` and `lame_bridge.c` are needed to reproduce this arrangement; see the [original LAME 3.100 archive](https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz). The in-app licenses list contains LAME’s original `COPYING`.

The Inter static regular instance used below API 26 is generated from the vendored variable font by `tools/generate_static_font.py` (FontTools 4.60.1, build-time only); the original OFL is retained. IBM Plex Sans Arabic and Inter are packaged under their supplied OFL notices.

Primary upstream references: [yt-dlp](https://github.com/yt-dlp/yt-dlp), [EJS](https://github.com/yt-dlp/ejs), [QuickJS-KT](https://github.com/dokar3/quickjs-kt), [QuickJS](https://bellard.org/quickjs/), [Chaquopy](https://github.com/chaquo/chaquopy), [CPython](https://github.com/python/cpython), [FFmpegKit](https://github.com/arthenica/ffmpeg-kit), and the published POM for `io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-min-16kb:6.1.4`.
