package com.neoroot.ksuonetap.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.neoroot.ksuonetap.R
import com.neoroot.ksuonetap.core.Ansi
import com.neoroot.ksuonetap.core.Channel
import com.neoroot.ksuonetap.core.Prefs
import com.neoroot.ksuonetap.core.ShizukuBridge
import com.neoroot.ksuonetap.core.Theme
import com.neoroot.ksuonetap.deploy.Terminal

/**
 * 内置终端。
 *
 * 三条执行通道 (见 [Terminal]):
 *   Shizuku shell —— 任何时候都可用 (uid 2000)
 *   临时 root    —— 只提取了 root、没部署 KSU 时用 (exploit 内置 su)
 *   KernelSU     —— 已部署 KSU 时用 (su_ksu)
 * 「自动」会按 KSU -> 临时 root -> shell 的顺序挑一个能用的。
 */
class TerminalActivity : Activity() {

    private lateinit var termScroll: ScrollView
    private lateinit var tvTerm: TextView
    private lateinit var etCmd: EditText
    private lateinit var btnRun: Button
    private lateinit var spChannel: Spinner
    private lateinit var tvStatus: TextView

    private val buf = StringBuilder()
    private val history = ArrayList<String>()
    private var histIndex = 0
    private var running = false
    private var tail: TailFollower? = null

    /** 输出上限, 防止长时间使用把内存吃满。 */
    private val maxChars = 200_000

    override fun onCreate(savedInstanceState: Bundle?) {
        Theme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        // 底部 dock 也要让出导航栏高度 (终端页同样有 dock)
        Insets.apply(this, listOf(findViewById(R.id.topBar)), findViewById(R.id.dock))

        termScroll = findViewById(R.id.termScroll)
        tvTerm = findViewById(R.id.tvTerm)
        etCmd = findViewById(R.id.etCmd)
        btnRun = findViewById(R.id.btnRun)
        spChannel = findViewById(R.id.spChannel)
        tvStatus = findViewById(R.id.tvTermStatus)

        tail = TailFollower(termScroll, findViewById(R.id.btnJumpLatestTerm))

        findViewById<TextView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.btnClearTerm).setOnClickListener { clearScreen() }
        findViewById<TextView>(R.id.btnCopyTerm).setOnClickListener { copyAll() }

        // 通道下拉: 位置 <-> Channel.all()
        val channels = Channel.all()
        spChannel.setSelection(channels.indexOf(Prefs.channel(this)).coerceAtLeast(0))
        spChannel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                Prefs.setChannel(this@TerminalActivity, channels[pos])
                refreshStatus()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        btnRun.setOnClickListener { runCmd(etCmd.text.toString()) }
        etCmd.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                runCmd(etCmd.text.toString()); true
            } else false
        }

        findViewById<TextView>(R.id.btnPrev).setOnClickListener { hist(-1) }
        findViewById<TextView>(R.id.btnNext).setOnClickListener { hist(+1) }

        buildQuickRow()
        // 终端不属于 dock 四项, 四项都不高亮; 但点 tab 要能切回容器的对应页
        Dock.dim(this)
        Dock.attachSecondary(this)
        refreshStatus()
    }

    // ---------------- 输出 ----------------

    private fun append(s: String) {
        buf.append(s)
        if (buf.length > maxChars) {
            // 丢掉最早的一半并留提示, 避免长时间使用把内存吃光
            buf.delete(0, buf.length - maxChars / 2)
            buf.insert(0, getString(R.string.term_truncated) + "\n")
        }
        val y = tail?.beforeChange() ?: 0
        tvTerm.text = Ansi.render(buf.toString(), tvTerm.currentTextColor)
        tail?.afterChange(y)
    }

    private fun clearScreen() {
        buf.setLength(0)
        tvTerm.text = getString(R.string.term_empty)
        tail?.toTail()
    }

    private fun copyAll() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KSUOneTap terminal", buf.toString()))
        Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }

    // ---------------- 执行 ----------------

    private fun runCmd(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty() || running) return
        history.add(cmd)
        histIndex = history.size
        etCmd.setText("")

        append("\n\u276F $cmd\n")
        setRunning(true)

        Thread {
            val res = try {
                Terminal.exec(cmd, Prefs.channel(this), 180_000)
            } catch (t: Throwable) {
                Terminal.Result("?", "!! $t", null)
            }
            runOnUiThread {
                val body = res.output.ifBlank { "(无输出)" }
                append(if (body.endsWith("\n")) body else body + "\n")
                append("[rc=${res.rc ?: "?"}] · ${Channel.describe(res.channel)}\n")
                setRunning(false)
                refreshStatus()
            }
        }.start()
    }

    private fun setRunning(v: Boolean) {
        running = v
        btnRun.isEnabled = !v
        btnRun.text = getString(if (v) R.string.term_running else R.string.term_run)
        btnRun.alpha = if (v) 0.5f else 1f
    }

    private fun hist(step: Int) {
        if (history.isEmpty()) return
        histIndex = (histIndex + step).coerceIn(0, history.size)
        etCmd.setText(if (histIndex < history.size) history[histIndex] else "")
        etCmd.setSelection(etCmd.text?.length ?: 0)
    }

    private fun buildQuickRow() {
        val row = findViewById<LinearLayout>(R.id.quickRow)
        val cmds = resources.getStringArray(R.array.term_quick_commands)
        val d = resources.displayMetrics.density
        for (c in cmds) {
            val tv = TextView(this)
            tv.text = c
            tv.setTextAppearance(R.style.Text_CardAction)
            tv.background = getDrawable(R.drawable.bg_input)
            // setTextAppearance 只吃 text* 属性, padding 要自己给
            tv.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            tv.setOnClickListener { runCmd(c) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            row.addView(tv, lp)
        }
    }

    /** 通道可用性探测有点慢 (要起进程), 所以放后台。 */
    private fun refreshStatus() {
        tvStatus.text = getString(R.string.state_checking)
        val ch = Prefs.channel(this)
        Thread {
            val line = if (!ShizukuBridge.ping()) null else Terminal.statusLine(ch)
            runOnUiThread {
                tvStatus.text = line ?: getString(R.string.term_need_shizuku)
            }
        }.start()
    }
}
