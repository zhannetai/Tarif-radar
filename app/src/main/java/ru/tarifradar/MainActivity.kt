package ru.tarifradar

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

class MainActivity : Activity() {

    private val palette = intArrayOf(
        0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF43A047.toInt(), 0xFFFB8C00.toInt(),
        0xFF8E24AA.toInt(), 0xFF00ACC1.toInt(), 0xFF6D4C41.toInt(), 0xFF3949AB.toInt()
    )

    private lateinit var prefs: Prefs
    private lateinit var db: Db
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvService: TextView
    private lateinit var btnService: Button
    private lateinit var btnMonitor: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvLast: TextView
    private lateinit var blocksBox: LinearLayout
    private lateinit var etThr: EditText
    private lateinit var etInt: EditText
    private lateinit var etEntry: EditText
    private lateinit var cbSound: CheckBox
    private lateinit var cbPeek: CheckBox
    private lateinit var etIdle: EditText
    private lateinit var chartRecent: ChartView
    private lateinit var chartHourly: ChartView
    private lateinit var chartFreq: ChartView
    private lateinit var tvFreqTitle: TextView
    private lateinit var tvBest: TextView
    private val periodButtons = ArrayList<Button>()

    private var recentHours = 24
    private var shownBlocks: List<String> = emptyList()

    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 5000)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        db = Db.get(this)
        AlarmPlayer.ensureChannels(this)
        setContentView(buildUi())
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        AlarmPlayer.stop(this)
        val f = IntentFilter(TariffService.ACTION_UPDATED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
        handler.post(refresher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresher)
        try { unregisterReceiver(receiver) } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun header(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setTypeface(null, Typeface.BOLD)
        setTextColor(Color.BLACK)
        setPadding(0, dp(18), 0, dp(6))
    }

    private fun label(text: String = "", size: Float = 14f) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFF333333.toInt())
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (v in views) addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun field(title: String, value: String, type: Int): Pair<View, EditText> {
        val et = EditText(this).apply { setText(value); inputType = type; setSingleLine() }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
            addView(et, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return box to et
    }

    private fun buildUi(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(32))
        }

        // --- Служба
        col.addView(header("1. Чтение экрана"))
        tvService = label()
        col.addView(tvService)
        btnService = button("Открыть настройки «Спец. возможности»") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        col.addView(btnService)

        // --- Слежка
        col.addView(header("2. Слежка"))
        col.addView(label("Откройте в WB Job экран «Тарифы операций» (фильтр «Операция» — сборка) и поставьте телефон на зарядку. Экран не будет гаснуть, пока идёт слежка.", 13f))
        btnMonitor = button("") { toggleMonitoring() }
        col.addView(btnMonitor)
        col.addView(button("🌙 Ночная слежка (чёрный экран)") { startNight() })
        col.addView(label("Ночью: откройте WB Job на «Тарифы операций», поставьте на зарядку. Экран станет чёрным, но не погаснет — слежка идёт. Когда сработает будильник, чёрный экран уберётся. Тап по тусклой плашке — стоп.", 12f))
        tvStatus = label(size = 13f)
        col.addView(tvStatus)

        // --- Последний замер
        col.addView(header("Последний замер"))
        tvLast = label()
        col.addView(tvLast)

        // --- Настройки
        col.addView(header("Настройки"))
        val (fThr, eThr) = field("Порог, ₽ (звонить при нём и выше)", Fmt.price(prefs.threshold).replace(" ₽", ""),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        etThr = eThr; col.addView(fThr)
        val (fInt, eInt) = field("Обновлять каждые, мин", prefs.intervalMin.toString(), InputType.TYPE_CLASS_NUMBER)
        etInt = eInt; col.addView(fInt)
        val (fEntry, eEntry) = field("Текст кнопки входа в тарифы", prefs.entryText, InputType.TYPE_CLASS_TEXT)
        etEntry = eEntry; col.addView(fEntry)
        cbSound = CheckBox(this).apply {
            text = "Звук будильника (иначе только уведомление)"
            isChecked = prefs.soundOn
        }
        col.addView(cbSound)
        cbPeek = CheckBox(this).apply {
            text = "Подглядывать, когда WB Job свёрнут (на 3–5 сек откроет WB Job и вернёт обратно)"
            isChecked = prefs.peekMode
        }
        col.addView(cbPeek)
        val (fIdle, eIdle) = field("Подглядывать, если телефон не трогали, сек", prefs.idleSec.toString(), InputType.TYPE_CLASS_NUMBER)
        etIdle = eIdle; col.addView(fIdle)
        col.addView(button("Сохранить настройки") { saveSettings() })

        col.addView(header("Блоки"))
        col.addView(label("Галочка — блок участвует в будильнике и графиках. Цвет = цвет линии на графике.", 13f))
        blocksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(blocksBox)

        // --- Графики
        col.addView(header("Тариф по времени"))
        val periods = listOf(24 to "24 ч", 72 to "3 дня", 168 to "7 дней")
        val periodRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((h, t) in periods) {
            val b = button(t) { recentHours = h; render() }
            b.tag = h
            periodButtons.add(b)
            periodRow.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        col.addView(periodRow)
        chartRecent = ChartView(this)
        col.addView(chartRecent)
        col.addView(label("Красная пунктирная линия — ваш порог.", 12f))

        col.addView(header("Средний тариф по часам суток"))
        col.addView(label("За последние 14 дней. Показывает, в какое время тариф обычно выше.", 12f))
        chartHourly = ChartView(this)
        col.addView(chartHourly)

        tvFreqTitle = header("Как часто тариф ≥ порога (по часам)")
        col.addView(tvFreqTitle)
        col.addView(label("Доля замеров в этот час, когда хотя бы один блок был ≥ порога. За 14 дней.", 12f))
        chartFreq = ChartView(this)
        col.addView(chartFreq)
        tvBest = label(size = 14f)
        col.addView(tvBest)

        // --- Прочее
        col.addView(header("Прочее"))
        col.addView(row(
            button("Тест будильника") { AlarmPlayer.fire(this, "Тест: так будет звучать будильник", cbSound.isChecked) },
            button("Стоп звук") { AlarmPlayer.stop(this) }
        ))
        col.addView(row(
            button("Что видит служба") { showDiagnostics() },
            button("Экспорт CSV") { exportCsv() }
        ))
        col.addView(button("Очистить статистику") { confirmClear() })
        col.addView(button("Настройки батареи для приложения") { openAppSettings() })
        col.addView(button("Xiaomi: разрешить открывать окна в фоне") { openXiaomiPerms() })

        return ScrollView(this).apply { addView(col) }
    }

    // --------------------------------------------------------------- Actions

    private fun toggleMonitoring() {
        if (prefs.monitoring) {
            prefs.monitoring = false
            prefs.nightMode = false
            prefs.status = "Слежка остановлена"
            render()
            return
        }
        val svc = TariffService.instance
        if (svc == null) {
            Toast.makeText(this, "Сначала включите «Тариф-радар» в спец. возможностях", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        saveSettings(silent = true)
        prefs.monitoring = true
        prefs.status = "Слежка запущена — откройте «Тарифы операций»"
        svc.onMonitoringStarted()
        render()
        packageManager.getLaunchIntentForPackage(prefs.wbPackage)?.let {
            Toast.makeText(this, "Откройте в WB Job «Тарифы операций»", Toast.LENGTH_LONG).show()
            startActivity(it)
        }
    }

    private fun startNight() {
        if (TariffService.instance == null) {
            Toast.makeText(this, "Сначала включите «Тариф-радар» в спец. возможностях", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        prefs.nightMode = true
        if (!prefs.monitoring) toggleMonitoring()
        else packageManager.getLaunchIntentForPackage(prefs.wbPackage)?.let { startActivity(it) }
        render()
    }

    private fun saveSettings(silent: Boolean = false) {
        val thr = etThr.text.toString().replace(',', '.').replace(" ", "").toDoubleOrNull()
        val interval = etInt.text.toString().trim().toIntOrNull()
        val entry = etEntry.text.toString().trim()
        val errors = ArrayList<String>()
        if (thr != null && thr > 0 && thr < 1000) prefs.threshold = thr else errors.add("порог")
        if (interval != null && interval in 1..180) prefs.intervalMin = interval else errors.add("интервал (1–180 мин)")
        if (entry.isNotEmpty()) prefs.entryText = entry else errors.add("текст кнопки")
        prefs.soundOn = cbSound.isChecked
        prefs.peekMode = cbPeek.isChecked
        val idle = etIdle.text.toString().trim().toIntOrNull()
        if (idle != null && idle in 3..600) prefs.idleSec = idle else errors.add("секунды без касаний (3–600)")
        if (errors.isNotEmpty()) {
            Toast.makeText(this, "Проверьте: " + errors.joinToString(", "), Toast.LENGTH_LONG).show()
        } else if (!silent) {
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show()
        }
        render()
    }

    private fun showDiagnostics() {
        val text = buildString {
            append("Служба: ").append(if (TariffService.instance != null) "включена" else "выключена").append('\n')
            append("Пакет WB Job: ").append(prefs.wbPackage).append('\n')
            append("Статус: ").append(prefs.status).append("\n\n")
            append("=== Экран тарифов ===\n").append(prefs.dumpTariff.ifEmpty { "(ещё не видела)" }).append("\n\n")
            append("=== Предыдущий экран WB Job ===\n").append(prefs.dumpOther.ifEmpty { "(ещё не видела)" })
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Что видит служба")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Копировать") { _, _ ->
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("radar", text))
                Toast.makeText(this, "Скопировано", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun exportCsv() {
        val rows = db.since(System.currentTimeMillis() - 30L * 24 * 3600_000)
        if (rows.isEmpty()) {
            Toast.makeText(this, "Пока нечего выгружать", Toast.LENGTH_SHORT).show(); return
        }
        val csv = buildString {
            append("время;блок;тариф\n")
            for (r in rows) append(Fmt.full(r.ts)).append(';').append(r.block).append(';')
                .append(r.price.toString().replace('.', ',')).append('\n')
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Тарифы сборки (30 дней)")
            putExtra(Intent.EXTRA_TEXT, csv)
        }
        startActivity(Intent.createChooser(send, "Отправить статистику"))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Удалить всю статистику?")
            .setMessage("Все замеры и графики будут стёрты.")
            .setPositiveButton("Удалить") { _, _ -> db.clear(); shownBlocks = emptyList(); render() }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openXiaomiPerms() {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            putExtra("extra_pkgname", packageName)
        }
        try {
            startActivity(miui)
            Toast.makeText(this, "Включите «Отображать всплывающие окна, когда запущено в фоновом режиме»", Toast.LENGTH_LONG).show()
        } catch (_: Throwable) {
            openAppSettings()
            Toast.makeText(this, "Откройте «Другие разрешения» → всплывающие окна в фоне", Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    // ---------------------------------------------------------------- Render

    private fun colorOf(block: String, all: List<String>): Int {
        val i = all.indexOf(block).coerceAtLeast(0)
        return palette[i % palette.size]
    }

    private fun render() {
        val svcOn = TariffService.instance != null
        tvService.text = if (svcOn) "✅ Чтение экрана включено"
        else "❌ Чтение экрана выключено. Включите «Тариф-радар» в настройках «Специальные возможности» (раздел «Скачанные приложения»)."
        btnService.visibility = if (svcOn) View.GONE else View.VISIBLE
        btnMonitor.text = if (prefs.monitoring) "⏸ Остановить слежку" else "▶ Начать слежку"
        tvStatus.text = prefs.status

        val latest = db.latest()
        tvLast.text = if (latest == null) "Замеров пока нет"
        else buildString {
            append(Fmt.dateTime(latest.first)).append("  (всего замеров: ").append(db.snapshotCount()).append(")\n")
            for ((b, p) in latest.second) {
                append(b).append(" — ").append(Fmt.price(p))
                if (p >= prefs.threshold) append("  ⬆")
                append('\n')
            }
        }.trimEnd()

        val all = db.blocks()
        if (all != shownBlocks) rebuildBlocks(all)
        for (b in periodButtons) b.isEnabled = b.tag != recentHours

        val enabled = all.filter { it !in prefs.disabledBlocks }
        renderRecent(all, enabled)
        renderHourly(all, enabled)
    }

    private fun rebuildBlocks(all: List<String>) {
        shownBlocks = all
        blocksBox.removeAllViews()
        if (all.isEmpty()) {
            blocksBox.addView(label("Блоки появятся после первого замера.", 13f))
            return
        }
        val off = prefs.disabledBlocks
        for (b in all) {
            blocksBox.addView(CheckBox(this).apply {
                text = b
                setTextColor(colorOf(b, all))
                setTypeface(null, Typeface.BOLD)
                isChecked = b !in off
                setOnCheckedChangeListener { _, checked ->
                    val s = prefs.disabledBlocks.toMutableSet()
                    if (checked) s.remove(b) else s.add(b)
                    prefs.disabledBlocks = s
                    render()
                }
            })
        }
    }

    private fun renderRecent(all: List<String>, enabled: List<String>) {
        val now = System.currentTimeMillis()
        val from = now - recentHours * 3600_000L
        val rows = db.since(from).filter { it.block in enabled }
        val byBlock = rows.groupBy { it.block }
        chartRecent.series = enabled.mapNotNull { b ->
            val list = byBlock[b] ?: return@mapNotNull null
            ChartView.Series(
                b, colorOf(b, all),
                FloatArray(list.size) { (list[it].ts - from) / 3600_000f },
                FloatArray(list.size) { list[it].price.toFloat() }
            )
        }
        chartRecent.xMin = 0f
        chartRecent.xMax = recentHours.toFloat()
        val step = recentHours / 6f
        chartRecent.xTicks = (0..6).map { it * step }
        chartRecent.xLabel = { h ->
            val ts = from + (h * 3600_000L).toLong()
            if (recentHours <= 24) Fmt.time(ts) else Fmt.dateTime(ts).substring(0, 5)
        }
        // Разрыв линии, если замеров не было дольше 3 интервалов (или 20 мин)
        chartRecent.gap = maxOf(prefs.intervalMin * 3, 20) / 60f
        chartRecent.threshold = prefs.threshold.toFloat()
        chartRecent.ySuffix = ""
        chartRecent.invalidate()
    }

    private fun renderHourly(all: List<String>, enabled: List<String>) {
        val from = System.currentTimeMillis() - 14L * 24 * 3600_000
        val rows = db.since(from)
        val cal = Calendar.getInstance()
        fun hourOf(ts: Long): Int { cal.timeInMillis = ts; return cal.get(Calendar.HOUR_OF_DAY) }

        // Средний тариф по часам для каждого блока
        val series = ArrayList<ChartView.Series>()
        for (b in enabled) {
            val sum = DoubleArray(24); val cnt = IntArray(24)
            for (r in rows) if (r.block == b) { val h = hourOf(r.ts); sum[h] += r.price; cnt[h]++ }
            val hours = (0 until 24).filter { cnt[it] > 0 }
            if (hours.isEmpty()) continue
            series.add(ChartView.Series(
                b, colorOf(b, all),
                FloatArray(hours.size) { hours[it] + 0.5f },
                FloatArray(hours.size) { (sum[hours[it]] / cnt[hours[it]]).toFloat() }
            ))
        }
        chartHourly.series = series
        chartHourly.xMin = 0f; chartHourly.xMax = 24f
        chartHourly.xTicks = (0..8).map { it * 3f }
        chartHourly.xLabel = { "${it.toInt()}:00" }
        chartHourly.gap = 1.01f
        chartHourly.threshold = prefs.threshold.toFloat()
        chartHourly.invalidate()

        // Как часто хотя бы один блок ≥ порога, по часам
        val thr = prefs.threshold
        val total = IntArray(24); val hit = IntArray(24)
        rows.filter { it.block in enabled }.groupBy { it.ts }.forEach { (ts, list) ->
            val h = hourOf(ts)
            total[h]++
            if (list.any { it.price >= thr - 0.0001 }) hit[h]++
        }
        val hours = (0 until 24).filter { total[it] > 0 }
        chartFreq.series = if (hours.isEmpty()) emptyList() else listOf(
            ChartView.Series(
                "%", 0xFF7B3FE4.toInt(),
                FloatArray(hours.size) { hours[it] + 0.5f },
                FloatArray(hours.size) { hit[hours[it]] * 100f / total[hours[it]] }
            )
        )
        chartFreq.bars = true
        chartFreq.fixedYMax = 100f
        chartFreq.ySuffix = "%"
        chartFreq.xMin = 0f; chartFreq.xMax = 24f
        chartFreq.xTicks = (0..8).map { it * 3f }
        chartFreq.xLabel = { "${it.toInt()}:00" }
        chartFreq.invalidate()
        tvFreqTitle.text = "Как часто тариф ≥ ${Fmt.price(thr)} (по часам)"

        val best = hours.filter { total[it] >= 3 && hit[it] > 0 }
            .sortedByDescending { hit[it].toDouble() / total[it] }
            .take(3)
        tvBest.text = if (best.isEmpty()) "Лучшие часы появятся, когда наберётся статистика (минимум 3 замера в часе)."
        else "Чаще всего тариф высокий:\n" + best.joinToString("\n") { h ->
            "• %02d:00–%02d:00 — %d%% замеров (%d из %d)".format(
                h, (h + 1) % 24, hit[h] * 100 / total[h], hit[h], total[h]
            )
        }
    }
}
