#include "bridge.h"

#include <tinyalsa/pcm.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <thread>
#include <vector>

#include "../audio/aaudio_engine.h"
#include "../audio/audio_common.h"
#include "../audio/java_audio_track_engine.h"
#include "../audio/oboe_engine.h"
#include "../audio/opensl_engine.h"
#include "../audio/ring_buffer.h"
#include "../logging/logging.h"
#include "../recording/audio_recorder.h"

// Define Globals
std::atomic<bool> isRunning{false};
std::atomic<bool> isFinished{true};
std::atomic<bool> isSpeakerMuted{false};
std::atomic<bool> isMicMuted{false};
std::thread bridgeThread;
std::atomic<bool> audioRecorderAvailable{false};
std::mutex audioRecorderMutex;
std::unique_ptr<AudioRecorder> audioRecorder;

void writeAudioRecorder(const uint8_t *data, size_t size) {
  if (!audioRecorderAvailable.load(std::memory_order_acquire))
    return;
  std::lock_guard<std::mutex> lock(audioRecorderMutex);
  if (audioRecorder)
    audioRecorder->write(data, size);
}

// --- Capture Thread ---
// Report actual period size to bridge
void captureLoop(unsigned int card, unsigned int device, RingBuffer *rb,
                 int *out_period_size, int requested_period_size,
                 int requested_rate) {
  setHighPriority();
  struct pcm_config config;
  memset(&config, 0, sizeof(config));
  config.channels = 2;
  config.period_count = 4;
  config.format = PCM_FORMAT_S16_LE;

  struct pcm *pcm = nullptr;

  unsigned int rate = (unsigned int)requested_rate;
  if (rate == 0)
    rate = 48000; // Fallback

  // Configs: Try provided period size, or Smart Auto
  std::vector<size_t> periods;
  std::vector<unsigned int> period_counts;
  // Expanded list to hit exact "/4" targets for common buffer sizes (30ms=1440->360, 20ms=960->240, etc)
  std::vector<size_t> candidates = {4096, 2048, 1024, 960, 512, 480, 360, 256, 240, 192, 128, 120, 96, 64};
  // Larger buffer presets can tolerate/benefit from a less aggressive ALSA period layout.
  double buffer_ms = (rb->capacity() / 4.0) * 1000.0 / (rate > 0 ? rate : 48000);
  if (buffer_ms >= 60.0) {
    period_counts = {6, 8, 4};
  } else {
    period_counts = {4, 6, 8};
  }

  if (requested_period_size > 0) {
    periods.push_back((size_t)requested_period_size);
  } else {
    // Smart Auto: Target ~4 periods per buffer for stability/latency balance.
    size_t buffer_frames = rb->capacity() / 4; // 16-bit stereo = 4 bytes/frame
    size_t target_period = buffer_frames / 4;

    // Find best match (largest size <= target)
    size_t best_match = 64; // Default to smallest
    for (size_t c : candidates) {
        if (c <= target_period) {
            best_match = c;
            break; // Found largest since candidates are desc
        }
    }

    periods.push_back(best_match);

    // Add others as fallback (skip duplicates)
    for (size_t c : candidates) {
        if (c != best_match) periods.push_back(c);
    }
  }

  bool opened = false;

  // Outer loop for retrying connection (waiting for host)
  reportStateToJava(1); // 1 = CONNECTING (Searching/Retrying PCM)
  for (int retry = 0; retry < 20 && isRunning; retry++) {
    config.rate = rate;
    for (size_t p_size : periods) {
      for (unsigned int p_count : period_counts) {
        // Ensure period fits in ring buffer (bytes)
        if (p_size * 4 > rb->capacity()) {
          continue;
        }
        config.period_size = p_size;
        config.period_count = p_count;

        pcm = pcm_open(card, device, PCM_IN, &config);

        if (pcm && pcm_is_ready(pcm)) {
          opened = true;
          if (out_period_size)
            *out_period_size = (int)p_size;
          LOGD("[Native] PCM Device ready. Waiting for Host stream... (Rate: %u, "
               "Period: %zu, Count: %u)",
               rate, p_size, p_count);
          reportStateToJava(2); // 2 = WAITING (PCM Open, No Data)
          break;
        }

        if (pcm) {
          LOGE("[Native] Config %zu x %u failed: %s", p_size, p_count, pcm_get_error(pcm));
          pcm_close(pcm);
          pcm = nullptr;
        }
      }
      if (opened)
        break;
    }

    if (opened)
      break;

    LOGE("[Native] All configs failed. Retrying in 1s...");
    std::this_thread::sleep_for(std::chrono::milliseconds(1000));
  }

  if (!opened || !isRunning) {
    LOGE("[Native] Error: Failed to open PCM after retries.");
    if (pcm) {
      pcm_close(pcm);
      pcm = nullptr;
    }
    isRunning = false;
    return;
  }

  if (!isRunning)
    return;

  unsigned int chunk_bytes = pcm_frames_to_bytes(pcm, config.period_size);
  std::vector<uint8_t> local_buf(chunk_bytes);
  // LOGD("[Native] Capture loop running.");

  int readErrorCount = 0;
  int overrunCount = 0;
  while (isRunning) {
    // Wait up to 100ms for data. This allows checking isRunning frequently.
    int wait_res = pcm_wait(pcm, 100);
    if (wait_res == 0) {
      // Timeout, check isRunning again
      continue;
    }

    int res = pcm_read(pcm, local_buf.data(), chunk_bytes);
    if (res == 0) {
      writeAudioRecorder(local_buf.data(), chunk_bytes);
      size_t written = rb->write(local_buf.data(), chunk_bytes);
      // Stall the capture briefly and retry instead of dropping the frames
      // (dropped audio is audible) - the consumer is only backed up because
      // the output still has data to play, so we can wait.
      for (int it = 0; written < chunk_bytes && it < 40 && isRunning; ++it) {
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
        written += rb->write(local_buf.data() + written, chunk_bytes - written);
      }
      if (written < chunk_bytes) {
        size_t dropped = chunk_bytes - written;
        if (overrunCount++ % 50 == 0) {
          LOGE("[Native] RING BUFFER OVERRUN! (wrote %zu/%u, dropped %zu bytes)",
               written, chunk_bytes, dropped);
        }
      }
      // Reset error count on success
      readErrorCount = 0;
    } else {
      // Failed read
      if (errno == EAGAIN) {
        // No data available yet. Wait slightly and check isRunning.
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
        continue;
      }

      readErrorCount++;

      const char *err_msg = pcm_get_error(pcm);

      // Log occasionally to avoid spam
      if (readErrorCount % 20 == 0) {
        LOGE("[Native] PCM READ FAILING! (Consecutive: %d, Error: %s)",
             readErrorCount, err_msg);
      }

      // FATAL ERROR CHECK
      // If we fail > 50 times consecutively (approx 50 * 20ms = 1 sec), assume
      // device is dead. Also check for explicit disconnect errors if possible
      // (TinyALSA mostly generic, but -ENODEV/EIO common) We rely on count
      // mainly.
      if (readErrorCount > 50) {
        LOGE("[Native] Too many errors. Assuming USB Disconnect.");
        reportErrorToJava("Capture Failed");
        isRunning = false;
        break;
      }

      // Attempt recovery logic
      // If broken pipe (XRUN), prepare might fix it. If physical disconnect,
      // prepare will fail or read will fail again.
      pcm_prepare(pcm);
    }
  }
  if (pcm) {
    pcm_close(pcm);
    pcm = nullptr;
  }
  LOGD("[Native] Host closed device (Capture stopped).");
}

