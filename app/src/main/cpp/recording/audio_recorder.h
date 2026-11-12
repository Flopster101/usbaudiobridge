#ifndef AUDIO_RECORDER_H
#define AUDIO_RECORDER_H

#include <media/NdkMediaCodec.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

class AudioRecorder {
public:
  AudioRecorder(int fd, int format, int sampleRate);
  ~AudioRecorder();

  bool start();
  void write(const uint8_t *data, size_t size);
  void stop();

private:
  void run();
  void fail(const std::string &error);
  bool writeAll(const uint8_t *data, size_t size);
  bool writeWavHeader(uint32_t dataSize, bool finalize);
  bool encode(const std::vector<uint8_t> &pcm);
  bool drainFlac(bool endOfStream);
  bool writeFlacOutput(const AMediaCodecBufferInfo &info, const uint8_t *data);
  bool writeFlacHeader(const uint8_t *data, size_t size);

  int fd_;
  int format_;
  int sampleRate_;
  AMediaCodec *codec_ = nullptr;
  std::thread thread_;
  std::vector<uint8_t> pcmBuffer_;
  std::atomic<size_t> bufferRead_{0};
  std::atomic<size_t> bufferWrite_{0};
  std::mutex sleepMutex_;
  std::condition_variable sleepCondition_;
  std::atomic<bool> accepting_{true};
  std::atomic<bool> stopping_{false};
  std::atomic<bool> failed_{false};
  std::mutex errorMutex_;
  std::string error_;
  uint64_t submittedBytes_ = 0;
  uint64_t recordedBytes_ = 0;
  uint64_t inputFrames_ = 0;
  bool flacHeaderWritten_ = false;
};

#endif  // AUDIO_RECORDER_H
