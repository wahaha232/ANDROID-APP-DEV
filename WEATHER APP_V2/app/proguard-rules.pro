# D:/Claude/ANDROID-APP-DEV/WEATHER APP_V2/app/proguard-rules.pro
# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes InnerClasses

# kotlinx.serialization
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.wahaha232.weatherforecast.**$$serializer { *; }
-keepclassmembers class com.wahaha232.weatherforecast.** {
    *** Companion;
}
-keepclasseswithmembers class com.wahaha232.weatherforecast.** {
    kotlinx.serialization.KSerializer serializer(...);
}
