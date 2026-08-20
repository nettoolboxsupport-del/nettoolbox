# =============================================================================
# NetToolbox - R8 / ProGuard rules
#
# Every rule below exists for a reason that is written down. A keep rule with
# no justification is impossible to remove later, because nobody dares find out
# what it was protecting.
# =============================================================================

# Keep line numbers for readable crash reports from sideloaded release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# -----------------------------------------------------------------------------
# JNI entry points
#
# The C side exports symbols built from the fully qualified Java name, e.g.
# Java_de_nettoolbox_vterm_VtermNative_nativeNew. Renaming the class or the
# method breaks the lookup at runtime with UnsatisfiedLinkError - and only in
# release builds, where it is hardest to diagnose.
#
# proguard-android-optimize.txt already carries a generic rule for native
# methods. These are spelled out anyway: the generic rule is a default that a
# future change could drop, and the cost of losing this silently is an app that
# builds cleanly and then cannot ping, measure or open a terminal.
#
# The class names come from @file:JvmName in each *Native.kt.
# -----------------------------------------------------------------------------
-keep class de.nettoolbox.icmp.IcmpNative { *; }
-keep class de.nettoolbox.iperf3.Iperf3Native { *; }
-keep class de.nettoolbox.vterm.VtermNative { *; }

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}


# -----------------------------------------------------------------------------
# jsch (SSH transport)
#
# jsch resolves every algorithm through a name-to-class map and instantiates it
# reflectively. Verified in JSch.java of version 2.28.6, for example:
#
#     config.put("curve25519-sha256",            "com.jcraft.jsch.DH25519");
#     config.put("aes128-gcm@openssh.com",       "com.jcraft.jsch.jce.AES128GCM");
#     config.put("hmac-sha2-256",                "com.jcraft.jsch.jce.HMACSHA256");
#     config.put("ssh-ed25519",                  "com.jcraft.jsch.jce.SignatureEd25519");
#     config.put("chacha20-poly1305@openssh.com","com.jcraft.jsch.bc.ChaCha20Poly1305");
#
# R8 sees no caller for any of these, so without a keep rule it removes them
# all. The failure mode is not a crash at startup but a connection that
# negotiates an algorithm and then cannot instantiate it.
#
# Kept wholesale rather than class by class: the map holds around sixty entries
# across three packages, and a list maintained by hand would fall out of date on
# the first dependency bump - silently, and in the release build only.
# -----------------------------------------------------------------------------
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**


# -----------------------------------------------------------------------------
# Bouncy Castle
#
# Present because Android's JCE provides neither ed25519 nor x25519, which
# current OpenSSH servers use by default. See PHASE6B_NOTES.md.
#
# The provider registers its algorithms through inner "Mappings" classes that
# are themselves loaded by name, so nothing references them statically.
#
# Only the provider packages are kept, not all of Bouncy Castle: the library is
# large and most of it (PQC, CMS, OpenPGP, TLS) is never reached from here.
# If a release build fails to negotiate a key exchange, this is the first place
# to widen - and the APK size is worth measuring either way.
# -----------------------------------------------------------------------------
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jcajce.spec.** { *; }
-dontwarn org.bouncycastle.**

# Bouncy Castle compiles against JDK classes that do not exist on Android.
-dontwarn javax.naming.**
-dontwarn java.awt.**


# -----------------------------------------------------------------------------
# kotlinx.serialization
#
# Used for DataStore payloads (settings, known hosts, SSH profiles) and for
# Navigation Compose's type-safe routes. Generated serializers are referenced
# only through the Companion, which R8 cannot follow.
# -----------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class de.nettoolbox.**$$serializer { *; }
-keepclassmembers class de.nettoolbox.** {
    *** Companion;
}


# -----------------------------------------------------------------------------
# Serialised model classes
#
# Their field names are the on-disk format. Renaming them would not crash - it
# would quietly orphan every stored setting, saved profile and trusted host key
# on update, which is worse: the app would look fine and simply have forgotten
# which servers the user trusts.
# -----------------------------------------------------------------------------
-keepclassmembers class de.nettoolbox.core.datastore.model.** { <fields>; }
-keepclassmembers class de.nettoolbox.feature.ssh.data.KnownHost { <fields>; }
-keepclassmembers class de.nettoolbox.feature.ssh.data.KnownHosts { <fields>; }
-keepclassmembers class de.nettoolbox.feature.ssh.data.SshProfile { <fields>; }
-keepclassmembers class de.nettoolbox.feature.ssh.data.SshProfiles { <fields>; }


# -----------------------------------------------------------------------------
# Room
#
# Room ships consumer rules for its own runtime. Entities are kept here because
# their column names are the database schema; the same reasoning as above.
# -----------------------------------------------------------------------------
-keepclassmembers class de.nettoolbox.core.database.entity.** { <fields>; }


# -----------------------------------------------------------------------------
# Persisted enums
#
# Enum constants are stored BY NAME, never by ordinal - both in Room (see
# NetToolboxConverters) and in the JSON payloads. The constant name is
# therefore part of the stored format, exactly like a column name.
#
# Kotlin bakes the name into the enum constructor as a string literal, so this
# usually survives on its own. "Usually" is not a guarantee worth taking for
# data that has to be readable after an update.
# -----------------------------------------------------------------------------
-keepclassmembers enum de.nettoolbox.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# -----------------------------------------------------------------------------
# MapLibre
#
# The SDK loads its own native library and reaches parts of its Java API from
# JNI, which R8 cannot see. It ships consumer rules; these cover the warnings
# its optional dependencies produce.
# -----------------------------------------------------------------------------
-dontwarn org.maplibre.**


# -----------------------------------------------------------------------------
# Coroutines / OkHttp
#
# Both ship their own consumer rules. These suppress warnings about optional
# classes that are absent on Android by design.
# -----------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
