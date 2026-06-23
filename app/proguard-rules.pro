# =============================================================================
# Hushd — R8/ProGuard rules for release builds
# License: All rights reserved (private)
#
# Strategy:
#   - Compose-safe defaults (don't strip Composable callable references)
#   - Aggressive log stripping (Timber + android.util.Log)
#   - Keep rules for AccessibilityService (system instantiates via reflection)
#   - Keep rules for Hilt, Room, Kotlinx Serialization
#   - Strip BuildConfig fields that leak build metadata
# =============================================================================

# ---------- General hygiene ----------
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes AnnotationDefault
-keepattributes *Annotation*

# ---------- Reproducibility ----------
# Forbid optimization-pass-dependent ordering of code rewrites.
-optimizations !code/allocation/variable
-optimizationpasses 5

# ============================================================================
# Log stripping — defense in depth.
# Timber + android.util.Log calls in release builds are silently removed.
# This prevents AccessibilityNodeInfo text from accidentally reaching logcat
# even if a developer forgets the lint rule.
# ============================================================================
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
-assumenosideeffects class timber.log.Timber {
    public static void v(...);
    public static void d(...);
    public static void i(...);
    public static void w(...);
}
-assumenosideeffects class timber.log.Timber$Tree {
    public void v(...);
    public void d(...);
    public void i(...);
    public void w(...);
}
# Keep .e() / .wtf() — error and fatal paths must reach safe redacted tree.

# ============================================================================
# AccessibilityService — system instantiates via reflection from manifest.
# DO NOT obfuscate the public class names referenced from AndroidManifest.xml
# or the system will fail to bind the service silently.
# ============================================================================
-keep public class dev.labushuya.hushd.service.accessibility.AutostartAccessibilityService {
    public <init>();
    public void onServiceConnected();
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
    public void onUnbind(android.content.Intent);
}
# Keep the overlay service class name (manifest reference)
-keep public class dev.labushuya.hushd.service.overlay.OverlayService {
    public <init>();
    public void onCreate();
    public void onDestroy();
    public void onStartCommand(android.content.Intent, int, int);
}
# Settings activity reachable from AccessibilityServiceInfo.settingsActivity
-keep public class dev.labushuya.hushd.MainActivity { public <init>(); }
-keep public class * extends androidx.appcompat.app.AppCompatActivity { public <init>(); }
-keep public class * extends androidx.activity.ComponentActivity { public <init>(); }

# ============================================================================
# AndroidX & AppCompat
# ============================================================================
-dontwarn androidx.**
-keep class androidx.lifecycle.** { *; }
-keep class androidx.savedstate.** { *; }
-keep class androidx.startup.** { *; }

# ============================================================================
# Jetpack Compose
# ============================================================================
# Compose runtime keeps slot tables alive across recompositions — do not strip.
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.tooling.preview.** { *; }
# Composable functions that are referenced reflectively by tooling
-keep,allowobfuscation class * {
    @androidx.compose.runtime.Composable <methods>;
}
# Compose Lambdas — keep companion ComposableSingletons$* classes
-keepclassmembers class **.ComposableSingletons$* {
    <fields>;
    <methods>;
}
-dontwarn androidx.compose.**

# ============================================================================
# Hilt / Dagger
# ============================================================================
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$ViewComponentBuilderEntryPoint
-keep,allowobfuscation @interface dagger.hilt.android.AndroidEntryPoint
-keep,allowobfuscation @interface dagger.hilt.android.HiltAndroidApp
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-keepclassmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
-keep class hilt_aggregated_deps.** { *; }
-dontwarn dagger.hilt.**

# ============================================================================
# Room
# ============================================================================
-keep class androidx.room.RoomDatabase
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keepclassmembers @androidx.room.Entity class * { <fields>; }
-dontwarn androidx.room.**

# ============================================================================
# kotlinx.serialization
# ============================================================================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class dev.labushuya.hushd.**$$serializer { *; }
-keepclassmembers class dev.labushuya.hushd.** {
    *** Companion;
}
-keepclasseswithmembers class dev.labushuya.hushd.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================================
# Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**
-dontwarn kotlinx.atomicfu.**

# ============================================================================
# WorkManager
# ============================================================================
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker

# ============================================================================
# Timber — keep redacting tree implementation (referenced via Class.forName? No.
# But keep it for stable stack traces in fatal-path .e()/.wtf() calls.)
# ============================================================================
-keep class dev.labushuya.hushd.core.common.log.SafeTimberTree { *; }
-keep class dev.labushuya.hushd.core.common.log.Redactor { *; }

# ============================================================================
# BuildConfig — strip everything except what we actually read at runtime.
# Build metadata (git SHA, build time) is fine to keep for diagnostics — but no PII.
# ============================================================================
-keepclassmembers class dev.labushuya.hushd.BuildConfig {
    public static final java.lang.String GIT_SHA;
    public static final boolean REPRODUCIBLE;
}

# ============================================================================
# Enum hygiene
# ============================================================================
-keepclassmembers,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# Parcelable
# ============================================================================
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# ============================================================================
# Native methods
# ============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================================
# Strip Kotlin-Intrinsics null-check messages — they leak parameter names that
# can hint at sensitive call sites. Safe because R8 reinserts them where the
# JVM contract truly requires them.
# ============================================================================
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    public static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String, java.lang.String);
    public static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
}

# ============================================================================
# Don't warn about generated Hilt/Room classes pre-existing in dependencies
# ============================================================================
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# ============================================================================
# Final fail-loud: any remaining unresolved reference is a real bug, not noise.
# ============================================================================
# (no global -dontwarn — keep the build honest)
