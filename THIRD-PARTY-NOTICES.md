# Third-party notices

Components redistributed in official builds of this project, with the license each one is
covered by. This file supplements [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md): the terms below
apply to those components, not to this project's own code.

Versions are the ones pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml), the Firebase Apple
SDK version is pinned in the Xcode project (firebase-ios-sdk 12.19.2).

## Apache License 2.0

Full text: [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt)

| Component | Copyright |
|---|---|
| Kotlin standard library, `kotlin-test` | JetBrains s.r.o. and Kotlin Programming Language contributors |
| kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime, kotlinx-browser | JetBrains s.r.o. |
| Compose Multiplatform — runtime, foundation, material3, ui, ui-backhandler, components-resources, material-icons-extended | The Android Open Source Project, JetBrains s.r.o. |
| Skiko | JetBrains s.r.o. |
| navigation-compose, lifecycle-viewmodel-compose, lifecycle-runtime-compose (JetBrains multiplatform builds) | The Android Open Source Project, JetBrains s.r.o. |
| androidx.core:core-ktx, androidx.activity:activity-compose | The Android Open Source Project |
| Ktor (client core, OkHttp, Darwin, JS engines) | JetBrains s.r.o. |
| OkHttp | Square, Inc. |
| Firebase Crashlytics, Firebase Performance Monitoring (Android builds) | Google LLC |
| Firebase Apple SDK: FirebaseCore, FirebaseInstallations, FirebaseCrashlytics, FirebaseSessions, with GoogleUtilities, GoogleDataTransport and Promises (iOS builds) | Google LLC |
| AndroidX Media3 (transformer, effect, common) | The Android Open Source Project |
| AndroidX CameraX (camera-camera2, camera-lifecycle, camera-compose) | The Android Open Source Project |
| ZXing core (Android and desktop builds) | ZXing authors |
| zxing-cpp, compiled to WebAssembly inside `zxing-wasm` (website) | Axel Waggershauser and zxing-cpp contributors |
| Apache PDFBox with FontBox and pdfbox-io (desktop builds) | The Apache Software Foundation |
| Apache Commons Logging, pulled in by PDFBox (desktop builds) | The Apache Software Foundation |
| JavaCPP (desktop builds) | Samuel Audet |
| JNA, Java Native Access (desktop builds), licensed under Apache-2.0 or LGPL-2.1-or-later at the recipient's choice | Timothy Wall and JNA contributors |
| PDF.js (`pdfjs-dist`, website) | Mozilla Foundation |
| Roboto 2.137 Regular (website, `fonts/roboto.woff2`, cut down to the characters of the site's HTML copy) | Google LLC |

## MIT License

**material-kolor** — Copyright (c) 2025 Jordon de Hoog

**qrose** — Copyright (c) 2023 Alexander Zhirkevich

**FileKit** — Copyright (c) 2026 Vincent Guillebaud

**zxing-wasm** (website, loaded from its own `vendor/` folder when the scanner opens) — Copyright (c) 2023 Ze-Zheng Wu

> Permission is hereby granted, free of charge, to any person obtaining a copy of this software
> and associated documentation files (the "Software"), to deal in the Software without
> restriction, including without limitation the rights to use, copy, modify, merge, publish,
> distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
> Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or
> substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
> BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
> NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
> DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

## Mozilla Public License 2.0

**Mediabunny** and **@mediabunny/mp3-encoder** — Copyright (c) Vanilagy. The website loads both
unmodified from its own `vendor/` folder on first use of a media tool. Source code:
<https://github.com/Vanilagy/mediabunny>. License text: <https://www.mozilla.org/en-US/MPL/2.0/>.

## GNU Lesser General Public License

**FFmpeg** 8.1, in the LGPL build published by the JavaCPP Presets (`org.bytedeco:ffmpeg`), ships
with desktop builds as separate executables that the app starts as processes. The codec libraries
that build links in are covered by their own licenses, listed in the FFmpeg configuration of the
presets. FFmpeg source: <https://ffmpeg.org/download.html>. Build scripts:
<https://github.com/bytedeco/javacpp-presets/tree/master/ffmpeg>. License text:
<https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

**LAME**, compiled to WebAssembly inside `@mediabunny/mp3-encoder`, is licensed under the LGPL 2.0
or later. It runs only on the website, in its own module. Source: <https://lame.sourceforge.io>.

## SIL Open Font License 1.1

**Liberation Fonts** 2.1.5 — Digitized data copyright (c) 2010 Google Corporation, with Reserved
Font Arimo, Tinos and Cousine. Copyright (c) 2012 Red Hat, Inc., with Reserved Font Name Liberation.
The fonts under `shared/src/commonMain/composeResources/files/fonts` are cut down to Latin, Cyrillic
and punctuation and renamed Mobitool, as the license requires of modified versions. They are used
only to typeset PDF files, which embed subsets of them. The license text sits next to them in
`OFL.txt`.

## CC0 1.0

**minimp3** by lieff, <https://github.com/lieff/minimp3>. The Huffman tables and the analysis
window of the MP3 encoder in `Mp3Tables.kt` were taken from it. The encoder itself is not a port.

## zlib License

**nanopb**, pulled in by the Firebase Apple SDK (iOS builds) — Copyright (c) 2011 Petteri Aimonen.
License text: <https://github.com/nanopb/nanopb/blob/master/LICENSE.txt>.

## BSD 3-Clause

**Skia** — Copyright (c) 2011 Google Inc. Ships as a native binary inside Skiko; see
<https://github.com/google/skia/blob/main/LICENSE>.

## Not open-source licenses

**Firebase Analytics** (`com.google.firebase:firebase-analytics`, which pulls in
`play-services-measurement`) is distributed under the Android Software Development Kit License
Agreement, <https://developer.android.com/studio/terms>. It permits building and distributing
applications; it is not covered by this project's own license, and anyone building from source
obtains it directly from Google on those terms.

**Firebase Analytics for iOS** (the `FirebaseAnalytics` and `GoogleAppMeasurement` binaries that
firebase-ios-sdk downloads from Google) is closed source and distributed under the Firebase terms,
<https://firebase.google.com/terms>. Like the Android SDK above, it is not covered by this project's own
license.

**Bundled Java runtime.** Desktop installers produced by `jpackage` embed an Eclipse Temurin 21
runtime image, licensed under GPLv2 with the Classpath Exception,
<https://openjdk.org/legal/gplv2+ce.html>. The exception is what permits bundling it with an
application under different terms.

## Ported benchmark code

The benchmark tool contains Kotlin ports of three reference implementations, kept faithful so the
scores stay comparable with published ones:

- **Whetstone** — from the netlib C source, <https://netlib.org/benchmark/whetstone.c>, itself a
  translation of the Curnow and Wichmann ALGOL original (1976). Public domain.
- **Linpack** — from the netlib Java version by Jack Dongarra and co-authors,
  <https://netlib.org/benchmark/linpackjava/>. Public domain.
- **SciMark 2.0** — from the NIST ANSI C sources, <https://math.nist.gov/scimark>. Work of the
  U.S. National Institute of Standards and Technology and not subject to copyright in the
  United States.

The SHA-256, memory-copy and Mandelbrot kernels beside them are not ports of anything and are not
standard benchmarks.

## Development-only

**JUnit 4** (`junit:junit`) is used for tests under the Eclipse Public License 1.0,
<https://www.eclipse.org/legal/epl-v10.html>. It is a test dependency and is not present in any
published build.

**Apache Commons Imaging** checks the image codecs in the desktop tests under the Apache License
2.0 and is not present in any published build either.

## Website

vasmarfas.com and vasmarfas.ru load Yandex Metrica from `mc.yandex.ru`. vasmarfas.com also loads the
Firebase JavaScript SDK (`firebase-app` and `firebase-analytics` 12.19.0, Apache-2.0) from
`www.gstatic.com`, and the SDK loads the Google tag (`gtag.js`) from `www.googletagmanager.com`. These are
hosted services and scripts called over the network, not libraries bundled into the site.
