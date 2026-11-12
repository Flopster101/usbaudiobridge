cmake_minimum_required(VERSION 3.22.1)

project("usbaudio")

# Build TinyALSA from source (Submodule)
# tinyalsa has a simple structure: src/pcm.c, src/mixer.c
add_library(tinyalsa STATIC
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/pcm.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/mixer.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/limits.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/pcm_hw.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/pcm_plugin.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/mixer_hw.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/mixer_plugin.c
    ${CMAKE_SOURCE_DIR}/tinyalsa/src/snd_card_plugin.c
)

target_include_directories(tinyalsa PUBLIC
    ${CMAKE_SOURCE_DIR}/tinyalsa/include)

# Build Oboe from source
add_subdirectory(oboe)

add_executable(gadget_monitor_pause gadget_monitor_pause.c)
target_link_libraries(gadget_monitor_pause PRIVATE log)
add_custom_command(TARGET gadget_monitor_pause POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:gadget_monitor_pause>
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libgadget_monitor_pause.so
)

# Define our JNI library
add_library(usbaudio SHARED
    native-lib.cpp
    logging/logging.cpp
    audio/aaudio_engine.cpp
    audio/opensl_engine.cpp
    audio/java_audio_track_engine.cpp
    audio/oboe_engine.cpp
    core/bridge.cpp
    recording/audio_recorder.cpp
    $<$<CONFIG:Debug>:${CMAKE_SOURCE_DIR}/../../debug/cpp/recorder_test.cpp>
)

# Link libraries
find_library(log-lib log)
find_library(aaudio-lib aaudio)
find_library(mediandk-lib mediandk REQUIRED)

target_link_libraries(usbaudio
    tinyalsa
    oboe
    ${log-lib}
    ${aaudio-lib}
    ${mediandk-lib}
    OpenSLES)
