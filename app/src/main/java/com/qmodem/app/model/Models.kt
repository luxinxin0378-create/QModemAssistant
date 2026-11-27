package com.qmodem.app.model

import java.io.Serializable

/** 已保存的路由器设备 */
data class SavedDevice(
    val id: Long,
    val host: String,           // 形如 http://192.168.1.1 （含协议，不含端口）
    val port: Int,
    val username: String,
    val password: String,       // 未记住密码时为空串
    val rememberPassword: Boolean,
    val label: String = ""
) {
    val address: String
        get() = if (port == 80 || (host.startsWith("https") && port == 443)) host else "$host:$port"
}

/** 一个模组配置节 */
data class ModemSection(
    val id: String,             // config_section，如 modem1
    val name: String,           // 展示名
    val atPort: String? = null
) : Serializable

/** modem_info 数组中的一条信息 */
data class InfoEntry(
    val key: String,
    val value: String,
    val fullName: String,
    val type: String,           // plain_text / progress_bar / warning_message
    val minValue: Double? = null,
    val maxValue: Double? = null,
    val unit: String? = null,
    val group: String = "",     // 原始 class，如 Cell Information
    val extraInfo: String? = null
) {
    val numValue: Double? get() = value.toDoubleOrNull()
}

/** 信号质量等级 */
enum class SignalQuality(val label: String, val color: Int) {
    EXCELLENT("极佳", 0xFF10B981.toInt()),
    GOOD("良好", 0xFF84CC16.toInt()),
    FAIR("一般", 0xFFF59E0B.toInt()),
    POOR("较差", 0xFFEF4444.toInt()),
    UNKNOWN("无数据", 0xFF94A3B8.toInt())
}
