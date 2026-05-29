# Keep JNI bridge class — native methods must never be renamed
-keep class com.projectexe.engine.LlamaEngine { native <methods>; }
-keep class com.projectexe.engine.TokenCallback { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *

# Gson serialization for SoulDocument
-keep class com.projectexe.data.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
