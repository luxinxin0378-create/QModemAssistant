package com.qmodem.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.qmodem.app.model.SavedDevice
import com.qmodem.app.net.LuciClient
import com.qmodem.app.net.LoginException
import com.qmodem.app.net.NetworkException
import com.qmodem.app.store.DeviceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var deviceDropdown: AutoCompleteTextView
    private lateinit var btnDeleteDevice: ImageButton
    private lateinit var inputHostLayout: TextInputLayout
    private lateinit var inputHost: TextInputEditText
    private lateinit var inputPort: TextInputEditText
    private lateinit var inputUser: TextInputEditText
    private lateinit var inputPass: TextInputEditText
    private lateinit var chkRemember: MaterialCheckBox
    private lateinit var btnConnect: MaterialButton
    private lateinit var progress: CircularProgressIndicator
    private lateinit var tvStatus: TextView

    private var devices: MutableList<SavedDevice> = mutableListOf()
    private var selected: SavedDevice? = null
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        deviceDropdown = findViewById(R.id.deviceDropdown)
        btnDeleteDevice = findViewById(R.id.btnDeleteDevice)
        inputHostLayout = findViewById(R.id.inputHostLayout)
        inputHost = findViewById(R.id.inputHost)
        inputPort = findViewById(R.id.inputPort)
        inputUser = findViewById(R.id.inputUser)
        inputPass = findViewById(R.id.inputPass)
        chkRemember = findViewById(R.id.chkRemember)
        btnConnect = findViewById(R.id.btnConnect)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        devices = DeviceStore.load(this)
        refreshDeviceMenu()

        // 预填最近使用的设备
        DeviceStore.getLast(this)?.let { fillFromDevice(it) }

        inputHost.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // 地址里带端口时同步到端口输入框
                val text = s?.toString()?.trim() ?: return
                val idx = text.lastIndexOf(':')
                if (idx > text.lastIndexOf('/') && idx < text.length - 1) {
                    val p = text.substring(idx + 1).toIntOrNull()
                    if (p != null && p in 1..65535 && inputPort.text.isNullOrBlank()) {
                        inputPort.setText(p.toString())
                    }
                }
            }

            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        deviceDropdown.onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ ->
            val list = devices
            if (pos in list.indices) fillFromDevice(list[pos])
        }

        btnDeleteDevice.setOnClickListener {
            val d = selected ?: return@setOnClickListener
            DeviceStore.delete(this, d.id)
            devices = DeviceStore.load(this)
            selected = null
            refreshDeviceMenu()
            setStatus(getString(R.string.device_deleted), false)
        }

        btnConnect.setOnClickListener { startConnect() }
    }

    private fun fillFromDevice(d: SavedDevice) {
        selected = d
        inputHost.setText(d.host.removePrefix("http://").removePrefix("https://"))
        if (d.port != 80) inputPort.setText(d.port.toString()) else inputPort.setText("")
        inputUser.setText(d.username)
        inputPass.setText(d.password)
        chkRemember.isChecked = d.rememberPassword
        if (d.rememberPassword && d.password.isEmpty()) chkRemember.isChecked = false
    }

    private fun refreshDeviceMenu() {
        val labels = devices.map { "${it.address} · ${it.username}" }
        deviceDropdown.setText("")
        deviceDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        btnDeleteDevice.visibility = if (devices.isEmpty()) View.GONE else View.VISIBLE
        deviceDropdown.isEnabled = devices.isNotEmpty()
    }

    /** 解析地址输入 → (scheme://host, port) */
    fun parseAddress(): Pair<String, Int>? {
        val raw = inputHost.text?.toString()?.trim() ?: ""
        if (raw.isEmpty()) {
            inputHostLayout.error = getString(R.string.host_required)
            return null
        }
        inputHostLayout.error = null
        var s = raw
        val https = s.startsWith("https://", true)
        s = s.removePrefix("https://").removePrefix("http://")
        val portField = inputPort.text?.toString()?.trim()?.toIntOrNull()
        s = s.trimEnd('/').substringBefore('/')
        val (host, urlPort) = if (s.contains(':')) {
            val parts = s.split(':', limit = 2)
            val p = parts[1].toIntOrNull()
            if (parts[0].isEmpty() || p == null || p !in 1..65535) return null
            parts[0] to p
        } else {
            s to (portField ?: if (https) 443 else 80)
        }
        if (host.isEmpty()) {
            inputHostLayout.error = getString(R.string.host_invalid)
            return null
        }
        return Pair(if (https) "https://$host" else "http://$host", urlPort)
    }

    private fun startConnect() {
        if (connecting) return
        val parsed = parseAddress() ?: run {
            setStatus(getString(R.string.host_invalid), true)
            return
        }
        val (host, port) = parsed
        val user = inputUser.text?.toString()?.trim()?.ifEmpty { "root" } ?: "root"
        val pass = inputPass.text?.toString() ?: ""
        val remember = chkRemember.isChecked
        if (remember && pass.isEmpty()) {
            setStatus(getString(R.string.password_required_when_remember), true)
            return
        }

        connecting = true
        btnConnect.isEnabled = false
        progress.visibility = View.VISIBLE
        setStatus(getString(R.string.connecting), false)

        lifecycleScope.launch {
            try {
                val client = LuciClient(host, port, user, pass)
                val modems = withContext(Dispatchers.IO) { client.connect() }
                // 保存设备
                DeviceStore.upsert(this@MainActivity, host, port, user, pass, remember)
                val dev = DeviceStore.load(this@MainActivity)
                    .firstOrNull { it.host == host && it.port == port && it.username == user }
                setStatus(getString(R.string.connect_ok, modems.size), false)
                val intent = Intent(this@MainActivity, DashboardActivity::class.java).apply {
                    putExtra(DashboardActivity.EXTRA_HOST, host)
                    putExtra(DashboardActivity.EXTRA_PORT, port)
                    putExtra(DashboardActivity.EXTRA_USER, user)
                    putExtra(DashboardActivity.EXTRA_PASS, pass)
                    dev?.id?.let { putExtra(DashboardActivity.EXTRA_DEVICE_ID, it) }
                    putExtra(DashboardActivity.EXTRA_MODEMS, ArrayList(modems))
                }
                startActivity(intent)
                progress.visibility = View.GONE
                btnConnect.isEnabled = true
                connecting = false
            } catch (e: Exception) {
                progress.visibility = View.GONE
                btnConnect.isEnabled = true
                connecting = false
                val msg = when (e) {
                    is LoginException -> e.message ?: "登录失败"
                    is NetworkException -> e.message ?: "网络错误"
                    else -> "连接失败：${e.message}"
                }
                setStatus(msg, true)
            }
        }
    }

    private fun setStatus(text: String, isError: Boolean) {
        tvStatus.text = text
        tvStatus.visibility = View.VISIBLE
        tvStatus.setTextColor(
            if (isError) getColor(R.color.danger) else getColor(R.color.neutral_text)
        )
    }

    override fun onResume() {
        super.onResume()
        // 从仪表盘返回后刷新设备列表
        devices = DeviceStore.load(this)
        refreshDeviceMenu()
    }
}