// --- Playback Loop (Mic -> Gadget) ---
// Reads from Android Mic (InputEngine), writes to USB Gadget (PCM_OUT)
void playbackLoop(unsigned int card, unsigned int device, int sampleRate,
                  int engineType, int micSource) {
  setHighPriority();
  LOGD("[Native] Starting playback loop (Mic -> Gadget)...");

  struct pcm_config config;
  memset(&config, 0, sizeof(config));
  config.channels = 2;
  config.rate = sampleRate > 0 ? sampleRate : 48000;
  config.period_size = 1024;
  config.period_count = 4;
  config.format = PCM_FORMAT_S16_LE;

  std::unique_ptr<AudioInputEngine> inputEngine;
  // Currently only supporting AAudio for Input for cleanliness, or fallback?
  // Use AAudio for input.
  inputEngine = std::make_unique<AAudioInputEngine>();
  inputEngine->setInputPreset(micSource);

  if (!inputEngine->open(config.rate, 2)) {
    LOGE("[Native] Failed to open Mic Input Engine");
    return;
  }
  inputEngine->start();

  // Open USB Gadget PCM OUT
  struct pcm *pcm = pcm_open(card, device, PCM_OUT, &config);
  if (!pcm || !pcm_is_ready(pcm)) {
    LOGE("[Native] Failed to open Gadget PCM OUT: %s",
         pcm ? pcm_get_error(pcm) : "null");
    if (pcm)
      pcm_close(pcm);
    inputEngine->stop();
    inputEngine->close();
    return;
  }

  size_t buffer_bytes = pcm_frames_to_bytes(pcm, config.period_size);
  std::vector<uint8_t> buffer(buffer_bytes);

  // Throttle the log when the host has not opened its capture endpoint.
  int writeErrorCount = 0;
  LOGD("[Native] Mic -> Gadget streaming active.");

  while (isRunning) {
    size_t readBytes = inputEngine->read(buffer.data(), buffer_bytes);
    if (readBytes > 0) {
      if (isMicMuted) {
        std::memset(buffer.data(), 0, readBytes);
      }
      int err = pcm_write(pcm, buffer.data(), readBytes);
      if (err) {
        if (++writeErrorCount == 1 || writeErrorCount % 50 == 0) {
          LOGE("[Native] PCM Write Error: %s", pcm_get_error(pcm));
        }
      } else {
        writeErrorCount = 0;
      }
    } else {
      std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
  }

  pcm_close(pcm);
  inputEngine->stop();
  inputEngine->close();
  LOGD("[Native] Playback loop finished.");
}

// --- Bridge Logic ---
void bridgeTask(int card, int device, int bufferSizeFrames,
                int periodSizeFrames, int engineType, int sampleRate,
                int activeDirections, int micSource) {
  setHighPriority();

  bool enableSpeaker = (activeDirections & 1) != 0;
  bool enableMic = (activeDirections & 2) != 0;

  LOGD("[Native] Bridge task starting. Directions: Speaker=%d, Mic=%d",
       enableSpeaker, enableMic);

  std::thread micThread;
  if (enableMic) {
    // Start Mic -> Gadget pipe in separate thread
    // We assume device 0 for both directions as is standard for UAC2 gadget
    micThread = std::thread(playbackLoop, card, device, sampleRate, engineType, micSource);
  }

  if (!enableSpeaker) {
    if (enableMic) {
      reportStateToJava(3); // Streaming (Mic only mode)
      micThread.join();
    }
    reportStateToJava(0);
    isFinished = true;
    if (javaVM)
      javaVM->DetachCurrentThread();
    return;
  }

  // Speaker Logic

  // Use provided buffer size (Minimum 480 to avoid issues)
  size_t deep_buffer_frames = (size_t)std::max(480, bufferSizeFrames);
  // Keep a small internal guard margin to absorb scheduler/USB jitter on
  // older devices without changing the user-visible buffer setting.
  size_t jitter_guard_frames = std::max<size_t>(240, deep_buffer_frames / 4);
  size_t effective_buffer_frames = deep_buffer_frames + jitter_guard_frames;
  LOGD("[Native] Starting Speaker Bridge. Buffer: %zu frames, PeriodReq: %d, "
       "Engine: %d, Rate: %d, Guard: +%zu",
       deep_buffer_frames, periodSizeFrames, engineType, sampleRate,
       jitter_guard_frames);

  size_t bytes_per_frame = 4; // 16-bit stereo
  size_t rb_size = effective_buffer_frames * bytes_per_frame;
  RingBuffer rb(rb_size);

  int actual_period_size = 0;
  std::thread c_thread(captureLoop, card, device, &rb, &actual_period_size,
                       periodSizeFrames, sampleRate);

  int32_t rate = (sampleRate > 0) ? sampleRate : 48000;

  // Select Engine
  AudioEngine *engine = nullptr;
  if (engineType == 1) {
    engine = new OpenSLEngine();
    LOGD("[Native] Using OpenSL ES Engine");
  } else if (engineType == 2) {
    engine = new JavaAudioTrackEngine();
    LOGD("[Native] Using Legacy AudioTrack Engine");
  } else if (engineType == 3) {
    engine = new OboeEngine();
    LOGD("[Native] Using Oboe Engine");
  } else {
    engine = new AAudioEngine();
    LOGD("[Native] Using AAudio Engine");
  }

  if (!engine->open(rate, 2)) {
    LOGE("[Native] Error: Failed to open Audio Engine.");
    isRunning = false;
    delete engine;
    if (c_thread.joinable())
      c_thread.join();
    if (micThread.joinable())
      micThread.join();
    return;
  }

  engine->start();

  // Pre-roll: wait for a stable initial fill before playback starts.
  // Use a slightly higher target on larger buffers for older-device stability.
  size_t configured_ms = (size_t)((deep_buffer_frames * 1000) / rate);
  size_t target_preroll_ms = 50;
  if (configured_ms >= 80) {
    target_preroll_ms = 65;
  } else if (configured_ms >= 60) {
    target_preroll_ms = 55;
  }
  // Cap at 50% of ring capacity to avoid deadlock on tiny buffers.
  size_t target_preroll_bytes =
      (size_t)(rate * target_preroll_ms / 1000) * bytes_per_frame;
  if (target_preroll_bytes > rb.capacity() / 2) {
      target_preroll_bytes = rb.capacity() / 2;
  }
  // Ensure at least 1 frame (avoid 0 waiting)
  if (target_preroll_bytes == 0) target_preroll_bytes = bytes_per_frame;

  LOGD("[Native] Pre-rolling (Target: %zu bytes)...", target_preroll_bytes);
  while (isRunning &&
         rb.available() < target_preroll_bytes) {
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  LOGD("[Native] Host opened device (Streaming started).");
  reportStatsToJava(rate, actual_period_size, (int)deep_buffer_frames);

  int32_t burstFrames = engine->getBurstFrames();
  if (burstFrames <= 0)
    burstFrames = 192; // Fallback
  int32_t rawBurstFrames = burstFrames;

  const char *backendName = "AAudio";
  int32_t minTargetFrames = 120;
  int32_t maxTargetFrames = 480;
  int emptySleepUs = 500;
  if (engineType == 1) {
    backendName = "OpenSL";
    minTargetFrames = 96;
    maxTargetFrames = 192;
    emptySleepUs = 500;
  } else if (engineType == 2) {
    backendName = "AudioTrack";
    minTargetFrames = 120;
    maxTargetFrames = 480;
    emptySleepUs = 400;
  } else if (engineType == 3) {
    backendName = "Oboe";
    minTargetFrames = 96;
    maxTargetFrames = 240;
    emptySleepUs = 250;
  } else {
    backendName = "AAudio";
    minTargetFrames = 96;
    maxTargetFrames = 240;
    emptySleepUs = 250;
  }

  // Some devices report very large "burst" values for AAudio/AudioTrack.
  // Clamp to sane ranges for bridge chunking so adaptive logic stays valid.
  int32_t maxBurstFrames = 384;
  if (engineType == 1) {
    maxBurstFrames = 256;
  } else if (engineType == 2) {
    maxBurstFrames = 960;
  } else {
    maxBurstFrames = 384;
  }
  burstFrames = std::max<int32_t>(96, std::min<int32_t>(burstFrames, maxBurstFrames));
  if (burstFrames != rawBurstFrames) {
    LOGD("[Native] %s burst clamped: raw=%d, using=%d", backendName, rawBurstFrames,
         burstFrames);
  }

  // Use a chunk close to the capture period when available.
  // Backend-specific bounds: AAudio prefers smaller writes for stability on
  // some older devices, AudioTrack can tolerate bigger chunks.
  int32_t targetFrames = (actual_period_size > 0) ? actual_period_size : burstFrames;
  int32_t boundedTarget =
      std::max<int32_t>(minTargetFrames, std::min<int32_t>(targetFrames, maxTargetFrames));
  int32_t chunkFrames = std::max(burstFrames, boundedTarget);
  size_t chunkBytes = chunkFrames * bytes_per_frame;
  int32_t reducedChunkFrames = std::max<int32_t>(96, chunkFrames / 2);
  if (engineType == 1) {
    // OpenSL queueing is less predictable with tiny buffers.
    reducedChunkFrames = std::max<int32_t>(burstFrames, reducedChunkFrames);
  }
  if (reducedChunkFrames > chunkFrames) {
    reducedChunkFrames = chunkFrames;
  }
  // Continuous fill-based chunk steering instead of a fixed latch. Target ~50%
  // of ring capacity for symmetric headroom between host bursts and hiccups.
  size_t targetFillBytes = rb.capacity() / 2;
  size_t reducedChunkBytes = reducedChunkFrames * bytes_per_frame;

  LOGD("[Native] %s chunk strategy: normal=%d, reduced=%d, targetFill=%zu bytes, "
       "emptySleep=%dus",
       backendName, chunkFrames, reducedChunkFrames, targetFillBytes, emptySleepUs);
  std::vector<uint8_t> p_buf(chunkBytes);

  // Consume Loop
  int stats_counter = 0;
  bool isStreaming = true; // Initially true after pre-roll
  auto lastDataTime = std::chrono::steady_clock::now();

  while (isRunning) {
    auto now = std::chrono::steady_clock::now();
    size_t availableBeforeRead = rb.available();

    // Blend chunk size between reduced (low fill) and normal (high fill).
    double fillRatio = static_cast<double>(availableBeforeRead) /
                       static_cast<double>(std::max<size_t>(1, targetFillBytes));
    int32_t desiredChunkFrames = chunkFrames;
    if (fillRatio < 1.0) {
      desiredChunkFrames = chunkFrames -
                           static_cast<int32_t>((chunkFrames - reducedChunkFrames) * (1.0 - fillRatio));
    }
    desiredChunkFrames = std::max(reducedChunkFrames,
                                  std::min(desiredChunkFrames, chunkFrames));
    size_t desiredChunkBytes = desiredChunkFrames * bytes_per_frame;

    size_t read_bytes = rb.read(p_buf.data(), desiredChunkBytes);

    if (read_bytes > 0) {
      lastDataTime = now;
      if (!isStreaming) {
        isStreaming = true;
        // Resume detected
        reportStateToJava(3); // 3 = STREAMING
        reportStatsToJava(rate, actual_period_size, (int)deep_buffer_frames);
        stats_counter = 0;
      }

      if (isSpeakerMuted) {
        std::memset(p_buf.data(), 0, read_bytes);
      }

      engine->write(p_buf.data(), read_bytes);
    } else {
      // Buffer empty. Check for timeout (Idle detection)
      auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                         now - lastDataTime)
                         .count();
      if (isStreaming && elapsed > 1000) {
        isStreaming = false;
        reportStateToJava(4); // 4 = IDLING
        LOGD("[Native] Stream idle for 1s. State -> Waiting.");
      }
      std::this_thread::sleep_for(std::chrono::microseconds(emptySleepUs));
    }

    // Periodic stats update (only when streaming)
    if (isStreaming && ++stats_counter > 500) {
      reportStatsToJava(rate, actual_period_size, (int)deep_buffer_frames);
      stats_counter = 0;
    }
  }

  engine->stop();
  engine->close();
  delete engine;

  if (c_thread.joinable())
    c_thread.join();
  if (micThread.joinable())
    micThread.join();

  LOGD("[Native] Bridge task finished.");
  reportStateToJava(0); // 0 = STOPPED
  isFinished = true;    // Signal we are mostly done (safe to restart)

  // Detach thread if attached (safe to call even if not attached? No, better
  // check) But we reused helper functions that attach/detach locally. Only
  // JavaAudioTrackEngine calls GetEnv/Attach. Standard JNI practice: Detach if
  // you Attached.
  if (javaVM) {
    javaVM->DetachCurrentThread();
  }
}
