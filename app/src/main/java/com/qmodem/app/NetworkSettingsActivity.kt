package com.qmodem.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.qmodem.app.model.ModemSection
import com.qmodem.app.net.LuciClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 网络与拨号设置页：制式偏好 / USB 网络模式 / 拨号控制 / 拨号日志 */
class NetworkSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_MODEM_ID = "modem_id"
        const val EXTRA_MODEM_AT_PORT = "modem_at_port"
    }

    private lateinit var client: LuciClient
    private lateinit var modem: ModemSection
    private lateinit var sw3g: SwitchCompat
    private lateinit var sw4g: SwitchCompat
    private lateinit var sw5g: SwitchCompat
    private lateinit var btnSavePrefer: MaterialButton
    private lateinit var tvUsbModeCur: TextView
    private lateinit var radioModes: RadioGroup
    private lateinit var btnApplyMode: MaterialButton
    private lateinit var tvDialStatus: TextView
    private lateinit var btnDial: MaterialButton
    private lateinit var btnHang: MaterialButton
    private lateinit var btnRedial: MaterialButton
    private lateinit var tvDialLog: TextView
    private lateinit var btnLoadLog: MaterialButton
    private lateinit var btnClearLog: MaterialButton
    private val MODE_LIST = listOf("qmi", "ecm", "mbim", "rndis", "ncm")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_settings)

        val host = intent.getStringExtra(EXTRA_HOST) ?: "http://192.168.1.1"
        val port = intent.getIntExtra(EXTRA_PORT, 80)
        val user = intent.getStringExtra(EXTRA_USER) ?: "root"
        val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
        modem = ModemSection(
            intent.getStringExtra(EXTRA_MODEM_ID) ?: "modem1",
            intent.getStringExtra(EXTRA_MODEM_ID) ?: "modem1",
            intent.getStringExtra(EXTRA_MODEM_AT_PORT)
        )
        client = LuciClient(host, port, user, pass)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        sw3g = findViewById(R.id.sw3g)
        sw4g = findViewById(R.id.sw4g)
        sw5g = findViewById(R.id.sw5g)
        btnSavePrefer = findViewById(R.id.btnSavePrefer)
        tvUsbModeCur = findViewById(R.id.tvUsbModeCur)
        radioModes = findViewById(R.id.radioModes)
        btnApplyMode = findViewById(R.id.btnApplyMode)
        tvDialStatus = findViewById(R.id.tvDialStatus)
        btnDial = findViewById(R.id.btnDial)
        btnHang = findViewById(R.id.btnHang)
        btnRedial = findViewById(R.id.btnRedial)
        tvDialLog = findViewById(R.id.tvDialLog)
        btnLoadLog = findViewById(R.id.btnLoadLog)
        btnClearLog = findViewById(R.id.btnClearLog)

        btnLoadLog.setOnClickListener { loadDialLog() }
        btnClearLog.setOnClickListener {
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.clearDialLog(modem) }
                    tvDialLog.text = ""
                    toast("已清除拨号日志")
                } catch (e: Exception) {
                    toast("清除失败：${e.message}")
                }
            }
        }

        btnSavePrefer.setOnClickListener { saveNetworkPrefer() }
        btnApplyMode.setOnClickListener { applyUsbMode() }
        btnDial.setOnClickListener { dialAction("modem_dial", "拨号命令已发送") }
        btnHang.setOnClickListener { dialAction("modem_hang", "挂断命令已发送") }
        btnRedial.setOnClickListener { dialAction("modem_redial", "重拨命令已发送") }

        loadNetworkPrefer()
        loadUsbMode()
        loadDialStatus()
    }

    // ---------------- 网络制式偏好 ----------------

    private fun loadNetworkPrefer() {
        lifecycleScope.launch {
            try {
                val prefer = withContext(Dispatchers.IO) { client.getNetworkPrefer(modem) }
                if (prefer.isEmpty()) throw LuciClient.FeatureException("模组不支持网络制式查询")
                sw3g.isChecked = prefer["3G"] == true
                sw4g.isChecked = prefer["4G"] == true
                sw5g.isChecked = prefer["5G"] == true
                val hidden = listOf("3G", "4G", "5G").filter { !prefer.containsKey(it) }
                for (h in hidden) {
                    when (h) {
                        "3G" -> sw3g.visibility = View.GONE
                        "5G" -> sw5g.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                sw3g.isEnabled = false; sw4g.isEnabled = false; sw5g.isEnabled = false
                btnSavePrefer.isEnabled = false
                sw3g.text = "读取失败：${e.message}"
            }
        }
    }

    private fun saveNetworkPrefer() {
        val enabled = ArrayList<String>()
        if (sw3g.isVisible && sw3g.isChecked) enabled.add("3G")
        if (sw4g.isVisible && sw4g.isChecked) enabled.add("4G")
        if (sw5g.isVisible && sw5g.isChecked) enabled.add("5G")
        if (enabled.isEmpty()) {
            toast("请至少启用一项网络制式")
            return
        }
        setBusy(btnSavePrefer, true)
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { client.setNetworkPrefer(modem, enabled) }
                toast("已保存：${enabled.joinToString("/")}（模组正在切换，可能短暂掉线）")
            } catch (e: Exception) {
                toast("保存失败：${e.message}")
            } finally {
                setBusy(btnSavePrefer, false)
            }
        }
    }

    // ---------------- USB 网络模式 ----------------

    private fun loadUsbMode() {
        lifecycleScope.launch {
            try {
                val (cur, all) = withContext(Dispatchers.IO) { client.getUsbMode(modem) }
                tvUsbModeCur.text = "当前模式：$cur"
                val modes = all.ifEmpty { MODE_LIST }
                radioModes.removeAllViews()
                for (m in modes) {
                    val rb = RadioButton(this@NetworkSettingsActivity).apply {
                        text = m.uppercase()
                        textSize = 14f
                        isChecked = m == cur
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 6.dp }
                    }
                    radioModes.addView(rb)
                }
            } catch (e: Exception) {
                tvUsbModeCur.text = "读取失败：${e.message}"
                btnApplyMode.isEnabled = false
            }
        }
    }

    private fun applyUsbMode() {
        val rb = findViewById<RadioButton>(radioModes.checkedRadioButtonId) ?: return
        val mode = rb.text.toString().lowercase()
        confirmDialog(
            "切换 USB 网络模式",
            "将 USB 模式切换为 $mode？切换后路由器可能需要重新拨号或重启模组才能恢复网络。"
        ) {
            setBusy(btnApplyMode, true)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { client.setUsbMode(modem, mode) }
                    toast("已设置为 $mode，等待模组生效…")
                    loadUsbMode()
                } catch (e: Exception) {
                    toast("设置失败：${e.message}")
                } finally {
                    setBusy(btnApplyMode, false)
                }
            }
        }
    }

    // ---------------- 拨号控制 ----------------

    private fun loadDialStatus() {
        lifecycleScope.launch {
            try {
                val running = withContext(Dispatchers.IO) { client.dialStatus(modem) }
                tvDialStatus.text = if (running) "● 拨号运行中" else "● 拨号未运行"
                tvDialStatus.setTextColor(
                    ContextCompat.getColor(
                        this@NetworkSettingsActivity,
                        if (running) R.color.excellent else R.color.poor
                    )
                )
            } catch (e: Exception) {
                tvDialStatus.text = "● 状态未知（${e.message}）"
            }
        }
    }

    private fun dialAction(method: String, okMsg: String) {
        lifecycleScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { client.dialAction(modem, method) }
                if (ok) toast(okMsg) else toast("命令执行失败")
                loadDialStatus()
            } catch (e: Exception) {
                toast("执行失败：${e.message}")
            }
        }
    }

    private fun loadDialLog() {
        setBusy(btnLoadLog, true)
        lifecycleScope.launch {
            try {
                val log = withContext(Dispatchers.IO) { client.getDialLog(modem) }
                tvDialLog.text = log.ifEmpty { "（暂无日志）" }
                if (log.isNotEmpty()) toast("已加载 ${log.lines().size} 行日志")
            } catch (e: Exception) {
                tvDialLog.text = "加载失败：${e.message}"
            } finally {
                setBusy(btnLoadLog, false)
            }
        }
    }

    // ---------------- 通用 ----------------

    private val View.isVisible: Boolean get() = visibility == View.VISIBLE

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun setBusy(btn: MaterialButton, busy: Boolean) {
        if (busy && btn.tag == null) btn.tag = btn.text.toString()
        btn.isEnabled = !busy
        btn.text = if (busy) "处理中…" else btn.tag?.toString() ?: btn.text.toString()
    }

    private fun confirmDialog(title: String, msg: String, onOk: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定") { _, _ -> onOk() }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        client.logout()
    }
}
