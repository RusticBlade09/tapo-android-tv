package za.munnik.tapoatv

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Camera control on port 443 (PTZ, name), the way pytapo's "pytapo" transport does it:
 * encrypt_type 3 login + securePassthrough, or the plain legacy login on old firmware.
 * shortcut: no TPAP/SPAKE2+ login (newest firmware, error -40211) — add it if a camera needs it.
 */
class TapoControl(private val host: String, private val creds: Creds) {
    private var stok: String? = null
    private var cnonce = ""
    private var hp = "" // the hashed password the camera accepted (MD5 or SHA256)
    private var lsk: ByteArray? = null
    private var ivb: ByteArray? = null
    private var seq = 0

    /** Relative PTZ move (pytapo moveMotor). */
    fun move(x: Int, y: Int) {
        call("motorMove", JSONObject().put("motor", JSONObject().put("move",
            JSONObject().put("x_coord", x.toString()).put("y_coord", y.toString()))))
    }

    /** The camera's own microphone (pytapo getAudioConfig): true = muted, so live sound AND recordings are silent. */
    fun micMuted(): Boolean =
        call("getAudioConfig", JSONObject().put("method", "get").put("audio_config", JSONObject().put("name", JSONArray().put("microphone"))))
            .getJSONObject("audio_config").getJSONObject("microphone").optString("mute") == "on"

    /** pytapo setMicrophone(mute=…). */
    fun setMicMuted(mute: Boolean) {
        call("setMicrophoneVolume", JSONObject().put("method", "set").put("audio_config",
            JSONObject().put("microphone", JSONObject().put("mute", if (mute) "on" else "off"))))
    }

    /** Saved PTZ positions, stored ON the camera (the Tapo app shows the same ones): (id, name) in the camera's order. */
    fun presets(): List<Pair<String, String>> {
        val p = call("getPresetConfig", JSONObject().put("preset", JSONObject().put("name", JSONArray().put("preset"))))
            .optJSONObject("preset")?.optJSONObject("preset") ?: return emptyList()
        val ids = p.optJSONArray("id") ?: return emptyList()
        val names = p.optJSONArray("name")
        return (0 until ids.length()).map { ids.getString(it) to (names?.optString(it) ?: ids.getString(it)) }
    }

    /** Saves where the camera points NOW under [name] (pytapo savePreset; the camera's method name has that typo). */
    fun savePreset(name: String) {
        call("addMotorPostion", JSONObject().put("preset", JSONObject().put("set_preset",
            JSONObject().put("name", name).put("save_ptz", "1"))))
    }

    fun deletePreset(id: String) {
        call("deletePreset", JSONObject().put("preset", JSONObject().put("remove_preset", JSONObject().put("id", JSONArray().put(id)))))
    }

    fun gotoPreset(id: String) {
        call("motorMoveToPreset", JSONObject().put("preset", JSONObject().put("goto_preset", JSONObject().put("id", id))))
    }

    /** basic_info (device_alias, device_model, …), or null if the camera won't let us in. */
    fun info(): JSONObject? = runCatching {
        call("getDeviceInfo", JSONObject().put("device_info", JSONObject().put("name", JSONArray().put("basic_info"))))
            .getJSONObject("device_info").getJSONObject("basic_info")
    }.getOrNull()

