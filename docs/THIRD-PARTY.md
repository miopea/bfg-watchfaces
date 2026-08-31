# Third-party code and licences

Everything here is Apache-2.0 or MIT and is used as intended. This file exists
because two of them are not visible as ordinary dependencies.

## google/pack — Apache-2.0

<https://github.com/google/pack>. Compiles Android resources into an APK with no
Android SDK and no aapt2. Used two ways:

- **On the desktop**, as the CLI, built by `scripts/build-pack.sh` from a pinned
  commit with `scripts/pack-qualifiers.patch` applied.
- **On the device**, as a JNI library, built by `scripts/build-pack-android.sh`
  from that same checkout into `mobile/src/main/jniLibs/`.

Neither binary is committed. Both are build output from a pinned source.

`scripts/pack-java/` is a small JNI wrapper crate. Its shape is adapted from the
wrapper in Google's Androidify sample (Apache-2.0, Copyright 2025 The Android
Open Source Project); the symbol name is this project's, because a JNI symbol
encodes the Java package.

## Androidify — Apache-2.0

<https://github.com/android/androidify>. No code is copied from it. The on-device
signing approach in `ApkSigning.kt` — an Android Keystore key, a self-signed
certificate, and `apksig` with v2/v3 — follows the approach its `Signer.kt`
takes, because it is solving the same problem: `pack` produces an unsigned APK
and Watch Face Push will not accept one.

Its prebuilt `libpack_java.so` files are deliberately **not** used. Two reasons:
they are unversioned binaries this project cannot audit, and they are built from
unpatched `pack`, where `res/drawable-nodpi` is recorded as mdpi and the watch
scales a dial that says do not scale me.

## BouncyCastle — MIT-style (the Bouncy Castle Licence)

`org.bouncycastle:bcpkix-jdk18on`. Used only to mint the self-signed certificate
that accompanies the Android Keystore signing key.

Its jars each carry `META-INF/LICENSE.md`, and the Android resource merger will
not pick between three identical copies. They are excluded from the APK in
`mobile/build.gradle.kts`, which is why the licence is acknowledged here instead.

## apksig — Apache-2.0

`com.android.tools.build:apksig`. Signs the APK that `pack` compiled.

## Watch Face Push and its validator — Apache-2.0

`androidx.wear.watchfacepush:watchfacepush` and
`com.google.android.wearable.watchface.validator:*`. The validator issues the
token `addWatchFace` requires, and is the only thing that catches a
schema-invalid face before it installs and silently never appears.
