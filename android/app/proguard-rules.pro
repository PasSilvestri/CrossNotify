# Keep Kotlinx Serialization annotations and generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.crossnotify.app.**$$serializer { *; }
-keepclassmembers class com.crossnotify.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.crossnotify.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