    /** H500 hub only: cameras that record to it (device_id, mac, alias). They are NOT in getChildDeviceList. */
    fun hubCameras(): List<JSONObject> {
        val arr = call("getGeneralDeviceList", JSONObject().put("general_camera_manage",
            JSONObject().put("paired_general_device_list", JSONObject())))
            .getJSONObject("general_camera_manage").getJSONArray("paired_general_device_list")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    /** H500 hub only: a camera's recordings between two unix times, newest first. */
    fun clips(devId: String, mac: String, from: Long, to: Long): List<Clip> {
        val r = call("searchVideoWithUTC", JSONObject().put("playback", JSONObject().put("search_video_with_utc", JSONObject()
            .put("channel", 0).put("child_device_id", devId).put("child_device_mac", mac)
            .put("start_time", from).put("end_time", to).put("start_index", 0).put("end_index", 199)
            .put("player_id", TapoStream.PLAYER))))
        // each entry is {"search_video_results_N": {"startTime":…, "endTime":…, …}}; the hub answers {} when there are none
        val arr = r.optJSONObject("playback")?.optJSONArray("search_video_results") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getJSONObject(o.keys().next()) } }
            .map { Clip(devId, mac, it.getLong("startTime"), it.getLong("endTime")) }
            .sortedByDescending { it.start }
    }

    /**
     * H500 hub only: what triggered each recording, as (start time, event codes). Codes follow
     * Sujeom/tapo-h500-local-recordings: events_1 bit N set = code N+1 (2 motion, 6 person, 8 vehicle, 9 pet …).
     */
    fun detections(devId: String, mac: String, from: Long, to: Long): List<Pair<Long, List<Int>>> {
        val r = call("searchDetectionList", JSONObject().put("playback", JSONObject().put("search_detection_list", JSONObject()
            .put("channel", 0).put("child_device_id", devId).put("child_device_mac", mac)
            .put("start_time", from).put("end_time", to).put("start_index", 0).put("end_index", 999))))
        val pb = r.optJSONObject("playback") ?: return emptyList() // a quiet day answers {}
        val arr = pb.keys().asSequence().mapNotNull { pb.optJSONArray(it) }.firstOrNull() ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i).let { o -> if (o.length() == 1) o.optJSONObject(o.keys().next()) ?: o else o }
            val start = o.optString("start_time", o.optString("startTime")).toLongOrNull() ?: 0L
            val mask = o.optString("events_1").toLongOrNull() ?: 0L
            val codes = if (mask > 0) (0 until 64).filter { mask shr it and 1L == 1L }.map { it + 1 }
            else listOfNotNull(o.optString("alarm_type").toIntOrNull()?.takeIf { it > 0 })
            start to codes
        }
    }

    @Synchronized
    fun call(method: String, params: JSONObject): JSONObject {
        repeat(2) { attempt ->
            if (stok == null) login()
            val inner = JSONObject().put("method", "multipleRequest").put("params", JSONObject()
                .put("requests", JSONArray().put(JSONObject().put("method", method).put("params", params))))
            val reply = send(inner)
            val code = reply.optInt("error_code", 0)
            if (code == -40401 || code == -40421) { stok = null; return@repeat } // session expired: log in again
            if (code != 0) throw IOException("Camera $host: error $code")
            val first = reply.getJSONObject("result").getJSONArray("responses").getJSONObject(0)
            if (first.optInt("error_code", 0) != 0) throw IOException("Camera $host: $method error ${first.optInt("error_code")}")
            return first.getJSONObject("result")
        }
        throw IOException("Camera $host: login keeps expiring")
    }

    private fun send(inner: JSONObject): JSONObject {
        val k = lsk
        val v = ivb
        if (k == null || v == null) return post("stok=$stok/ds", inner.toString(), emptyMap()) // legacy firmware
        val outer = JSONObject().put("method", "securePassthrough").put("params",
            JSONObject().put("request", Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, k, v, inner.toString().toByteArray())))).toString()
        val tag = TapoCrypto.sha256u(TapoCrypto.sha256u(hp + cnonce) + outer + seq)
        val headers = mapOf("Seq" to seq.toString(), "Tapo_tag" to tag)
        seq++
        val reply = post("stok=$stok/ds", outer, headers)
        val enc = reply.optJSONObject("result")?.optString("response") ?: return reply
        val text = String(aes(Cipher.DECRYPT_MODE, k, v, Base64.getDecoder().decode(enc)))
        // An old session (hours later) gets an EMPTY answer instead of an error code: treat it as expired so
        // call() logs in again (seen on the H500, 2026-11-12: "End of input at character 0").
        return if (text.isBlank()) JSONObject().put("error_code", -40401) else JSONObject(text)
    }

    private fun login() {
        lsk = null; ivb = null
        cnonce = TapoCrypto.hex(ByteArray(8).also { SecureRandom().nextBytes(it) }).uppercase()
        val first = post("", """{"method":"login","params":{"cnonce":"$cnonce","encrypt_type":"3","username":"admin"}}""", emptyMap())
        val data = first.optJSONObject("result")?.optJSONObject("data")
        val nonce = data?.optString("nonce").orEmpty()
        if (nonce.isEmpty()) {
            if (first.optInt("error_code") == -40211) throw IOException("Camera $host: new-firmware login (TPAP) not supported yet")
            legacyLogin(); return
        }
        val confirm = data!!.optString("device_confirm")
        hp = listOf(creds.sha256u, creds.md5u).firstOrNull { TapoCrypto.sha256u(cnonce + it + nonce) + nonce + cnonce == confirm }
            ?: throw IOException("Camera $host: wrong Tapo password")
        val digest = TapoCrypto.sha256u(hp + cnonce + nonce) + cnonce + nonce
        val r = post("", """{"method":"login","params":{"cnonce":"$cnonce","encrypt_type":"3","digest_passwd":"$digest","username":"admin"}}""", emptyMap())
            .getJSONObject("result")
        val hk = TapoCrypto.sha256u(cnonce + hp + nonce)
        lsk = TapoCrypto.sha256("lsk$cnonce$nonce$hk").copyOf(16)
        ivb = TapoCrypto.sha256("ivb$cnonce$nonce$hk").copyOf(16)
        seq = r.getInt("start_seq")
        stok = r.getString("stok")
    }

    private fun legacyLogin() {
        val r = post("", """{"method":"login","params":{"hashed":true,"password":"${creds.md5u}","username":"admin"}}""", emptyMap())
        stok = r.optJSONObject("result")?.optString("stok")?.takeIf { it.isNotEmpty() }
            ?: throw IOException("Camera $host: login failed (${r.optInt("error_code")})")
    }

    private fun aes(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }.doFinal(data)

    private fun post(path: String, body: String, headers: Map<String, String>): JSONObject {
        val c = URL("https://$host/$path").openConnection() as HttpsURLConnection
        c.sslSocketFactory = trustCamera.socketFactory
        c.setHostnameVerifier { _, _ -> true } // camera on our own LAN, self-signed cert
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        c.setRequestProperty("requestByApp", "true")
        c.setRequestProperty("User-Agent", "Tapo CameraClient Android")
        c.setRequestProperty("Referer", "https://$host")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        val text = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private companion object {
        // Tapo cameras only have self-signed certificates; used for camera IPs found on the LAN only.
        val trustCamera: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), SecureRandom())
        }
    }
}
