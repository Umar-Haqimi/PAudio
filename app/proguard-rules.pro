# Release builds strip verbose/debug/info logging (SPEC §6). Never log thresholds, curves, names or devices.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ---------------------------------------------------------------------------------------------------------------
# M12 (DECISIONS §26). Hilt, Room, Media3, Navigation, Compose, DataStore and kotlinx.serialization ship their own
# consumer rules inside their AARs/JARs; the rules below only pin what PAudio itself reaches by reflection or by
# name, so a future library update that drops a consumer rule cannot silently break a release build.
# ---------------------------------------------------------------------------------------------------------------

# kotlinx.serialization: type-safe navigation routes (`@Serializable data object/class XxxRoute`, resolved by
# `serializer<T>()` at runtime) and the profile JSON import/export DTOs (`ProfileJson`, device snapshots,
# preference round records). Keep the generated serializers, companions and `serializer()` accessors.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, EnclosingMethod
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class my.umarh.paudio.**$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class my.umarh.paudio.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class my.umarh.paudio.**
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class my.umarh.paudio.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Profile file fields are matched by serial name (strings in the generated descriptors), but keep the model
# enums' names intact: enum names are part of the exported JSON and of `HeadsetKey` strings stored in Room.
-keepclassmembers enum my.umarh.paudio.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Room: the generated `PAudioDatabase_Impl` is instantiated reflectively by `Room.databaseBuilder`.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class my.umarh.paudio.** { *; }

# Hilt / Dagger: generated components and `@AndroidEntryPoint` base classes are referenced by name from the
# manifest (Application, Service, receivers) and by Hilt's bytecode transform.
-keep class dagger.hilt.android.internal.managers.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }

# Components named in the merged manifest (service, receivers, activity, application).
-keep class my.umarh.paudio.PAudioApplication { *; }
-keep class my.umarh.paudio.MainActivity { *; }

# Media3: the A/B player uses ExoPlayer with the default factories (no extension reflection). Keep the
# ExoPlayer `MediaCodecRenderer` constructors Media3's consumer rules already cover; nothing else is needed.
-dontwarn androidx.media3.**
