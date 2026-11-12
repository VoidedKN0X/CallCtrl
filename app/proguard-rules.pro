# kotlinx.serialization models (library ships its own rules; these keep saved history and routes intact).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class com.vknxworks.callctrl.**$$serializer { *; }
-keepclassmembers class com.vknxworks.callctrl.** {
    *** Companion;
}
-keepclasseswithmembers class com.vknxworks.callctrl.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google-free: Guava's listenablefuture jar is excluded from the build (see app/build.gradle.kts).
# AndroidX's concurrent-futures still names its interface in classes CallCtrl never uses.
-dontwarn com.google.common.util.concurrent.ListenableFuture

# Release builds drop debug and info logging entirely (CallCtrl itself doesn't log; this also covers libraries).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
