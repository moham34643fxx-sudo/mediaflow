# Third-party license summary

| Component | Version | Declared license |
|---|---:|---|
| yt-dlp | 2026.8.19 | Unlicense |
| yt-dlp-ejs | 0.8.0 | Unlicense |
| FFmpeg Kit min 16 KB (JamaisMagic distribution) | 6.1.4 | LGPL-3.0 (published POM) |
| LAME (separate shared library; native JNI integration) | 3.100 | LGPL-2.0-or-later; full upstream COPYING and source included |
| IBM Plex Sans Arabic / Inter | Vendored Google Fonts files | SIL Open Font License 1.1; full licenses packaged |
| QuickJS-KT | 1.0.15 | Apache-2.0 |
| Embedded QuickJS | Runtime reports 2026-06-04 | MIT |
| Chaquopy / CPython | 17.1.0 / 3.14 | Chaquopy upstream license / Python Software Foundation license |
| AndroidX / Kotlin / OkHttp / Coil / Media3 and transitive Maven components | Resolved release graph | 137 components inventoried in `legal/dependencies/inventory.json`; embedded notices included |

The FFmpeg dependency uses the LGPL `main-min` artifact, not the GPL variant. Applicable notices and corresponding-source obligations must accompany redistribution. The generated Maven inventory records actual POM declarations, inherited declarations and embedded notice texts; it does not guess a license when the POM is silent (`javax.inject:javax.inject:1`). It is available with the notice texts in the app's legal viewer. Native corresponding-source/build/relinking obligations still require release-owner review before production redistribution; the Maven inventory does not certify those obligations.

LAME source, `config.h`, CMake configuration and JNI integration are included in the source archive. The Inter static regular instance used below API 26 is generated from the vendored variable font by `tools/generate_static_font.py` (FontTools 4.60.1, build-time only); the original OFL is retained. IBM Plex Sans Arabic and Inter are packaged under their supplied OFL notices.

Primary upstream references: [yt-dlp](https://github.com/yt-dlp/yt-dlp), [EJS](https://github.com/yt-dlp/ejs), [QuickJS-KT](https://github.com/dokar3/quickjs-kt), [QuickJS](https://bellard.org/quickjs/), [Chaquopy](https://github.com/chaquo/chaquopy), [CPython](https://github.com/python/cpython), [FFmpegKit](https://github.com/arthenica/ffmpeg-kit), and the published POM for `io.github.jamaismagic.ffmpeg:ffmpeg-kit-main-min-16kb:6.1.4`.
