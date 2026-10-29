package com.slowva.player.player.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow

/** Where the sound goes. [key] is type plus product name (or address) and names the device preset. */
data class OutputDevice(val key: String, val name: String, val type: Int, val id: Int) {
    val isUsb: Boolean get() = type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    val kind: String get() = kindOf(type)

    companion object {
        fun kindOf(type: Int): String = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "Phone speaker"
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST -> "Bluetooth LE"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
            AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> "Line out"
            AudioDeviceInfo.TYPE_DOCK, AudioDeviceInfo.TYPE_DOCK_ANALOG -> "Dock"
            else -> "Output"
        }
    }
}

/** One USB bit-perfect format the mixer offers. */
data class BitPerfectFormat(val sampleRate: Int, val encoding: Int, val channelMask: Int)

/** Watches output devices for the device layer and the signal path. */
object OutputDevices {
    val current = MutableStateFlow<OutputDevice?>(null)
    val outputRate = MutableStateFlow<Int?>(null)
    /** bit-perfect formats of the current USB device, empty when unsupported */
    val bitPerfectFormats = MutableStateFlow<List<BitPerfectFormat>>(emptyList())

    private lateinit var am: AudioManager
    private val main = Handler(Looper.getMainLooper())
    private val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    fun init(context: Context) {
        if (::am.isInitialized) return
        am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshSoon()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshSoon()
        }, main)
        refresh()
    }

    /** the route settles a moment after the callback */
    private fun refreshSoon() {
        refresh()
        main.postDelayed(::refresh, 600)
    }

    fun refresh() {
        if (!::am.isInitialized) return
        val info = currentInfo()
        val dev = info?.let { toDevice(it) }
        if (dev != current.value) current.value = dev
        outputRate.value = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
        bitPerfectFormats.value = if (info != null && dev?.isUsb == true && Build.VERSION.SDK_INT >= 34) bitPerfect(info) else emptyList()
    }

    fun currentInfo(): AudioDeviceInfo? {
        if (!::am.isInitialized) return null
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { am.getAudioDevicesForAttributes(media).firstOrNull() }.getOrNull()?.let { routed ->
                // the routed entry can lack the product name; prefer the full one from the device list
                return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == routed.id } ?: routed
            }
        }
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).maxByOrNull { rank(it.type) }
    }

    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 6
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> 5
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> 4
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 4
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_DOCK -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 1
        else -> 0
    }

    private fun toDevice(d: AudioDeviceInfo): OutputDevice {
        val product = d.productName?.toString()?.trim().orEmpty()
        val builtin = d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || d.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val ident = when {
            builtin -> ""
            product.isNotEmpty() && product != Build.MODEL -> product
            else -> d.address.orEmpty()
        }
        val name = if (builtin || product.isEmpty() || product == Build.MODEL) OutputDevice.kindOf(d.type) else product
        return OutputDevice("${d.type}:$ident", name, d.type, d.id)
    }

    @RequiresApi(34)
    private fun bitPerfect(d: AudioDeviceInfo): List<BitPerfectFormat> = runCatching {
        am.getSupportedMixerAttributes(d)
            .filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .map { BitPerfectFormat(it.format.sampleRate, it.format.encoding, it.format.channelMask) }
    }.getOrDefault(emptyList())

    /**
     * Asks the mixer for bit-perfect output at [sampleRate] on the current USB device, or clears it.
     * Returns null on success, else why not.
     */
    fun setBitPerfect(on: Boolean, sampleRate: Int?): String? {
        if (Build.VERSION.SDK_INT < 34) return "Needs Android 14"
        val dev = currentInfo() ?: return "No output device"
        return setBitPerfect34(dev, on, sampleRate)
    }

    @RequiresApi(34)
    private fun setBitPerfect34(dev: AudioDeviceInfo, on: Boolean, sampleRate: Int?): String? {
        if (!on) { runCatching { am.clearPreferredMixerAttributes(media, dev) }; return null }
        val options = am.getSupportedMixerAttributes(dev).filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
        if (options.isEmpty()) return "This USB device has no bit-perfect mode"
        val stereo16 = options.filter { it.format.encoding == AudioFormat.ENCODING_PCM_16BIT && it.format.channelCount == 2 }
        val pick = stereo16.firstOrNull { it.format.sampleRate == sampleRate }
            ?: return "No bit-perfect 16-bit stereo format at ${sampleRate ?: "this"} Hz"
        return if (am.setPreferredMixerAttributes(media, dev, pick)) null else "The system refused bit-perfect mode"
    }
}
