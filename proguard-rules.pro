# Backtalk's own code finds classes and members by name: the compositor, settings fragments, JSON
# models and the native braille and QuickJS libraries. So it keeps all of its names and members,
# and R8 only optimizes it. The libraries are shrunk and optimized with their own rules.
-dontobfuscate
-keep,allowoptimization class com.google.android.accessibility.** { *; }
-keep,allowoptimization class com.google.android.libraries.accessibility.** { *; }
-keep,allowoptimization class com.google.android.marvin.** { *; }
-keep,allowoptimization class com.google.android.apps.common.** { *; }

# ML Kit creates its components by name, which its own rules don't keep.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# LiteRT-LM's native library calls back into its Java classes by name, and it has no rules.
-keep class com.google.ai.edge.litertlm.** { *; }
