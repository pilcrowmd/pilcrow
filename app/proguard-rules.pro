# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# signingConfig, minificationEnabled and shrinkResources flags in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# ---------------------------------------------------------------------------
# NO-OP R8 (v1.0.2 release hygiene): R8 is enabled ONLY so it emits mapping.txt
# into the AAB (Play Vitals symbolication + kills the Console "no deobfuscation
# file" warning). The three -dont flags below make it byte-neutral: no code is
# removed, renamed, or rewritten — release behavior is identical to minify-off.
# Real shrinking/obfuscation is a deliberate, separately-UAT'd future change:
# removing ANY of these flags requires its own release with full on-device UAT
# (Prism4j/Sora/JLatexMath have reflective paths that would need keep rules).
# ---------------------------------------------------------------------------
-dontshrink
-dontobfuscate
-dontoptimize

# Preserve exact file/line info so Play crash reports keep accurate frames.
-keepattributes SourceFile,LineNumberTable

# R8 still traces references even in no-op mode. Both of these are compile-time-only
# ghosts that never exist at runtime on Android (verified via R8's missing_rules.txt):
# - javax.lang.model.element.Modifier: referenced by error-prone's @IncompatibleModifiers
#   annotation (annotation processing machinery, not runtime code).
# - kotlin.Cloneable$DefaultImpls: stale metadata reference from Sora's ShareableData
#   (the known Kotlin 2.3.10 <-> Sora metadata quirk, see D7-01).
-dontwarn javax.lang.model.element.Modifier
-dontwarn kotlin.Cloneable$DefaultImpls
