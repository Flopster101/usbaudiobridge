#include "logging.h"

#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <cstdarg>
#include <cstdio>

JavaVM* javaVM = nullptr;
jobject serviceObj = nullptr;

void logToJava(const char* fmt, ...) {
    if (!javaVM || !serviceObj) return;

    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeLog", "(Ljava/lang/String;)V");
    if (mid) {
        jstring jLog = env->NewStringUTF(buffer);
        env->CallVoidMethod(serviceObj, mid, jLog);
        env->DeleteLocalRef(jLog);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportTidToJava(int tid) {
    if (!javaVM || !serviceObj) return;

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeThreadStart", "(I)V");
    if (mid) {
        env->CallVoidMethod(serviceObj, mid, tid);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportErrorToJava(const char* fmt, ...) {
    if (!javaVM || !serviceObj) return;

    char buffer[512];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeError", "(Ljava/lang/String;)V");
    if (mid) {
        jstring jMsg = env->NewStringUTF(buffer);
        env->CallVoidMethod(serviceObj, mid, jMsg);
        env->DeleteLocalRef(jMsg);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportOutputDisconnectToJava() {
    if (!javaVM || !serviceObj) return;

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onOutputDisconnect", "()V");
    if (mid) {
        env->CallVoidMethod(serviceObj, mid);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportStateToJava(int stateCode) {
    if (!javaVM || !serviceObj) return;

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeState", "(I)V");
    if (mid) {
        env->CallVoidMethod(serviceObj, mid, stateCode);
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportStatsToJava(int rate, int period, int bufferSize) {
    if (!javaVM || !serviceObj) {
        // Cannot log here easily as we are in logging implementation, avoid
        // recursion loops if we use LOGE
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "[Native] Stats report failed: VM or ServiceObj null");
        return;
    }

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "[Native] Stats report failed: Could not attach thread");
            return;
        }
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "[Native] Stats report failed: GetEnv error %d",
                            getEnvStat);
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeStats", "(III)V");
    if (mid) {
        env->CallVoidMethod(serviceObj, mid, rate, period, bufferSize);
        if (env->ExceptionCheck()) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                                "[Native] Exception handling onNativeStats!");
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "[Native] Stats report failed: Method ID not found");
    }
    env->DeleteLocalRef(cls);

    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportRecordingProgressToJava(int64_t pcmBytes) {
    if (!javaVM || !serviceObj) return;

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeRecordingProgress", "(J)V");
    if (mid) {
        env->CallVoidMethod(serviceObj, mid, (jlong)pcmBytes);
    }
    env->DeleteLocalRef(cls);
    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void reportRecordingStoppedToJava(const char* error) {
    if (!javaVM || !serviceObj) return;

    JNIEnv* env;
    bool attached = false;
    int getEnvStat = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != 0) return;
        attached = true;
    } else if (getEnvStat != JNI_OK) {
        return;
    }

    jclass cls = env->GetObjectClass(serviceObj);
    jmethodID mid = env->GetMethodID(cls, "onNativeRecordingStopped", "(Ljava/lang/String;)V");
    if (mid) {
        jstring jError = env->NewStringUTF(error);
        env->CallVoidMethod(serviceObj, mid, jError);
        env->DeleteLocalRef(jError);
    }
    env->DeleteLocalRef(cls);
    if (attached) {
        javaVM->DetachCurrentThread();
    }
}

void setHighPriority() {
    pid_t tid = syscall(SYS_gettid);

    // 1. Set Nice value
    int priority = -19;
    setpriority(PRIO_PROCESS, tid, priority);
    LOGD("[Native] Thread %d nicely set to %d", tid, priority);

    // 2. Request Root Escalation to SCHED_FIFO
    reportTidToJava((int)tid);
}
