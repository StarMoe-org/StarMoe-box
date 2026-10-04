# StarMoe Box

Android app that reads the **BanG Dream! Our Notes** save off the phone, decrypts it on the device and uploads its
player data (`_player`), **unchanged**, to the StarMoe backend (starmoe-api, "Game saves") under the user's
StarMoe Passport account.

Kotlin, Jetpack Compose, Material 3 (dynamic color on Android 12+), Logto Android SDK v3, Shizuku.

| Build | Packages | Upload as |
| --- | --- | --- |
| International (TW/HK/MO, EN, KR) | `com.bilibili.sirius.official` (official site), `com.bilibili.sirius` (Google Play) | `intl` |
| Japan | `com.bushiroad.sirius` | `jp` |

There is no mainland China build of the game. Both international packages are scanned; when the same account
turns up in both, the newer file wins.

## How it works

1. **Sign in** with StarMoe Passport (Logto at `passport.star.moe`) in a Custom Tab. The app asks for an access
   token for the API resource `https://passport.bdon.moe` with `saves:read saves:write`.
2. **Read.** Since Android 11, `Android/data/<package>/` is readable only by the shell user (what adb runs as).
   Shizuku lends that identity over wireless debugging, so no PC and no root are needed. The app lists
   `files/` of each installed build, enters hex-named directories (the account directories; names are content
   hashes), and reads their small files with `cat` through Shizuku.
3. **Decrypt** each candidate: `magic[32] ‖ iv[32] ‖ Rijndael-256-CBC(key, iv, PKCS#7(json))` — Rijndael with a
   **256-bit block**, not AES (`crypto/Rijndael.kt`, checked against BouncyCastle in tests). The file that
   decrypts to JSON with a top-level `_player` holding a valid `_profileId` (the in-game player ID) is the save.
4. **Upload** only `_player`: its value is cut out of the decrypted text byte for byte (`save/JsonSlice.kt`), not
   parsed and re-serialized, so numbers, key order and spacing are the game's own. The rest of the save stays on
   the phone. `PUT {API_BASE}/api/me/saves/{intl|jp}`, gzip level 9 on the wire (the server stores and serves that gzip as is), `Authorization: Bearer <access token>`.
   The server names the file after `_profileId` and keeps the latest upload per account.

### Other ways to read

| | Android 8–12 | Android 13+ |
| --- | --- | --- |
| **Shizuku** (above) | yes | yes |
| **Grant the game directory** once in the system folder picker, opened right on `Android/data/<package>` (`SafSaveSource`); later reads are one tap | yes¹ | no: Android 13 refuses any pick under `Android/data` |
| **Pick a folder or files**: the account folder (`files/<hex hash>/`) copied out with the phone's file manager or a PC, single save files, or the PC tool's `账号包.zip` | yes | yes |

¹ Android 11–12 refuse `Android/data` as a picker root but accept a pick that starts inside a package
directory. Some vendor builds may differ; a pick that is not a game directory is read once like any folder.

A picked folder or file does not say which build it came from, so the user names it first. Every source feeds
the same `SaveCollector`: each small file is tried, the ones that decrypt to a valid `_player` are saves, and
the newest copy of an account wins. The game's asset caches (`EncryptedBundles`, `Addressables`, …) are never
entered.

## Configuration

Build properties (`-P` on the command line or `gradle.properties`):

| Property | Default | |
| --- | --- | --- |
| `passportAppId` | `8qgrfp21mgcbkq5nvceaz` | App ID of the **Native** app `StarMoe Box` in the Logto console (public; native apps have no secret) |
| `passportEndpoint` | `https://passport.star.moe` | Logto endpoint |
| `apiResource` | `https://passport.bdon.moe` | Logto API resource = starmoe-api `PASSPORT_API_RESOURCE` |
| `apiBase` | `https://passport.bdon.moe` | starmoe-api's public origin (`https://bdon.moe` works too, through the site's `/api` proxy) |

Two different hosts: `passport.star.moe` is the Logto sign-in itself; `passport.bdon.moe` is starmoe-api, the
backend that stores the saves.

### Logto console

1. **API resources**: `https://passport.bdon.moe` with permissions `saves:read`, `saves:write`.
2. **Roles**: grant both permissions to the default user role, or tokens come without them (uploads answer 403).
3. **Applications → Native (Android)** `StarMoe Box`: redirect URI and post sign-out redirect URI
   `moe.starmoe.box://moe.starmoe.box/callback`.

## Build

JDK 17, Android SDK 35. `local.properties` points at the SDK (`sdk.dir=…`).

```bash
./gradlew testDebugUnitTest
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk (R8-shrunk, about 1.3 MB)
```

Behind a slow network, point Gradle at a local proxy for the first dependency download, e.g. mihomo:
`./gradlew … -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897`.

Release builds are signed with the debug key until a release keystore is configured.

## Save key

The magic (`b50b23a5…10b8e30`, the 32-byte `Salt` CryptProvider's constructor loads) was checked in the
global-metadata of intl `com.bilibili.sirius` 1.0.2 and jp `com.bushiroad.sirius` 1.0.4, and the key is the one
both builds' master-data configs use. If a game update changes either, saves stop decrypting and the scan shows
"没有存档"; update `save/SaveCodec.kt`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

The official build receives the save decryption parameters through GitHub Actions Secrets; source builds without
those secrets use deterministic test values and cannot decode real game saves. The encoded form in the official
APK is only light obfuscation, not a security boundary.