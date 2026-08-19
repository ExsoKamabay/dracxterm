# JNI entry points are resolved by name; keep the native bridge class intact.
-keep class com.dracxterm.NativeTerminal { *; }
-keepclasseswithmembernames class * { native <methods>; }
