# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.vasmarfas.card.**$$serializer { *; }
-keepclassmembers class com.vasmarfas.card.** { *** Companion; }
-keepclasseswithmembers class com.vasmarfas.card.** { kotlinx.serialization.KSerializer serializer(...); }

# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

-keep class com.google.firebase.analytics.FirebaseAnalytics { *; }

# JavaCPP / Tesseract: the native side looks classes and fields up by name
-keep class org.bytedeco.** { *; }
-dontwarn org.bytedeco.**
-dontwarn java.awt.**
