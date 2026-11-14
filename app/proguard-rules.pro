# مبنية على قواعد NewPipe
-dontobfuscate

# NewPipeExtractor + Rhino (لفك تشفير توقيعات يوتيوب)
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class com.grack.nanojson.** { *; }

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# poToken JavaScript bridge
-keepclassmembers class com.turkialawlqy.aman.potoken.PoTokenWebView {
    @android.webkit.JavascriptInterface <methods>;
}

# حل تحديات يوتيوب (WebView bridge)
-keepclassmembers class com.turkialawlqy.aman.yt.JsChallengeSolver$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}

# kotlinx.serialization / Supabase / Ktor
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.turkialawlqy.aman.**$$serializer { *; }
-keepclassmembers class com.turkialawlqy.aman.** { *** Companion; }
-keepclasseswithmembers class com.turkialawlqy.aman.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
