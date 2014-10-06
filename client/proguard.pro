-injars target/client-0.1-SNAPSHOT-bundled.jar
-outjars target/lighthouse.jar
-libraryjars <java.home>/lib/rt.jar
-libraryjars <java.home>/lib/jce.jar
-libraryjars <java.home>/lib/jsse.jar
-libraryjars <java.home>/lib/ext/jfxrt.jar
-libraryjars <java.home>/lib/ext/sunjce_provider.jar
-libraryjars <java.home>/lib/ext/sunpkcs11.jar
-libraryjars <java.home>/lib/ext/zipfs.jar
-target 8

# For now we prefer to just use dead code elimination. Optimizations break things and obfuscations don't
# reduce download size by any meaningful amount. However DCE removes about 10mb of code.
-dontoptimize
-dontobfuscate

# To keep things quiet
-dontnote
-dontwarn

-keep class lighthouse.Main {
    public static void main(java.lang.String[]);
    public static void realMain(java.lang.String[]);
}

# Preserve all entrypoints that are invoked via FXML (i.e. reflection) and are marked as such.
-keepclassmembers class * {
    @javafx.fxml.FXML *;
}
-keepclassmembers class * {
    public void initialize();
}
-keep class lighthouse.controls.* {
    public <init>();
}
-keep class lighthouse.subwindows.* { *; }

# Protobufs use reflection
-keep class lighthouse.protocol.LHProtos** {
    *;
}

-keepattributes **

# Don't break enums
-keep enum * {
    *;
}

# UpdateFX sets these via reflection because of the way it uses classloaders.
-keepclassmembers class com.vinumeris.updatefx.UpdateFX {
    public static *;
}

# Work around bug #540 in ProGuard/JDK8 interaction with lambdas
-keepclassmembers,allowshrinking,allowobfuscation class * {
    synthetic <methods>;
}