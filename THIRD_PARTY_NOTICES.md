# Third-party notices

Delta Vault source is licensed under GNU AGPL version 3. The complete license is in `LICENSE`.
Copyright (c) 2026 Delta Vault contributors. This application is an independent project;
Cryptomator is the name of the upstream format and project, not an endorsement of this client.

Runtime dependencies are free and open source:

| Component | License | Source |
| --- | --- | --- |
| Cryptomator cryptolib 2.2.0 | AGPL-3.0 | https://github.com/cryptomator/cryptolib/tree/2.2.0 |
| Gson 2.11.0 | Apache-2.0 | https://github.com/google/gson |
| OkHttp / Okio | Apache-2.0 | https://github.com/square/okhttp / https://github.com/square/okio |
| Kotlin standard library (OkHttp dependency) | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Guava and associated annotations | Apache-2.0 / MIT / BSD | https://github.com/google/guava |
| siv-mode | MIT | https://github.com/cryptomator/siv-mode |
| Bouncy Castle (including upstream shaded dependency) | MIT | https://github.com/bcgit/bc-java |
| SLF4J | MIT | https://github.com/qos-ch/slf4j |
| kXML2 | MIT | https://kxml.sourceforge.net/ |
| Android JDK desugaring libraries | GPL-2.0 with Classpath Exception / Apache-2.0 and upstream notices | https://github.com/google/desugar_jdk_libs |
| LibVLC Android 3.7.7, native VLC and bundled modules | LGPL-2.1-or-later / GPL-2.0-or-later / GPL-3.0-or-later and upstream component licenses | https://code.videolan.org/videolan/libvlcjni / https://code.videolan.org/videolan/vlc |
| LibVLC HRTF dataset, Copyright 2017 3D Sound Labs | LGPL-2.1-or-later | VideoLAN LibVLC distribution |
| LibVLC Lua dkjson, Copyright 2010, 2011 David Heiko Kolf | MIT | https://dkolf.de/dkjson-lua/ |
| Native libc++ | Apache-2.0 with LLVM exceptions / upstream notices | https://github.com/llvm/llvm-project |
| AndroidX support and annotations (LibVLC dependency) | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| libavif Android 1.3.0.841110fd and JNI bindings | BSD-2-Clause and upstream component notices | https://github.com/AOMediaCodec/libavif |
| dav1d (AVIF native decoder) | BSD-2-Clause | https://code.videolan.org/videolan/dav1d |
| libyuv (AVIF pixel conversion) | BSD-3-Clause and upstream patent grant | https://chromium.googlesource.com/libyuv/libyuv/ |
| Android NDK cpufeatures (AVIF native dependency) | BSD-2-Clause | https://android.googlesource.com/platform/ndk/ |

JUnit (EPL-1.0), Hamcrest (BSD), MockWebServer and OkHttp TLS (Apache-2.0), and Robolectric (MIT) are test-only dependencies.
Android Gradle Plugin and Gradle are build tools; no Google Play Services or proprietary cloud SDK is included.
The Android SDK/platform on the user's device is external to this source project.

The APK preserves available dependency `META-INF/LICENSE*` and `META-INF/NOTICE*` resources
and includes the application's AGPL license under `assets/LICENSE`.
Additional license texts are included under `assets/licenses/`, including Apache-2.0,
siv-mode and kXML2 copyright notices, and the OpenJDK GPL license with Classpath Exception.
Exact resolved versions can be inspected with `gradlew :core:dependencies --configuration runtimeClasspath`.
No source from the official desktop or Android app has been copied into this repository.

LibVLC's Maven POM declares LGPL-2.1, but `libvlc-all` also contains GPL modules and Lua assets.
All are FLOSS. The APK includes LGPL-2.1, GPL-2.0, GPL-3.0, the dkjson and HRTF notices,
LLVM/NDK notices, and VideoLAN's upstream copyright/license inventory under `assets/licenses/`.
The upstream inventory covers additional VLC Android app components as well as native libraries;
it is retained as upstream documentation, not an assertion that every listed component is included.
3.7.7 AAR SHA-256: `b48dab96e0e90e34cce3861963500c144a5aadb1b249d501d6a4b104d849e61b`.
The inspected native library reports VLC 3.0.25 and changeset `3.0.24-35-gc7e5d68ee5`.

For public binary distribution, preserve and provide the complete corresponding native/contrib source,
patches and build instructions for the pinned VideoLAN build in addition to this application's source.
The Maven `sources.jar` includes Java bindings only, and generic source URLs alone are not a complete
corresponding-source archive. Native source reconstruction and a release source bundle remain release work.
See [VideoLAN LibVLC build scripts](https://code.videolan.org/videolan/libvlcjni/-/tree/3.x/buildsystem)
and [VideoLAN VLC source](https://code.videolan.org/videolan/vlc).

The AVIF AAR SHA-256 is `d2e3e0ac5ca2512b12527027b3bf815c54afdb3a2261855ed0f1ca8d06ab0c97`.
Inspected native components report libavif 1.3.0 (JNI/source commit
`841110fd8723abd0d5439cbb2cfb8e9cfa11f808`), dav1d `1.5.1-10-g63bf075`, and libyuv version 1913.
The exact libyuv build commit is not proven; the dependency checkout in the libavif source script
reports a different libyuv version and must not be treated as the exact binary build provenance.
The full libavif license inventory, dav1d/libyuv notices, libyuv patent grant, and NDK cpufeatures
notice are included under `assets/licenses/`. These are all FLOSS components.
PDF rendering uses Android's platform PdfRenderer, backed by the OS PDF implementation;
no proprietary PDF SDK or additional PDF binary is bundled.
