package com.flopster101.usbaudiobridge

fun AudioService.startRecorderForTest(fd: Int, format: Int): Boolean = startAudioRecorder(fd, format, 48000)

fun AudioService.stopRecorderForTest() = stopAudioRecorder()
