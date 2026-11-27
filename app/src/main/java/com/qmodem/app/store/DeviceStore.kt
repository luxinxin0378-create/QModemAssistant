package com.qmodem.app.store

import android.content.Context
import com.qmodem.app.model.SavedDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已保存设备列表（SharedPreferences + JSON）。
 * 密码以 Base64 简单混淆存储（本机保存，供“记住密码”使用）。
 */
object DeviceStore {

    private const val PREF = "qmodem_devices"
    private const val KEY_DEVICES = "devices_json"
    private const val KEY_LAST = "last_device_id"

    @Synchronized
    fun load(context: Context): MutableList<SavedDevice> {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_DEVICES, null) ?: return mutableListOf()
        val out = mutableListOf<SavedDevice>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SavedDevice(
                        id = o.optLong("id"),
                        host = o.optString("host"),
                        port = o.optInt("port", 80),
                        username = o.optString("username", "root"),
                        password = decode(o.optString("password", "")),
                        rememberPassword = o.optBoolean("remember", false),
                        label = o.optString("label", "")
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    @Synchronized
    fun save(context: Context, devices: List<SavedDevice>) {
        val arr = JSONArray()
        for (d in devices) {
            arr.put(
                JSONObject()
                    .put("id", d.id)
                    .put("host", d.host)
                    .put("port", d.port)
                    .put("username", d.username)
                    .put("password", encode(d.password))
                    .put("remember", d.rememberPassword)
                    .put("label", d.label)
            )
        }
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_DEVICES, arr.toString()).apply()
    }

    /** 按主机+端口+用户名去重后保存/更新，并记为最近使用 */
    @Synchronized
    fun upsert(
        context: Context,
        host: String,
        port: Int,
        username: String,
        password: String,
        rememberPassword: Boolean
    ): SavedDevice {
        val devices = load(context)
        val exist = devices.firstOrNull {
            it.host == host && it.port == port && it.username == username
        }
        val id: Long
        if (exist != null) {
            id = exist.id
            val updated = exist.copy(
                password = if (rememberPassword) password else "",
                rememberPassword = rememberPassword
            )
            devices[devices.indexOf(exist)] = updated
        } else {
            id = nextId(devices)
            devices.add(
                SavedDevice(
                    id = id, host = host, port = port, username = username,
                    password = if (rememberPassword) password else "",
                    rememberPassword = rememberPassword
                )
            )
        }
        save(context, devices)
        setLast(context, id)
        return devices.first { it.id == id }
    }

    @Synchronized
    fun delete(context: Context, id: Long) {
        val devices = load(context).filter { it.id != id }
        save(context, devices)
        if (getLast(context)?.id == id) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_LAST).apply()
        }
    }

    fun setLast(context: Context, id: Long) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST, id).apply()
    }

    fun getLast(context: Context): SavedDevice? {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_LAST)) return null
        val id = sp.getLong(KEY_LAST, -1)
        return load(context).firstOrNull { it.id == id }
    }

    private fun nextId(devices: List<SavedDevice>): Long =
        (devices.maxOfOrNull { it.id } ?: 0L) + 1L

    private fun encode(s: String): String =
        if (s.isEmpty()) "" else android.util.Base64.encodeToString(s.toByteArray(), android.util.Base64.NO_WRAP)

    private fun decode(s: String): String =
        if (s.isEmpty()) "" else try {
            String(android.util.Base64.decode(s, android.util.Base64.NO_WRAP))
        } catch (_: Exception) {
            ""
        }
}
