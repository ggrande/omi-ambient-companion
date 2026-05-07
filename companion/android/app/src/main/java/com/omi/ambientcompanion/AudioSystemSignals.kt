package com.omi.ambientcompanion

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import org.json.JSONArray
import org.json.JSONObject

object AudioSystemSignals {
    fun snapshot(context: Context): JSONObject {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputDevices = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.typeName() }
        val outputDevices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.typeName() }
        return JSONObject()
            .put("mode", modeName(audio.mode))
            .put("microphone_mute", audio.isMicrophoneMute)
            .put("music_active", audio.isMusicActive)
            .put("speakerphone_on", audio.isSpeakerphoneOn)
            .put("bluetooth_sco_on", audio.isBluetoothScoOn)
            .put("wired_headset_on", audio.isWiredHeadsetOn)
            .put("input_devices", JSONArray(inputDevices))
            .put("output_devices", JSONArray(outputDevices))
    }

    fun label(context: Context): String {
        val s = snapshot(context)
        return "Audio mode: ${s.optString("mode")} | mic muted: ${s.optBoolean("microphone_mute")} | SCO: ${s.optBoolean("bluetooth_sco_on")}"
    }

    private fun modeName(mode: Int): String = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in_call"
        AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
        else -> "unknown_$mode"
    }

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin_mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        AudioDeviceInfo.TYPE_TELEPHONY -> "telephony"
        else -> "type_$type"
    }
}
