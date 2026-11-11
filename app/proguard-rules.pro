-keep class com.tulipskun.aixodia.data.model.** { *; }

# JNI entry points for the on-device Laya/JEV-compatible runtime.
-keep class com.tulipskun.aixodia.screen.LocalJevEngine { *; }
-keep class com.tulipskun.aixodia.screen.ScreenAutomationService { *; }

# Tink (backing EncryptedSharedPreferences) references errorprone annotations
# that are compile-time only and absent at runtime. R8 treats them as fatal
# missing classes, so they are suppressed rather than kept.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Tink's optional remote-keyset downloader (KeysDownloader) pulls in Google HTTP
# client and Joda-Time classes that this app never uses. Only that path needs
# them, so the warnings are suppressed instead of adding dead dependencies.
-dontwarn com.google.api.client.http.**
-dontwarn org.joda.time.**
