import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Passport (Logto) and API settings. Override per build with -P or gradle.properties, e.g.
//   ./gradlew assembleRelease -PapiBase=http://192.168.1.10:8787
// passport.star.moe is the Logto sign-in; passport.bdon.moe is starmoe-api, which the app calls directly
// (bdon.moe/api/* reaches the same service through the site's proxy).
fun setting(name: String, fallback: String): String =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: fallback

// Secrets are supplied only by the build environment. XOR encoding is obfuscation, not secrecy.
fun encodedSecret(name: String, testByte: Int): String {
    val value = providers.environmentVariable(name).orNull.orEmpty()
    require(value.isEmpty() || value.matches(Regex("[0-9a-fA-F]{64}"))) { "$name must be 32 bytes of hexadecimal" }
    val bytes = if (value.isEmpty()) List(32) { testByte } else value.chunked(2).map { it.toInt(16) }
    return bytes.mapIndexed { i, b -> (b xor ((i * 17 + 93) and 255)).toString(16).padStart(2, '0') }.joinToString("")
}
val cryptoConfigured = !providers.environmentVariable("OURNOTES_SAVE_KEY").orNull.isNullOrBlank() &&
    !providers.environmentVariable("OURNOTES_SAVE_MAGIC").orNull.isNullOrBlank()

android {
    namespace = "moe.starmoe.box"
    compileSdk = 35

    defaultConfig {
        applicationId = "moe.starmoe.box"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Logto v3 SDK: the redirect URI is <scheme>://<applicationId>/callback.
        manifestPlaceholders["logtoRedirectScheme"] = "moe.starmoe.box"

        buildConfigField("boolean", "SAVE_CRYPTO_CONFIGURED", cryptoConfigured.toString())
        buildConfigField("String", "SAVE_KEY_ENCODED", "\"${encodedSecret("OURNOTES_SAVE_KEY", 17)}\"")
        buildConfigField("String", "SAVE_MAGIC_ENCODED", "\"${encodedSecret("OURNOTES_SAVE_MAGIC", 34)}\"")
        buildConfigField("String", "PASSPORT_ENDPOINT", "\"${setting("passportEndpoint", "https://passport.star.moe")}\"")
        // The Native app "StarMoe Box" in the Logto console. A native app has no secret; its ID is public.
        buildConfigField("String", "PASSPORT_APP_ID", "\"${setting("passportAppId", "8qgrfp21mgcbkq5nvceaz")}\"")
        buildConfigField("String", "API_RESOURCE", "\"${setting("apiResource", "https://passport.bdon.moe")}\"")
        buildConfigField("String", "API_BASE", "\"${setting("apiBase", "https://passport.bdon.moe")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = false
    }

    buildTypes {
        release {
            // R8 drops the unused part of material-icons-extended and the rest; debug builds stay unshrunk.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key until a release key is set up (keystore.properties).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES")
    }

    testOptions {
        unitTests.all { test ->
            // Real saves for RealSaveTest are passed in from the command line, never committed.
            for (name in listOf("realJpSave", "realJpId", "realIntlPlayer", "realIntlId")) {
                (project.findProperty(name) as String?)?.let { test.systemProperty(name, it) }
            }
            test.testLogging { showStandardStreams = true }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // StarMoe Passport sign-in (Logto, opens the system browser).
    implementation("io.logto.sdk:android:3.0.0")
    // Shizuku: borrow adb's shell identity to read Android/data, no root.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in local unit tests; the real one replaces it.
    testImplementation("org.json:json:20250517")
    // Reference Rijndael-256 to check ours against.
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.81")
}
