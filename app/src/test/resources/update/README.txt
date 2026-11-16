This is a minimal APK with binary AndroidManifest.xml, no executable code and a
throwaway self-signed RSA certificate (CN=Vkyra_Test_Only). The private key is not
included. It was produced with Android SDK 36 aapt2 and apksigner, signing only
with APK signature scheme v2. Its package is com.vkyra.testfixture, minSdk 26,
targetSdk 28, versionCode 999. It must never be installed as an application update.

The fixture exercises the real Google apksig content-verification path, including
modified signed bytes and an unsigned but structurally valid ZIP. The base64 text
format avoids treating this deliberately committed fixture as a build APK.
