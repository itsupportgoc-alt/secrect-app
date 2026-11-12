# JNI resolves these by name, so they must survive R8.
-keep class com.secretchat.app.outbox.RustOutbox { *; }

# kotlinx.serialization models are decoded reflectively by name.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
