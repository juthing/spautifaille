# NewPipeExtractor + Rhino (repris de TeamNewPipe/NewPipe app/proguard-rules.pro)
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keep class org.mozilla.javascript.* { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.javascript.engine.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn org.mozilla.javascript.tools.**
-keep class javax.script.** { *; }
-dontwarn javax.script.**
-keep class jdk.dynalink.** { *; }
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**

# Protobuf-lite (NewPipeExtractor : continuations YouTube / YouTube Music, import de playlist par lien).
# Sans cette règle, R8 renomme/supprime les champs des messages générés et protobuf échoue à l'exécution
# avec « Field browseId_ for xxx not found ».
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# Sérialisation Java (voir https://github.com/TeamNewPipe/NewPipe/pull/1441)
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
}

# PrettyTime (dates relatives de l'extracteur) : bundles de ressources chargés par réflexion
# (voir https://github.com/TeamNewPipe/NewPipe/issues/13508)
-keep class org.ocpsoft.prettytime.i18n.Resources* { *; }

# OkHttp / Okio (repris de NewPipe)
-dontwarn okhttp3.**
-dontwarn okio.**
