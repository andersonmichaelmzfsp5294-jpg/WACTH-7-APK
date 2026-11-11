package com.example.heartrateweartest

import android.content.Context
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local TTS capability probe only. Engine/locale availability is not proof of
 * offline synthesis, so offline_supported remains false until a device test
 * explicitly verifies speech with the network disabled.
 */
class WatchTtsCapability(context: Context) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val engine = TextToSpeech(appContext, this)

    @Volatile private var initialized = false
    @Volatile private var initStatus = TextToSpeech.ERROR

    override fun onInit(status: Int) {
        initStatus = status
        initialized = status == TextToSpeech.SUCCESS
    }

    fun snapshot(): JSONObject {
        val result = JSONObject()
            .put("status", if (initialized) "engine_ready" else "engine_initializing")
            .put("offline_supported", false)
            .put("offline_evidence", "not_verified")
            .put("init_status", initStatus)
        if (!initialized) return result
        try {
            val engines = JSONArray()
            engine.engines.orEmpty().take(16).forEach { info ->
                engines.put(JSONObject()
                    .put("name", info.name.take(128))
                    .put("label", info.label?.toString()?.take(128).orEmpty()))
            }
            result.put("engines", engines)
            val locales = JSONArray()
            engine.availableLanguages.orEmpty().take(64).forEach { locale ->
                locales.put(locale.toLanguageTag().take(32))
            }
            result.put("locales", locales)
        } catch (_: Exception) {
            result.put("status", "engine_probe_failed")
        }
        return result
    }

    fun speak(text: String): Boolean {
        // Deliberately disabled until offline capability has a recorded device
        // proof and an explicit user feature flag.
        return false
    }

    fun shutdown() {
        try {
            engine.stop()
            engine.shutdown()
        } catch (_: Exception) {
            // TTS is optional and must not interfere with service shutdown.
        }
    }
}
