# Weebu r_album fork

Based on the published `r_album` 0.1.5 archive from pub.dev
(SHA-256: `f9e29514fb018c32732a49b8c3c88440c39ab0d09495ee9d0bbfe6052f9b90f0`).

The fork retains its Dart, Kotlin and Swift implementations for:

- `saveAlbumWithLocation`, used by Weebu when saving media with coordinates.
- Android MediaStore support, separate image/video destinations and MIME
  detection that excludes audio.
- Boolean results and a single native response for a batch of files.

The 0.1.5 rebase preserves those implementations unchanged. Upstream's Flutter
v1 registration removal was already included in the fork.

Android adopts the upstream AGP 4.1.0 and compile SDK 35 settings, with an
explicit `com.rhyme.r_album` namespace matching the fork's existing identity.
The fork retains Kotlin 2.0.20, minimum SDK 24, JVM/toolchain 8 and Maven Central.
The manifest no longer supplies `package`; the Gradle namespace replaces it.
The wrapper is unchanged because Weebu builds the plugin with the app's wrapper;
the upstream 9.0 milestone wrapper is not required for that build.
