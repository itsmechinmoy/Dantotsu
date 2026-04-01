#############################################
# General / Debugging
#############################################

#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-dontobfuscate


#############################################
# Kotlin / Coroutines / Serialization
#############################################

-keep class kotlin.** { *; }
-dontwarn kotlin.**

-keep class kotlinx.** { *; }

# Serializable Companion handling
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,allowoptimization class kotlinx.serialization.** { public protected *; }


#############################################
# Core App / Extensions
#############################################

-keep class ani.dantotsu.** { *; }
-keep class ani.dantotsu.download.DownloadsManager { *; }

-keep class eu.kanade.** { *; }
-keep class uy.kohesive.injekt.** { *; }

-keepclassmembers class uy.kohesive.injekt.api.FullTypeReference {
    <init>(...);
}

#############################################
# Firebase
#############################################

-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

#############################################
# Networking (OkHttp + Okio)
#############################################

-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

-keep class okio.** { *; }
-dontwarn okio.**


#############################################
# Android / Jetpack
#############################################

-keep class androidx.preference.** { *; }

# WorkManager database
-keep class androidx.work.impl.WorkDatabase_Impl { *; }


#############################################
# Gson / JSON / HTML Parsing
#############################################

-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }

-keep class org.jsoup.** { *; }
-keepclassmembers class org.jsoup.nodes.Document { *; }


#############################################
# QuickJS / Native / Unsafe
#############################################

-keep,allowoptimization class app.cash.quickjs.** { public protected *; }

-keep class rx.internal.util.unsafe.** { *; }

-dontwarn sun.misc.Unsafe
-dontwarn org.graalvm.nativeimage.**
-dontwarn com.oracle.svm.core.annotate.**



# Keep RxJava unsafe internals
-keep class rx.internal.util.unsafe.** { *; }

# Keep fields (VERY IMPORTANT)
-keepclassmembers class rx.internal.util.unsafe.** {
    long producerIndex;
    long consumerIndex;
}

# Keep all rx internal operators (safe side)
-keep class rx.internal.** { *; }

# Prevent stripping Unsafe usage
-dontwarn sun.misc.Unsafe
