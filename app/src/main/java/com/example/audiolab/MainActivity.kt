package com.example.audiolab

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioDeviceInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var recorder: Mp3Recorder
    private var inputs: List<AudioDeviceInfo> = emptyList()
    private lateinit var spinner: Spinner
    private lateinit var outSpinner: Spinner
    private var outputs: List<AudioDeviceInfo> = emptyList()
    private lateinit var recordBtn: Button
    private lateinit var status: TextView
    private lateinit var meter: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recorder = Mp3Recorder(this)
        setContentView(buildUi())
        ensurePermission()
        refreshInputs()
        meter.postDelayed(object : Runnable {            // simple level-meter poll
            override fun run() { meter.progress = (recorder.peakLevel * 100).toInt(); meter.postDelayed(this, 50) }
        }, 50)
    }

    private fun ensurePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    private fun refreshInputs() {
        inputs = recorder.listInputs()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            inputs.map { recorder.describe(it) })
        // Prefer a USB/external input if one is plugged in
        val usb = inputs.indexOfFirst {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
        if (usb >= 0) spinner.setSelection(usb)

        outputs = recorder.listOutputs()
        outSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            outputs.map { recorder.describe(it) })
        val outUsb = outputs.indexOfFirst {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }
        if (outUsb >= 0) outSpinner.setSelection(outUsb)
    }

    private fun toggleRecording() {
        if (recorder.isRecording) {
            recordBtn.isEnabled = false
            Thread { recorder.stop(); runOnUiThread {
                recordBtn.isEnabled = true; recordBtn.text = "● Record"
                status.text = "Saved to Music/AudioLab" } }.start()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) { ensurePermission(); return }
            recorder.start(inputs.getOrNull(spinner.selectedItemPosition),
                outputs.getOrNull(outSpinner.selectedItemPosition)) { }
            recordBtn.text = "■ Stop"; status.text = "Recording…"
        }
    }

    // ---------- UI (built in code to keep the project to a few files) ----------
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4); textSize = 15f }
        fun slider(min: Int, max: Int, start: Int, onChange: (Int) -> Unit) = SeekBar(this).apply {
            this.max = max - min; progress = start - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) = onChange(p + min)
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        root.addView(label("Input source (plug in USB audio interface / mic, then refresh)"))
        spinner = Spinner(this); root.addView(spinner)
        root.addView(label("Monitor output (earpiece / headphones)"))
        outSpinner = Spinner(this); root.addView(outSpinner)
        root.addView(Switch(this).apply {
            text = "Live monitor (use headphones!)"
            setOnCheckedChangeListener { _, c -> recorder.monitorEnabled = c }
        })
        val volLabel = TextView(this).apply { text = "Monitor volume: 80%" }
        root.addView(volLabel)
        root.addView(slider(0, 100, 80) { v -> volLabel.text = "Monitor volume: $v%"; recorder.monitorVolume = v / 100f })
        root.addView(Button(this).apply { text = "Refresh inputs"; setOnClickListener { refreshInputs() } })

        meter = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(label("Level")); root.addView(meter)

        // ----- EQ -----
        root.addView(label("Equalizer (±12 dB)"))
        val names = listOf("60 Hz", "230 Hz", "910 Hz", "3.6 kHz", "14 kHz")
        names.forEachIndexed { i, n ->
            val v = TextView(this).apply { text = "$n: 0 dB" }
            root.addView(v)
            root.addView(slider(-12, 12, 0) { db -> v.text = "$n: $db dB"; recorder.equalizer.setGain(i, db.toDouble()) })
        }

        // ----- Compressor -----
        root.addView(label("Compressor"))
        val sw = Switch(this).apply {
            text = "Enable"; isChecked = true
            setOnCheckedChangeListener { _, c -> recorder.compressor.enabled = c }
        }
        root.addView(sw)
        fun comp(name: String, unit: String, min: Int, max: Int, start: Int, set: (Int) -> Unit) {
            val v = TextView(this).apply { text = "$name: $start $unit" }
            root.addView(v)
            root.addView(slider(min, max, start) { x -> v.text = "$name: $x $unit"; set(x) })
        }
        comp("Threshold", "dB", -60, 0, -18) { recorder.compressor.thresholdDb = it.toDouble() }
        comp("Ratio", ": 1", 1, 20, 4)       { recorder.compressor.ratio = it.toDouble() }
        comp("Attack", "ms", 1, 200, 10)     { recorder.compressor.attackMs = it.toDouble() }
        comp("Release", "ms", 10, 1000, 120) { recorder.compressor.releaseMs = it.toDouble() }
        comp("Make-up gain", "dB", 0, 24, 6) { recorder.compressor.makeupDb = it.toDouble() }

        recordBtn = Button(this).apply { text = "● Record"; setOnClickListener { toggleRecording() } }
        status = TextView(this).apply { text = "Ready"; setTextColor(Color.GRAY) }
        root.addView(recordBtn); root.addView(status)

        return ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onDestroy() { if (recorder.isRecording) recorder.stop(); super.onDestroy() }
}
