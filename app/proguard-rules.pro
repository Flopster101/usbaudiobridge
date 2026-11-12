# ProGuard / R8 rules (minimal)
# Basic keeps for Kotlin, JNI, Parcelable, and enum reflection.

-keepattributes *Annotation*
-keepclassmembers class kotlin.Metadata { *; }

-keepclasseswithmembers class * {
    native <methods>;
}

-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# JNI callbacks/method lookups resolved by exact Java method name from native code.
-keepclassmembers class com.flopster101.usbaudiobridge.AudioService {
    void onNativeLog(java.lang.String);
    void onNativeError(java.lang.String);
    void onNativeState(int);
    void onNativeStats(int,int,int);
    void onNativeRecordingProgress(long);
    void onNativeRecordingStopped(java.lang.String);
    void onNativeThreadStart(int);
    void onOutputDisconnect();
    int initAudioTrack(int,int);
    void startAudioTrack();
    void writeAudioTrack(java.nio.ByteBuffer,int);
    void stopAudioTrack();
    void releaseAudioTrack();
}
