# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
# Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn java.lang.management.**
# Tink
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
