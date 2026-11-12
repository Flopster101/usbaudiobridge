package com.flopster101.usbaudiobridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AudioRecorderInstrumentedTest {
    @Test
    fun audioRecorderTest() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val latch = CountDownLatch(1)
        var service: AudioService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as AudioService.LocalBinder).getService()
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        context.bindService(Intent(context, AudioService::class.java), connection, Context.BIND_AUTO_CREATE)
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        val audioService = service!!
        for (format in listOf(SettingsRepository.RECORDING_FORMAT_WAV, SettingsRepository.RECORDING_FORMAT_FLAC)) {
            val extension = if (format == SettingsRepository.RECORDING_FORMAT_WAV) "wav" else "flac"
            val file = File(context.cacheDir, "recorder_test.$extension")
            val descriptor = android.os.ParcelFileDescriptor.open(
                file,
                android.os.ParcelFileDescriptor.MODE_CREATE or
                    android.os.ParcelFileDescriptor.MODE_READ_WRITE or
                    android.os.ParcelFileDescriptor.MODE_TRUNCATE
            ).detachFd()
            assertTrue(audioService.startRecorderForTest(descriptor, format))
            val pcm = ByteArray(48000 * 4)
            for (frame in 0 until 48000) {
                val sample = (sin(frame * 2.0 * Math.PI * 440.0 / 48000.0) * 12000).toInt().toShort()
                for (channel in 0 until 2) {
                    pcm[frame * 4 + channel * 2] = (sample.toInt() and 0xff).toByte()
                    pcm[frame * 4 + channel * 2 + 1] = (sample.toInt() shr 8).toByte()
                }
            }
            repeat(50) {
                audioService.writeRecordingTest(pcm)
                Thread.sleep(30)
            }
            audioService.stopRecorderForTest()
            Thread.sleep(100)
            assertTrue("$extension is empty", file.length() > 100)
            val bytes = file.readBytes()
            assertTrue(bytes.decodeToString(0, 4) == if (format == 0) "RIFF" else "fLaC")
            if (format == SettingsRepository.RECORDING_FORMAT_WAV) {
                assertEquals(pcm.size * 50 + 44, bytes.size)
                val dataSize = java.nio.ByteBuffer.wrap(bytes, 40, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                assertEquals(pcm.size * 50, dataSize)
            }
        }
        val store = AudioRecordingStore(context, SettingsRepository(context))
        val output = store.open(SettingsRepository.RECORDING_FORMAT_WAV)
        android.os.ParcelFileDescriptor.AutoCloseOutputStream(
            android.os.ParcelFileDescriptor.adoptFd(output.descriptor)
        ).use { it.write(ByteArray(44)) }
        store.completeRecording()
        val recordings = mutableListOf<android.net.Uri>()
        context.contentResolver.query(
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME),
            "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(output.name),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                recordings += android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0)
                )
            }
        }
        assertTrue("MediaStore recording was not published", recordings.isNotEmpty())
        recordings.forEach { context.contentResolver.delete(it, null, null) }
        context.unbindService(connection)
    }
}
