#ifndef BRIDGE_H
#define BRIDGE_H

#include <jni.h>

#include <atomic>
#include <thread>

// Global Execution State
extern std::atomic<bool> isRunning;
extern std::atomic<bool> isFinished;  // Synchronization flag
extern std::atomic<bool> isSpeakerMuted;
extern std::atomic<bool> isMicMuted;
extern std::thread bridgeThread;

// Main Bridge Task
void bridgeTask(int card, int device, int bufferSizeFrames, int periodSizeFrames, int engineType,
                int sampleRate, int activeDirections, int micSource);

void writeAudioRecorder(const uint8_t *data, size_t size);

#endif  // BRIDGE_H
