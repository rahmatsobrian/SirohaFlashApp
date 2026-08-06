# libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**

# Shizuku
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**

# Keep our data/model classes used for log export (reflection-free but safe)
-keep class com.siroha.flashtool.data.** { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
