#include "audio_recorder.h"

#include <media/NdkMediaFormat.h>

#include <cerrno>
#include <chrono>
#include <cstring>
#include <limits>
#include <unistd.h>

#include "../logging/logging.h"

namespace {
constexpr int FORMAT_WAV = 0;
constexpr int FORMAT_FLAC = 1;
constexpr size_t WAV_HEADER_SIZE = 44;

void putLe16(uint8_t *dest, uint16_t value) {
  dest[0] = value & 0xff;
  dest[1] = (value >> 8) & 0xff;
}

void putLe32(uint8_t *dest, uint32_t value) {
  dest[0] = value & 0xff;
  dest[1] = (value >> 8) & 0xff;
  dest[2] = (value >> 16) & 0xff;
  dest[3] = (value >> 24) & 0xff;
}

bool writeHeaderAt(int fd, const uint8_t *header, size_t size) {
  size_t written = 0;
  while (written < size) {
    ssize_t result = pwrite(fd, header + written, size - written, written);
    if (result < 0 && errno == EINTR)
      continue;
    if (result <= 0)
      return false;
    written += result;
  }
  return true;
}
}  // namespace

AudioRecorder::AudioRecorder(int fd, int format, int sampleRate)
    : fd_(fd), format_(format), sampleRate_(sampleRate), pcmBuffer_(8 * 1024 * 1024) {}

AudioRecorder::~AudioRecorder() {
  if (thread_.joinable())
    stop();
  if (codec_) {
    AMediaCodec_delete(codec_);
    codec_ = nullptr;
  }
  if (fd_ >= 0) {
    close(fd_);
    fd_ = -1;
  }
}

