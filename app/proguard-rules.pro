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

# Call recording: Shizuku starts the capture service by class name in its own process (reflection), with the
# (Context) or no-argument constructor. Keep both and the name.
-keep class com.vknxworks.callctrl.recording.shell.CaptureService {
    public <init>(android.content.Context);
    public <init>();
}
