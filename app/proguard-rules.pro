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
# R8 runs in full: shrinking, optimisation and obfuscation (M-175; Play's
# optimisation threshold). Obfuscated stack traces are read back with the
# mapping file R8 writes to app/build/outputs/mapping/release/mapping.txt; the
# AAB carries the same file in BUNDLE-METADATA, so Play Vitals deobfuscates on
# its own. A build from source (F-Droid, GitHub) has no Play copy: keep each
# release's mapping.txt, or its crash reports cannot be read.
#
# Libraries that already ship their own rules (merged automatically): Sora
# editor + language-textmate (tm4e models, joni), Gson, OkHttp, coroutines,
# DataStore and the AndroidX set. Markwon, commonmark, Prism4j and JLatexMath
# ship none; the rules at the end of this file cover what they need.
# ---------------------------------------------------------------------------

# Preserve exact file/line info so Play crash reports keep accurate frames.
-keepattributes SourceFile,LineNumberTable

# R8 traces every reference. Both of these are compile-time-only
# ghosts that never exist at runtime on Android (verified via R8's missing_rules.txt):
# - javax.lang.model.element.Modifier: referenced by error-prone's @IncompatibleModifiers
#   annotation (annotation processing machinery, not runtime code).
# - kotlin.Cloneable$DefaultImpls: stale metadata reference from Sora's ShareableData
#   (the known Kotlin 2.3.10 <-> Sora metadata quirk).
-dontwarn javax.lang.model.element.Modifier
-dontwarn kotlin.Cloneable$DefaultImpls

# ---------------------------------------------------------------------------
# Keep rules. Each one names the break it fixes, found on an R8 release build
# on the emulator. Deleting a rule brings its break back.
# ---------------------------------------------------------------------------

# JLatexMath: MacroInfo loads this class by a name held in a string, then calls
# getConstructor().newInstance() and getDeclaredMethod("executeMacro", ...) on it.
# Without the rule, shrinking removes the constructor: logcat shows
# "Cannot load package org.scilab.forge.jlatexmath.NewCommandMacro" /
# "NoSuchMethodException: ...NewCommandMacro.<init> []", and every predefined
# macro (\dfrac, \tfrac, \operatorname, \dbinom, \pmod, the pmatrix environment...)
# renders as raw LaTeX source instead of math.
-keep class org.scilab.forge.jlatexmath.NewCommandMacro {
    public <init>();
    public java.lang.String executeMacro(org.scilab.forge.jlatexmath.TeXParser, java.lang.String[]);
}
