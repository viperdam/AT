# Keep MediaPipe classes
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }

# Global rules to suppress warnings
-ignorewarnings
-dontwarn **

# Keep MediaPipe proto classes
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
  <fields>;
  <methods>;
}

# Keep MediaPipe framework classes
-keepclassmembers class com.google.mediapipe.framework.** {
  native <methods>;
  void nativeRelease();
  void nativeReset();
  void nativeInitialize();
  void nativeLoadBinaryGraph(byte[]);
  void nativeLoadBinaryGraphTemplate(byte[]);
  void nativeLoadBinaryResource(java.lang.String);
  void nativeLoadBinaryResources(java.lang.String[]);
  void nativeAddPacketCallback(java.lang.String, long);
  void nativeAddMultiStreamCallback(java.lang.String[], long);
  void nativeStartRunningGraph();
  void nativeWaitUntilGraphDone();
  void nativeCloseAllPacketSources();
  void nativeWaitUntilIdle();
  void nativeSetGraphType(int);
  void nativeSetGraphInputStreamAddMode(int);
  void nativeSetGraphOutputStreamAddMode(int);
  void nativeAddSidePacket(java.lang.String, byte[]);
  void nativeSetServiceObject(java.lang.String, java.lang.Object);
  void nativeGetCalculatorGraphConfig();
  void nativeLoadCalculatorGraphConfig(java.lang.String);
  void nativeLoadCalculatorGraphTemplate(java.lang.String);
  void nativeLoadCalculatorGraphTextFormat(java.lang.String);
  void nativeSetGraphInputStreamHandler(java.lang.String, java.lang.String);
  void nativeSetGraphOutputStreamHandler(java.lang.String, java.lang.String);
  void nativeAddPacketToInputStream(java.lang.String, byte[], long);
  void nativeAddPacketListToInputStream(java.lang.String, byte[][], long[]);
  void nativeGetStreamQueueSize(java.lang.String);
  void nativeGetGraphProfile();
  void nativeSetGraphProfiler(boolean);
  void nativeGetCalculatorProfiles();
  void nativeGetInputStreamQueueSize(java.lang.String);
  void nativeGetOutputStreamQueueSize(java.lang.String);
  void nativeGetProfiler();
}

# Keep AutoValue classes
-keep class com.google.auto.value.** { *; }
-keep class javax.lang.model.** { *; }
-keep class com.squareup.javapoet.** { *; }

# Suppress the specific R8 warning for the unreachable AutoValue method
-dontnote com.google.auto.value.processor.TypeVariables$SubstitutionVisitor
-dontwarn com.google.auto.value.processor.TypeVariables$SubstitutionVisitor
-keep class com.google.auto.value.processor.TypeVariables$SubstitutionVisitor {
    javax.lang.model.type.TypeMirror visitTypeVariable(javax.lang.model.type.TypeVariable, java.lang.Void);
}

# Keep Hilt classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Keep app classes
-keep class com.viperdam.kidsprayer.** { *; }

# Keep Camera classes
-keep class androidx.camera.** { *; }

# Keep generated classes
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * extends dagger.hilt.internal.GeneratedEntryPoint { *; }
-keep class * extends dagger.hilt.internal.GeneratedModule { *; }

# Keep annotation processors
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.auto.value.**
-dontwarn com.squareup.javapoet.**
-dontwarn com.google.protobuf.**
-dontwarn com.google.mediapipe.proto.**
-dontwarn com.google.mediapipe.framework.**

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep MediaPipe native libraries
-keep class com.google.mediapipe.framework.jni.** { *; }
-keepclassmembers class com.google.mediapipe.framework.jni.** {
    *;
}

# Kotlin & Coroutines
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keepclassmembers class kotlinx.coroutines.** { *; }
-keepclassmembers class kotlin.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-dontwarn kotlin.coroutines.**

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }
-keepclasseswithmembers class org.jetbrains.kotlinx.** { *; }

# Kotlin Datetime
-keep class kotlinx.datetime.** { *; }

# WorkManager
-keep class androidx.work.** { *; }
-keepclassmembers class androidx.work.** { *; }

# Adhan Prayer Times library
-keep class com.batoulapps.adhan.** { *; }
-keepclassmembers class com.batoulapps.adhan.** { *; }

# Firebase and Google Services
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# AdMob
-keep class com.google.android.gms.ads.** { *; }
-keep public class com.google.ads.** { *; }
-dontwarn com.google.android.gms.ads.**

# DataStore
-keep class androidx.datastore.** { *; }
-keepclassmembers class androidx.datastore.** { *; }

# Lottie
-keep class com.airbnb.lottie.** { *; }
-dontwarn com.airbnb.lottie.**

# Compose
-keep class androidx.compose.** { *; }
-keepclasseswithmembers class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Security
-keep class androidx.security.** { *; }

# Google Play Core
-keep class com.google.android.play.** { *; }

# Guava
-keep class com.google.common.** { *; }
-dontwarn com.google.common.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn java.lang.ClassValue
-dontwarn afu.org.checkerframework.**
-dontwarn org.checkerframework.**

# General rules to preserve reflection
-keepattributes Signature, Annotation, EnclosingMethod, InnerClasses
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault, SourceFile, LineNumberTable

# Keep the R class for reference from native code and resource files
-keepclassmembers class **.R$* {
    public static <fields>;
}

# AndroidX libraries
-keep class androidx.** { *; }
-keep interface androidx.** { *; }

# Keep Fragments
-keep class * extends androidx.fragment.app.Fragment { *; }
-keep class * extends android.app.Activity { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends android.content.ContentProvider { *; }

# Keep ViewModels and LiveData
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keep class * extends androidx.lifecycle.LiveData { *; }

# Google Mobile Ads SDK
-keep class com.google.android.gms.ads.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keepattributes *Annotation*
-keepattributes Signature

# For Google Play Services
-keep public class com.google.android.gms.ads.**{
    public *;
}

# For mediation
-keepattributes EnclosingMethod

# Unity Ads SDK
-keep class com.unity3d.ads.** { *; }
-keep class com.unity3d.services.** { *; }
-dontwarn com.unity3d.ads.**

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.firebase.** { *; }
-keep class org.apache.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-keepnames class javax.servlet.** { *; }
-keepnames class org.ietf.jgss.** { *; }
-dontwarn org.w3c.dom.**
-dontwarn org.joda.time.**
-dontwarn org.shaded.apache.**
-dontwarn org.ietf.jgss.**

# Keep classes that have native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep classes used by native code
-keep class com.viperdam.kidsprayer.ads.** { *; }

# Keep the application class and its members
-keep class com.viperdam.kidsprayer.PrayerApp { *; }

# Keep JavaScript interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Avoid obfuscation of WebView methods
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}

# Unity Ads ProGuard Rules
-keepattributes SourceFile,LineNumberTable
-keepattributes JavascriptInterface
-keep class com.unity3d.ads.** { *; }
-keep interface com.unity3d.ads.** { *; }

# Keep Unity Ads metadata
-keep class com.unity3d.ads.metadata.** { *; }

# Keep Unity Mediation classes for AdMob
-keep class com.google.ads.mediation.unity.** { *; }

# If you see "not found" errors for Unity Ads in your logs
-dontwarn com.unity3d.ads.**
-dontwarn com.google.ads.mediation.unity.**
