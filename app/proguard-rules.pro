# OmaSend Application & Architecture Keep Rules
-keep class io.omarchy.omasend.OmaSendApp { *; }
-keep class io.omarchy.omasend.MainActivity { *; }
-keep class io.omarchy.omasend.ui.ShareActivity { *; }
-keep class io.omarchy.omasend.service.** { *; }
-keep class io.omarchy.omasend.network.** { *; }
-keep class io.omarchy.omasend.crypto.** { *; }
-keep class io.omarchy.omasend.audio.** { *; }
-keep class io.omarchy.omasend.repository.** { *; }
-keep class io.omarchy.omasend.ui.haptics.** { *; }

# OmaSend Models & Serialization
-keep class io.omarchy.omasend.model.** { *; }
-keepclassmembers class io.omarchy.omasend.model.** {
    *** Companion;
    *** serializer(...);
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.SerializationKt
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    *** Companion;
}
-keepclassmembers class * {
    *** serializer(...);
}

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# ZXing
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }

# Compose & Lifecycle
-dontwarn androidx.compose.**
-dontwarn androidx.lifecycle.**
