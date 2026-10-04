# Shizuku: the AIDL stubs and binder classes are reached over IPC by name.
-keep class moe.shizuku.server.** { *; }
-keep class rikka.shizuku.** { *; }

# Logto SDK: token and OIDC responses are bound with Gson by field name; jose4j loads algorithms reflectively.
-keep class io.logto.sdk.** { *; }
-keep class org.jose4j.** { *; }
-dontwarn org.jose4j.**
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod

# OkHttp / Okio pulled in by the Logto SDK.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
