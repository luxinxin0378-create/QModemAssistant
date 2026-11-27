package com.qmodem.app

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.qmodem.app.model.InfoEntry
import com.qmodem.app.model.ModemSection
import com.qmodem.app.model.SignalQuality
import com.qmodem.app.net.InfoAnalyzer
import com.qmodem.app.net.LuciClient
import com.qmodem.app.ui.GaugeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_MODEMS = "modems"
    }

    private lateinit var client: LuciClient
    private lateinit var toolbar: MaterialToolbar
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var btnModem: MaterialButton
    private lateinit var gauge: GaugeView
    private lateinit var tvRsrp: TextView
    private lateinit var tvQuality: TextView
    private lateinit var badgeMode: TextView
    private lateinit var badgeOperator: TextView
    private lateinit var badgeBand: TextView
    private lateinit var badgeTemp: TextView
    private lateinit var signalBarsBox: LinearLayout
    private lateinit var groupContainer: LinearLayout
    private lateinit var tvUpdated: TextView
    private lateinit var tvBanner: TextView
    private lateinit var autoSwitch: SwitchCompat

    private var modems: List<ModemSection> = emptyList()
    private var currentModem: ModemSection? = null
    private var autoRefresh = true
    private var loading = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 功能页所需的连接参数
    private var clientHost = ""
    private var clientPort = 80
    private var clientUser = "root"
    private var clientPass = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        val host = intent.getStringExtra(EXTRA_HOST) ?: "http://192.168.1.1"
        val port = intent.getIntExtra(EXTRA_PORT, 80)
        val user = intent.getStringExtra(EXTRA_USER) ?: "root"
        val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
        clientHost = host; clientPort = port; clientUser = user; clientPass = pass

        client = LuciClient(host, port, user, pass)

        toolbar = findViewById(R.id.toolbar)
        swipe = findViewById(R.id.swipe)
        btnModem = findViewById(R.id.btnModem)
        gauge = findViewById(R.id.gauge)
        tvRsrp = findViewById(R.id.tvRsrp)
        tvQuality = findViewById(R.id.tvQuality)
        badgeMode = findViewById(R.id.badgeMode)
        badgeOperator = findViewById(R.id.badgeOperator)
        badgeBand = findViewById(R.id.badgeBand)
        badgeTemp = findViewById(R.id.badgeTemp)
        signalBarsBox = findViewById(R.id.signalBarsBox)
        groupContainer = findViewById(R.id.groupContainer)
        tvUpdated = findViewById(R.id.tvUpdated)
        tvBanner = findViewById(R.id.tvBanner)

        // 纯 Toolbar 模式：返回键 + 菜单（手动刷新 / 自动刷新开关）
        toolbar.setNavigationOnClickListener { finish() }
        // 左上角功能菜单：收纳 qmodem 设置/调试功能，点开展开
        val toolsBtn = androidx.appcompat.widget.AppCompatImageButton(this).apply {
            setImageResource(R.drawable.ic_tools)
            setBackgroundResource(android.R.color.transparent)
            contentDescription = "功能菜单"
            setOnClickListener { showToolsMenu(it) }
        }
        toolbar.addView(
            toolsBtn,
            androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL; leftMargin = 8.dp() }
        )
        toolbar.inflateMenu(R.menu.menu_dashboard)
        val autoItem = toolbar.menu.findItem(R.id.action_auto_refresh)
        val switchView = autoItem.actionView
        autoSwitch = switchView?.findViewById<SwitchCompat>(R.id.switchAuto) ?: SwitchCompat(this).apply {
            isChecked = autoRefresh
        }
        autoSwitch.isChecked = autoRefresh
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            autoRefresh = checked
        }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    loadInfo()
                    true
                }
                else -> false
            }
        }

        swipe.setColorSchemeColors(ContextCompat.getColor(this, R.color.primary))
        swipe.setOnRefreshListener { loadInfo() }

        btnModem.setOnClickListener { showModemMenu() }

        @Suppress("UNCHECKED_CAST")
        val extraList = intent.getSerializableExtra(EXTRA_MODEMS) as? List<ModemSection>
        modems = extraList?.filterIsInstance<ModemSection>()?.toMutableList() ?: mutableListOf()
        if (modems.isEmpty()) {
            modems = mutableListOf(ModemSection("modem1", "modem1"))
        }
        selectModem(modems.first())
        setupModemLabel()

        startRefreshLoop()
        loadInfo()
    }

    private fun setupModemLabel() {
        val text = currentModem?.let { getString(R.string.modem_prefix, it.name) } ?: ""
        btnModem.text = text
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------------
    // 左上角功能菜单
    // ------------------------------------------------------------------

    private fun showToolsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, 1, 0, "🛠 AT 命令调试")
            add(0, 2, 1, "📶 网络与拨号设置")
            add(0, 3, 2, "🔄 重启模组")
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openAtDebug()
                2 -> openNetworkSettings()
                3 -> confirmReboot()
            }
            true
        }
        popup.show()
    }

    private fun toolIntent(cls: Class<*>) = android.content.Intent(this, cls).apply {
        putExtra("host", clientHost)
        putExtra("port", clientPort)
        putExtra("user", clientUser)
        putExtra("pass", clientPass)
        currentModem?.let {
            putExtra("modem_id", it.id)
            it.atPort?.let { p -> putExtra("modem_at_port", p) }
        }
    }

    private fun openAtDebug() {
        startActivity(toolIntent(AtDebugActivity::class.java))
    }

    private fun openNetworkSettings() {
        startActivity(toolIntent(NetworkSettingsActivity::class.java))
    }

    private fun confirmReboot() {
        val modem = currentModem ?: return
        lifecycleScope.launch {
            val caps = try {
                withContext(Dispatchers.IO) { client.getRebootCaps(modem) }
            } catch (e: Exception) {
                true to false
            }
            val msg = StringBuilder("将重启模组 ${modem.name}，重启期间会断网，确定继续？")
            if (caps.second) {
                msg.append("\n\n软重启：AT 指令重启模组（推荐）\n硬重启：断电重启（仅支持时可用）")
            }
            val dlg = androidx.appcompat.app.AlertDialog.Builder(this@DashboardActivity)
                .setTitle("重启模组")
                .setMessage(msg)
                .setPositiveButton(if (caps.second) "软重启" else "重启") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.doReboot(modem, "soft") }
                            toast("已发送软重启命令，等待模组恢复…")
                        } catch (e: Exception) {
                            toast("重启失败：${e.message}")
                        }
                    }
                }
                .setNegativeButton("取消", null)
            if (caps.second) {
                dlg.setNeutralButton("硬重启") { _, _ ->
                    lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) { client.doReboot(modem, "hard") }
                            toast("已发送硬重启命令，等待模组恢复…")
                        } catch (e: Exception) {
                            toast("重启失败：${e.message}")
                        }
                    }
                }
            }
            dlg.show()
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showModemMenu() {
        if (modems.size <= 1) return
        val popup = PopupMenu(this, btnModem)
        modems.forEachIndexed { i, m ->
            popup.menu.add(0, i, i, m.name)
        }
        popup.setOnMenuItemClickListener { item ->
            modems.getOrNull(item.itemId)?.let { selectModem(it); loadInfo() }
            true
        }
        popup.show()
    }

    private fun selectModem(m: ModemSection) {
        currentModem = m
        setupModemLabel()
    }

    // ------------------------------------------------------------------
    // 数据加载
    // ------------------------------------------------------------------

    private fun startRefreshLoop() {
        lifecycleScope.launch {
            while (isActive) {
                delay(10_000)
                if (autoRefresh && !loading && currentModem != null) loadInfo()
            }
        }
    }

    private fun loadInfo() {
        val modem = currentModem ?: return
        if (loading) return
        loading = true
        tvBanner.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) { client.fetchInfo(modem) }
                render(entries)
            } catch (e: Exception) {
                showBanner(e.message ?: getString(R.string.load_failed))
            } finally {
                loading = false
                swipe.isRefreshing = false
            }
        }
    }

    private fun showBanner(msg: String) {
        tvBanner.text = msg
        tvBanner.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    private fun render(entries: List<InfoEntry>) {
        // 主信号
        val primary = InfoAnalyzer.primarySignal(entries)
        val dp = resources.displayMetrics.density
        if (primary != null) {
            val min = primary.minValue ?: -140.0
            val max = primary.maxValue ?: -44.0
            val q = InfoAnalyzer.quality(primary)
            gauge.setValue(min, max, primary.numValue, q.color)
            tvRsrp.text = InfoAnalyzer.displayValue(primary)
            tvQuality.text = getString(R.string.signal_quality_fmt, q.label)
            tvQuality.setTextColor(q.color)
        } else {
            gauge.setValue(-140.0, -44.0, null, 0xFF94A3B8.toInt())
            tvRsrp.text = "N/A"
            tvQuality.text = getString(R.string.signal_quality_fmt, SignalQuality.UNKNOWN.label)
            tvQuality.setTextColor(ContextCompat.getColor(this, R.color.neutral_text))
        }

        // 徽标
        val mode = InfoAnalyzer.networkMode(entries)
        val operator = InfoAnalyzer.operator(entries)
        val band = InfoAnalyzer.bandBadge(entries)
        val temp = InfoAnalyzer.temperature(entries)
        bindBadge(badgeMode, mode)
        bindBadge(badgeOperator, operator)
        bindBadge(badgeBand, band?.let { getString(R.string.band_fmt, it) })
        bindBadge(badgeTemp, temp?.let { getString(R.string.temp_fmt, it) })

        // 信号条
        signalBarsBox.removeAllViews()
        val bars = InfoAnalyzer.signalBars(entries)
        for (e in bars) addSignalBarRow(signalBarsBox, e, dp)

        // 分组卡片
        groupContainer.removeAllViews()
        val order = listOf("Base Information", "SIM Information", "Cell Information", "Network Information")
        val groups = entries.groupBy { it.group }
        val ordered = (order.filter { groups.containsKey(it) } + groups.keys.filter { it !in order })
        for (g in ordered) {
            val list = groups[g] ?: continue
            groupContainer.addView(buildGroupCard(InfoAnalyzer.groupLabel(g), list, dp, entries))
        }

        tvUpdated.text = getString(R.string.updated_at, timeFmt.format(Date()))
    }

    private fun bindBadge(badge: TextView, text: String?) {
        if (text.isNullOrBlank()) {
            badge.visibility = View.GONE
        } else {
            badge.visibility = View.VISIBLE
            badge.text = text
        }
    }

    private fun addSignalBarRow(parent: LinearLayout, e: InfoEntry, dp: Float) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(this).apply {
            text = InfoAnalyzer.entryLabel(e)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.neutral_text))
        }
        val value = TextView(this).apply {
            text = InfoAnalyzer.displayValue(e)
            textSize = 13f
            gravity = Gravity.END
            setTypeface(typeface, Typeface.BOLD)
        }
        top.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val bar = LinearProgressIndicator(this).apply {
            val minV = e.minValue ?: 0.0
            val maxV = e.maxValue ?: 100.0
            val spanV = if (maxV > minV) maxV - minV else 100.0
            max = spanV.toInt()
            e.numValue?.let { progress = ((it - minV).coerceIn(0.0, spanV)).toInt() }
            trackCornerRadius = (4 * dp).toInt()
            trackThickness = (8 * dp).toInt()
            setIndicatorColor(InfoAnalyzer.quality(e).color)
        }
        row.addView(top)
        row.addView(
            bar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (6 * dp).toInt() }
        )
        parent.addView(row)
    }

    private fun buildGroupCard(title: String, entries: List<InfoEntry>, dp: Float, all: List<InfoEntry>): MaterialCardView {
        val card = MaterialCardView(this).apply {
            radius = 16 * dp
            cardElevation = 0f
            strokeWidth = 1
            strokeColor = ContextCompat.getColor(context, R.color.card_stroke)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.card_bg))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        }
        box.addView(
            TextView(this).apply {
                text = title
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.primary))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = (8 * dp).toInt() }
        )

        for (e in entries) {
            when (e.type) {
                "progress_bar" -> {
                    box.addView(buildPlainRow(e, dp, all))
                    box.addView(
                        LinearProgressIndicator(this).apply {
                            val minV = e.minValue ?: 0.0
                            val maxV = e.maxValue ?: 100.0
                            val spanV = if (maxV > minV) maxV - minV else 100.0
                            max = spanV.toInt()
                            e.numValue?.let { progress = ((it - minV).coerceIn(0.0, spanV)).toInt() }
                            trackCornerRadius = (4 * dp).toInt()
                            trackThickness = (7 * dp).toInt()
                            setIndicatorColor(ContextCompat.getColor(context, R.color.primary))
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { bottomMargin = (10 * dp).toInt() }
                    )
                }
                "warning_message" -> {
                    box.addView(
                        TextView(this).apply {
                            text = getString(R.string.warning_fmt, e.value)
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(context, R.color.warning))
                        },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .apply { bottomMargin = (10 * dp).toInt() }
                    )
                }
                else -> {
                    box.addView(buildPlainRow(e, dp, all))
                }
            }
        }
        card.addView(box)
        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = (12 * dp).toInt() }
        card.layoutParams = cardParams
        return card
    }

    private fun buildPlainRow(e: InfoEntry, dp: Float, all: List<InfoEntry> = emptyList()): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val label = TextView(this).apply {
            text = InfoAnalyzer.entryLabel(e)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.neutral_text))
        }
        val value = TextView(this).apply {
            text = InfoAnalyzer.annotateValue(e, all)
            textSize = 13f
            gravity = Gravity.END
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 2
        }
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = (8 * dp).toInt()
        })
        row.addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            (layoutParams as? LinearLayout.LayoutParams)?.bottomMargin = (10 * dp).toInt()
        }.also { outer ->
            outer.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * dp).toInt() }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onDestroy() {
        super.onDestroy()
        client.logout()
    }
}
