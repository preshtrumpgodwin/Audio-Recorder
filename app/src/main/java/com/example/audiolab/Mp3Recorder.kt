package com.example.audiolab

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.OutputStream
import kotlin.math.abs

class Mp3Recorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 44100
        const val BITRATE_KBPS = 192
    }

    val equalizer = Equalizer(SAMPLE_RATE)
    val compressor = Compressor(SAMPLE_RATE)

    @Volatile var peakLevel = 0f           // 0..1, for the UI meter
    @Volatile var isRecording = false
        private set

    private var thread: Thread? = null

    /** Lists external inputs (USB interface, wired headset, Bluetooth) plus the built-in mic. */
    fun listInputs(): List<AudioDeviceInfo> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
    }

    fun describe(d: AudioDeviceInfo): String {
        val type = when (d.type) {
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            else -> "Type ${d.type}"
        }
        return "$type – ${d.productName}"
    }

    fun start(device: AudioDeviceInfo?, onSaved: (Uri?) -> Unit) {
        if (isRecording) return
        isRecording = true
        thread = Thread({ record(device, onSaved) }, "mp3-recorder").also { it.start() }
    }

    fun stop() { isRecording = false; thread?.join(); thread = null }

    private fun record(device: AudioDeviceInfo?, onSaved: (Uri?) -> Unit) {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, 8192)

        val recorder = AudioRecord.Builder()
            // UNPROCESSED avoids the phone's own AGC/noise suppression when supported
            .setAudioSource(MediaRecorder.AudioSource.UNPROCESSED)
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
            .setBufferSizeInBytes(bufSize * 2)
            .build()
        if (device != null) recorder.setPreferredDevice(device)

        val lame: AndroidLame = LameBuilder()
            .setInSampleRate(SAMPLE_RATE)
            .setOutSampleRate(SAMPLE_RATE)
            .setOutChannels(1)
            .setOutBitrate(BITRATE_KBPS)
            .setMode(LameBuilder.Mode.MONO)
            .setQuality(2)
            .build()

        // Create the MP3 in Music/AudioLab via MediaStore (no storage permission needed)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "rec_${System.currentTimeMillis()}.mp3")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/AudioLab")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        val out: OutputStream? = uri?.let { resolver.openOutputStream(it) }

        val pcm = ShortArray(bufSize / 2)
        val mp3buf = ByteArray((7200 + pcm.size * 1.25).toInt())

        try {
            recorder.startRecording()
            while (isRecording) {
                val n = recorder.read(pcm, 0, pcm.size)
                if (n <= 0) continue
                var peak = 0f
                for (i in 0 until n) {
                    var s = pcm[i] / 32768.0
                    s = equalizer.process(s)       // EQ first, then compression
                    s = compressor.process(s)
                    val clipped = s.coerceIn(-1.0, 1.0)
                    pcm[i] = (clipped * 32767).toInt().toShort()
                    peak = maxOf(peak, abs(clipped).toFloat())
                }
                peakLevel = peak
                val bytes = lame.encode(pcm, pcm, n, mp3buf)
                if (bytes > 0) out?.write(mp3buf, 0, bytes)
            }
            val tail = lame.flush(mp3buf)
            if (tail > 0) out?.write(mp3buf, 0, tail)
        } finally {
            recorder.stop(); recorder.release(); lame.close(); out?.close()
            peakLevel = 0f
            if (uri != null) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            onSaved(uri)
        }
    }
}