bool AudioRecorder::start() {
  if (fd_ < 0 || sampleRate_ <= 0 ||
      (format_ != FORMAT_WAV && format_ != FORMAT_FLAC)) {
    error_ = "Invalid recording configuration";
    return false;
  }

  if (format_ == FORMAT_WAV) {
    if (!writeWavHeader(0, false))
      return false;
  } else {
    codec_ = AMediaCodec_createCodecByName("c2.android.flac.encoder");
    if (!codec_) {
      error_ = "C2 FLAC encoder is unavailable";
      return false;
    }
    AMediaFormat *format = AMediaFormat_new();
    if (!format) {
      error_ = "Could not allocate FLAC encoder format";
      return false;
    }
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/flac");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, sampleRate_);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, 2);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FLAC_COMPRESSION_LEVEL, 5);
    media_status_t configureResult = AMediaCodec_configure(
        codec_, format, nullptr, nullptr, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (configureResult != AMEDIA_OK || AMediaCodec_start(codec_) != AMEDIA_OK) {
      error_ = "Could not start the C2 FLAC encoder";
      return false;
    }
  }

  thread_ = std::thread(&AudioRecorder::run, this);
  return true;
}

void AudioRecorder::write(const uint8_t *data, size_t size) {
  if (!accepting_)
    return;
  size -= size % 4;
  if (size == 0)
    return;
  if (format_ == FORMAT_WAV &&
      submittedBytes_ + size > std::numeric_limits<uint32_t>::max() - 36) {
    fail("WAV recording reached its 4 GB limit");
    return;
  }
  size_t write = bufferWrite_.load(std::memory_order_relaxed);
  size_t read = bufferRead_.load(std::memory_order_acquire);
  if (size > pcmBuffer_.size() - (write - read)) {
    // Never block the real-time USB capture thread on encoding or storage.
    fail("Recording stopped: encoder or storage is too slow");
    return;
  }
  size_t first = std::min(size, pcmBuffer_.size() - write % pcmBuffer_.size());
  std::memcpy(pcmBuffer_.data() + write % pcmBuffer_.size(), data, first);
  if (first < size)
    std::memcpy(pcmBuffer_.data(), data + first, size - first);
  bufferWrite_.store(write + size, std::memory_order_release);
  submittedBytes_ += size;
  sleepCondition_.notify_one();
}

void AudioRecorder::stop() {
  accepting_ = false;
  {
    std::lock_guard<std::mutex> lock(sleepMutex_);
    stopping_ = true;
  }
  sleepCondition_.notify_one();
  if (thread_.joinable())
    thread_.join();
}

void AudioRecorder::fail(const std::string &error) {
  bool expected = false;
  if (!failed_.compare_exchange_strong(expected, true))
    return;
  accepting_ = false;
  {
    std::lock_guard<std::mutex> lock(errorMutex_);
    error_ = error;
  }
  LOGE("[Native] %s", error.c_str());
  sleepCondition_.notify_one();
}

bool AudioRecorder::writeAll(const uint8_t *data, size_t size) {
  size_t written = 0;
  while (written < size) {
    ssize_t result = ::write(fd_, data + written, size - written);
    if (result < 0 && errno == EINTR)
      continue;
    if (result <= 0) {
      fail(std::string("Recording write failed: ") + strerror(errno));
      return false;
    }
    written += result;
  }
  return true;
}

bool AudioRecorder::writeWavHeader(uint32_t dataSize, bool finalize) {
  uint8_t header[WAV_HEADER_SIZE] = {};
  std::memcpy(header, "RIFF", 4);
  putLe32(header + 4, dataSize + 36);
  std::memcpy(header + 8, "WAVEfmt ", 8);
  putLe32(header + 16, 16);
  putLe16(header + 20, 1);
  putLe16(header + 22, 2);
  putLe32(header + 24, sampleRate_);
  putLe32(header + 28, sampleRate_ * 4);
  putLe16(header + 32, 4);
  putLe16(header + 34, 16);
  std::memcpy(header + 36, "data", 4);
  putLe32(header + 40, dataSize);
  if (finalize) {
    if (!writeHeaderAt(fd_, header, sizeof(header))) {
      fail("Could not finalize WAV header");
      return false;
    }
  } else if (!writeAll(header, sizeof(header))) {
    return false;
  }
  return true;
}

bool AudioRecorder::writeFlacHeader(const uint8_t *data, size_t size) {
  if (flacHeaderWritten_)
    return true;
  if (size < 4 || std::memcmp(data, "fLaC", 4) != 0) {
    fail("FLAC encoder returned invalid metadata");
    return false;
  }
  flacHeaderWritten_ = true;
  return writeAll(data, size);
}

bool AudioRecorder::writeFlacOutput(const AMediaCodecBufferInfo &info,
                                    const uint8_t *data) {
  if (info.size == 0)
    return true;
  if ((info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) != 0)
    return writeFlacHeader(data, info.size);
  if (!flacHeaderWritten_) {
    fail("FLAC encoder did not provide stream metadata");
    return false;
  }
  return writeAll(data, info.size);
}

bool AudioRecorder::drainFlac(bool endOfStream) {
  while (!failed_) {
    AMediaCodecBufferInfo info;
    ssize_t index = AMediaCodec_dequeueOutputBuffer(
        codec_, &info, endOfStream ? 100000 : 0);
    if (index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
      return !endOfStream;
    if (index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
      AMediaFormat *format = AMediaCodec_getOutputFormat(codec_);
      void *data = nullptr;
      size_t size = 0;
      if (format && AMediaFormat_getBuffer(format, "csd-0",
                                           &data, &size)) {
        writeFlacHeader(static_cast<uint8_t *>(data), size);
      }
      if (format)
        AMediaFormat_delete(format);
      continue;
    }
    if (index < 0)
      continue;
    size_t capacity = 0;
    uint8_t *data = AMediaCodec_getOutputBuffer(codec_, index, &capacity);
    bool valid = data && info.offset >= 0 && info.size >= 0 &&
                 static_cast<size_t>(info.offset) + info.size <= capacity;
    if (valid)
      writeFlacOutput(info, data + info.offset);
    else
      fail("FLAC encoder returned an invalid buffer");
    AMediaCodec_releaseOutputBuffer(codec_, index, false);
    if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0)
      return true;
  }
  return false;
}

bool AudioRecorder::encode(const std::vector<uint8_t> &pcm) {
  size_t offset = 0;
  while (offset < pcm.size() && !failed_) {
    ssize_t index = AMediaCodec_dequeueInputBuffer(codec_, 100000);
    if (index < 0) {
      fail("Timed out waiting for the FLAC encoder");
      return false;
    }
    size_t capacity = 0;
    uint8_t *buffer = AMediaCodec_getInputBuffer(codec_, index, &capacity);
    size_t size = std::min(pcm.size() - offset, capacity - capacity % 4);
    if (!buffer || size == 0) {
      fail("FLAC encoder has no usable input buffer");
      return false;
    }
    std::memcpy(buffer, pcm.data() + offset, size);
    uint64_t presentationTimeUs = inputFrames_ * 1000000ULL / sampleRate_;
    inputFrames_ += size / 4;
    if (AMediaCodec_queueInputBuffer(codec_, index, 0, size,
                                     presentationTimeUs, 0) != AMEDIA_OK) {
      fail("Could not queue audio for FLAC encoding");
      return false;
    }
    offset += size;
    drainFlac(false);
  }
  return !failed_;
}

void AudioRecorder::run() {
  auto lastStats = std::chrono::steady_clock::now();
  while (!failed_) {
    size_t read = bufferRead_.load(std::memory_order_relaxed);
    size_t write = bufferWrite_.load(std::memory_order_acquire);
    size_t size = write - read;
    if (size == 0) {
      if (stopping_)
        break;
      std::unique_lock<std::mutex> lock(sleepMutex_);
      sleepCondition_.wait_for(lock, std::chrono::milliseconds(100));
      continue;
    }
    size = std::min(size, pcmBuffer_.size() - read % pcmBuffer_.size());
    const uint8_t *pcm = pcmBuffer_.data() + read % pcmBuffer_.size();
    std::vector<uint8_t> input(pcm, pcm + size);

    bool ok = format_ == FORMAT_WAV ? writeAll(input.data(), input.size())
                                    : encode(input);
    if (!ok)
      break;
    bufferRead_.store(read + size, std::memory_order_release);
    recordedBytes_ += size;
    auto now = std::chrono::steady_clock::now();
    if (now - lastStats >= std::chrono::seconds(1)) {
      reportRecordingProgressToJava(recordedBytes_);
      lastStats = now;
    }
  }

  if (format_ == FORMAT_FLAC && !failed_ && drainFlac(false)) {
    ssize_t index = AMediaCodec_dequeueInputBuffer(codec_, 100000);
    if (index < 0) {
      fail("Could not finalize FLAC recording");
    } else if (AMediaCodec_queueInputBuffer(
                   codec_, index, 0, 0,
                   inputFrames_ * 1000000ULL / sampleRate_,
                   AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != AMEDIA_OK) {
      fail("Could not send end of stream to FLAC encoder");
    } else {
      auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
      while (!failed_ && !drainFlac(true)) {
        if (std::chrono::steady_clock::now() >= deadline) {
          fail("Timed out finalizing FLAC recording");
          break;
        }
      }
    }
  }

  if (format_ == FORMAT_WAV)
    writeWavHeader(static_cast<uint32_t>(recordedBytes_), true);
  if (fsync(fd_) != 0)
    fail(std::string("Could not sync recording: ") + strerror(errno));
  if (codec_) {
    AMediaCodec_stop(codec_);
    AMediaCodec_delete(codec_);
    codec_ = nullptr;
  }
  reportRecordingProgressToJava(recordedBytes_);
  std::string error;
  {
    std::lock_guard<std::mutex> lock(errorMutex_);
    error = error_;
  }
  reportRecordingStoppedToJava(error.c_str());
}
