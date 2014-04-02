# Not finished

-injars target/client-0.1-SNAPSHOT-bundled.jar
-outjars target/lighthouse.jar
-libraryjars <java.home>/lib/rt.jar
-target 8

-dontoptimize
-dontobfuscate

-keep class lighthouse.Main {
    public static void main(java.lang.String[]);
    public static void realMain(java.nio.file.Path, java.lang.String[]);
}

-keepclassmembers class * {
    public void initialize();
}