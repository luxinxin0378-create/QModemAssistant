package com.qmodem.app

import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.qmodem.app.model.ModemSection
import com.qmodem.app.net.LuciClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AT 命令调试页 */
class AtDebugActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_MODEM_ID = "modem_id"
        const val EXTRA_MODEM_AT_PORT = "modem_at_port"

        /** 常用 AT 命令预设 */
        val PRESETS = listOf(
            "ATI",
            "AT+CPIN?",
            "AT+CSQ",
            "AT+COPS?",
            "AT+QENG=\"servingcell\"",
            "AT+QENG=\"neighbourcell\"",
            "AT+CGATT?",
            "AT+CGREG?",
            "AT+CEREG?",
            "AT+CGDCONT?",
            "AT+CGSN",
            "AT+CIMI",
            "AT+QCCID",
            "AT+QNWPREFCFG=\"policy\"",
            "AT+QNWPREFCFG=\"lte_band\"",
            "AT+QNWPREFCFG=\"nsa_band\"",
            "AT+QNWPREFCFG=\"sa_band\"",
            "AT+QCFG=\"usbnet\"",
            "AT+QTEMP?",
            "AT+CFUN=1,1"
        )
    }

    private lateinit var client: LuciClient
    private lateinit var modem: ModemSection
    private lateinit var portInput: AutoCompleteTextView
    private lateinit var cmdInput: TextInputEditText
    private lateinit var btnSend: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var presetBar: LinearLayout
    private lateinit var logView: TextView
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_at_debug)

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

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        portInput = findViewById(R.id.portInput)
        cmdInput = findViewById(R.id.cmdInput)
        btnSend = findViewById(R.id.btnSend)
        btnClear = findViewById(R.id.btnClear)
        presetBar = findViewById(R.id.presetBar)
        logView = findViewById(R.id.logView)

        logView.typeface = Typeface.MONOSPACE

        // 预设命令芯片
        for (cmd in PRESETS) {
            val chip = MaterialButton(this).apply {
                text = cmd
                textSize = 12f
                isAllCaps = false
                insetTop = 0
                insetBottom = 0
                setPadding(20.dp, 8.dp, 20.dp, 8.dp)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 8.dp }
            }
            chip.setOnClickListener {
                // 带赋值参数的命令（如 AT+CFUN=1,1、锁频段设置）不直接执行，只填入输入框确认
                if (cmd.contains("=") && !cmd.endsWith("?")) {
                    cmdInput.setText(cmd)
                    cmdInput.requestFocus()
                    toast("已填入输入框，请确认后发送")
                } else {
                    cmdInput.setText(cmd)
                    sendAt()
                }
            }
            presetBar.addView(chip)
        }

        cmdInput.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                btnSend.isEnabled = !s.isNullOrBlank()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {}
        })
        btnSend.isEnabled = false
        btnSend.setOnClickListener { sendAt() }
        btnClear.setOnClickListener {
            logView.text = ""
        }
        cmdInput.setOnEditorActionListener { _, _, _ ->
            if (btnSend.isEnabled) sendAt()
            true
        }

        loadPorts()
        appendLog("提示：发送 AT 命令会直接作用于模组，设置类命令请谨慎操作。")
    }

    private fun loadPorts() {
        lifecycleScope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) { client.getAtConfig(modem) }
                val list = cfg.ports.ifEmpty { listOf("/dev/ttyUSB2") }
                val adapter = ArrayAdapter(this@AtDebugActivity, android.R.layout.simple_list_item_1, list)
                portInput.setAdapter(adapter)
                portInput.setText(cfg.defaultPort?.takeIf { it in list } ?: list.first(), false)
                appendLog("已加载 AT 端口：${list.joinToString()}")
            } catch (e: Exception) {
                appendLog("加载 AT 端口失败：${e.message}")
                val list = listOf("/dev/ttyUSB1", "/dev/ttyUSB2", "/dev/ttyUSB3")
                portInput.setAdapter(ArrayAdapter(this@AtDebugActivity, android.R.layout.simple_list_item_1, list))
                portInput.setText(list.first(), false)
            }
        }
    }

    private fun sendAt() {
        val cmd = cmdInput.text?.toString()?.trim() ?: return
        if (cmd.isEmpty()) return
        val port = portInput.text?.toString()?.trim() ?: return
        btnSend.isEnabled = false
        appendLog("> $cmd")
        lifecycleScope.launch {
            try {
                val res = withContext(Dispatchers.IO) { client.sendAt(modem, cmd, port) }
                appendLog(if (res.isEmpty()) "(空响应)" else res)
            } catch (e: Exception) {
                appendLog("✗ ${e.message}")
            } finally {
                btnSend.isEnabled = true
            }
        }
    }

    private fun appendLog(msg: String) {
        logView.append("[${timeFmt.format(Date())}] $msg\n\n")
        (logView.parent as? androidx.core.widget.NestedScrollView)?.post {
            (logView.parent as? androidx.core.widget.NestedScrollView)?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        client.logout()
    }
}
