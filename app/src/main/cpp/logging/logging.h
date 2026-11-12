#ifndef LOGGING_H
#define LOGGING_H

#include <android/log.h>
#include <jni.h>

#define TAG "UsbAudioNative"

// JNI Globals
extern JavaVM* javaVM;
extern jobject serviceObj;

// Helper to send logs back to Java
void logToJava(const char* fmt, ...);

// Redirect logs to both Logcat and Java Callback
#define LOGD(...)                                             \
    __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__); \
    logToJava(__VA_ARGS__)
#define LOGE(...)                                             \
    __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__); \
    logToJava(__VA_ARGS__)

// Helper functions for Java callbacks
void reportTidToJava(int tid);
void reportErrorToJava(const char* fmt, ...);
void reportOutputDisconnectToJava();
void reportStateToJava(int stateCode);
void reportStatsToJava(int rate, int period, int bufferSize);
void reportRecordingProgressToJava(int64_t pcmBytes);
void reportRecordingStoppedToJava(const char* error);

// Thread priority helper (uses reportTidToJava)
void setHighPriority();

#endif  // LOGGING_H
