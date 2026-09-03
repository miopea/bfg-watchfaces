# R8 rules for the phone app.
#
# WHY THIS FILE EXISTS
# --------------------
# Play reports "App optimization is below our threshold — Obfuscation (1%)"
# because the release build ran with `isMinifyEnabled = false`. Turning R8 on
# is not a free win here: this app reaches native code over JNI and signs APKs
# on the device, and both of those resolve names AT RUNTIME. R8 renaming a
# class that something looks up by name does not fail the build, it fails on a
# wrist. Every rule below names the thing that would break.
#
# The failure mode to watch for is silence: pack returns null, or the signer
# cannot find an algorithm, and the app reports "could not build the face"
# rather than anything about obfuscation.

# --- JNI: the pack bridge -----------------------------------------------------
#
# `nativeCompilePackage` is bound by its FULLY QUALIFIED name — the Rust side
# exports Java_com_bfg_watchfaces_mobile_pack_PackBridge_nativeCompilePackage —
# so the class name, the method name and the package all have to survive.
#
# `includedescriptorclasses` is load-bearing: the signature takes
# PackBridge$Resource[], and without it R8 keeps the method but is free to
# rename the type in its descriptor, which no longer matches what the native
# side expects.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# The Rust side reads these fields with GetFieldID, BY NAME. They are @JvmField
# precisely so there is no getter to go through, which also means there is
# nothing else pointing at them for R8 to follow — it would happily rename all
# three to a, b, c.
-keep class com.bfg.watchfaces.mobile.pack.PackBridge$Resource {
    <fields>;
    <init>(...);
}
-keep class com.bfg.watchfaces.mobile.pack.PackBridge {
    public static *** compileApk(...);
}

# --- On-device APK signing ----------------------------------------------------
#
# BouncyCastle resolves algorithm implementations through JCA provider lookups,
# which are string-keyed and invisible to R8. ApkSigning uses the Jca* builders
# (JcaX509v3CertificateBuilder, JcaContentSignerBuilder), and those reach the
# provider classes only by name. Keeping the library unobfuscated costs size in
# a dependency; getting it wrong costs every face anybody tries to send.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# apksig picks signature schemes reflectively in places, and it is the last step
# before Watch Face Push refuses an artefact.
-keep class com.android.apksig.** { *; }
-dontwarn com.android.apksig.**

# --- Google's Watch Face Push validator ---------------------------------------
#
# Issues the token without a network call. Treated as opaque: it is Google's
# code, we do not know what it looks up by name, and a token that fails to
# issue reads as "the watch refused the face".
-keep class com.google.android.wearable.watchface.validator.** { *; }
-dontwarn com.google.android.wearable.watchface.validator.**

# --- Reflection metadata ------------------------------------------------------
#
# Signature and InnerClasses keep generic types readable, which Compose and the
# Wearable Data Layer both rely on. Annotations must survive for anything that
# reads them at runtime.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Kotlin's own metadata, so reflection over Kotlin types keeps working.
-keep class kotlin.Metadata { *; }

# --- Crash readability --------------------------------------------------------
#
# Renaming stays on — that is the point — but keep line numbers and map the file
# name, so an uploaded mapping file can turn a stack trace back into something
# readable. Without SourceFile/LineNumberTable a deobfuscated trace still has no
# line numbers.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# --- References that are never reached on a phone -----------------------------
#
# R8 refuses to finish while these are unresolved. None of them are a defect and
# none are ours; they arrive transitively with Google's Watch Face Push
# validator, which brings Xerces (to validate the face against the XSD) and
# AutoValue along with it.
#
# `javax.lang.model.*` is the ANNOTATION PROCESSING API. It exists in a JDK at
# compile time and never on Android — the classes referencing it are AutoValue's
# processor and its shaded JavaPoet, which do not run in the app.
#
# `PsychoPathXPathTypeHelper` is an optional XPath 2.0 backend Xerces looks for
# when a schema uses XSD 1.1 assertions. Ours does not, and it is not on the
# device, so the reference is never followed.
#
# These are dontwarn and NOT keep on purpose: keeping them would ask R8 to
# retain classes that do not exist. Silencing the reference is the correct
# instruction, and it is safe precisely because nothing calls into them.
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn org.eclipse.wst.xml.xpath2.processor.PsychoPathXPathTypeHelper
