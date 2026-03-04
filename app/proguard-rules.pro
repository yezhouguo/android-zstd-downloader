# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Zstd-jni
-keep class com.github.luben.zstd.** { *; }
-keepclassmembers class com.github.luben.zstd.** { *; }

# AndroidX
-keep class * extends android.os.Parcelable
-keep class * extends androidx.lifecycle.ViewModel
