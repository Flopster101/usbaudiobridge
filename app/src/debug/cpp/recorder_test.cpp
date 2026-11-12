#include <jni.h>

#include <cstdint>

void writeAudioRecorder(const uint8_t *data, size_t size);

extern "C" JNIEXPORT void JNICALL
Java_com_flopster101_usbaudiobridge_AudioService_writeRecordingTest(
    JNIEnv *env, jobject /* this */, jbyteArray data) {
  jsize size = env->GetArrayLength(data);
  jbyte *bytes = env->GetByteArrayElements(data, nullptr);
  writeAudioRecorder(reinterpret_cast<const uint8_t *>(bytes), size);
  env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}
