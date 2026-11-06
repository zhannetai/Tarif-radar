package ru.tarifradar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Служба «специальных возможностей»: читает экран WB Job.
 *
 * Как работает слежка:
 *  1. Вы открываете в WB Job экран «Тарифы операций».
 *  2. Служба читает тарифы сборки и сохраняет их.
 *  3. Раз в N минут нажимает «назад», потом кнопку «Тарифы» — экран обновляется, служба читает снова.
 *  4. Если тариф выбранного блока ≥ порога — звонит будильник.
 * Кнопку «назад» служба жмёт ТОЛЬКО когда на экране точно «Тарифы операций».
 */
class TariffService : AccessibilityService() {

    companion object {
        @Volatile var instance: TariffService? = null
        const val ACTION_UPDATED = "ru.tarifradar.UPDATED"
    }

    private enum class Step { IDLE, WAIT_BACK, WAIT_LOAD }
    private enum class Click { OK, STILL_ON_TARIFFS, NOT_FOUND }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var db: Db

    private var step = Step.IDLE
    private var stepAt = 0L
    private var tries = 0
    private var nextRefreshAt = 0L
    private var savedThisVisit = false
    private var lastValues: Map<String, Double> = emptyMap()
    private val alerted = HashSet<String>()
    private var scanQueued = false
    private var lastAutoScanAt = 0L
    private var lastDumpT = ""
    private var lastDumpO = ""

    private var overlay: TextView? = null

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (t: Throwable) {
                prefs.status = "Ошибка: ${t.message}"
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        db = Db.get(this)
        instance = this
        AlarmPlayer.ensureChannels(this)
        prefs.status = "Служба запущена"
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (instance == null) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg == packageName || !isWbPackage(pkg)) return
        if (!scanQueued) {
            scanQueued = true
            handler.postDelayed({
                scanQueued = false
                try { scan() } catch (_: Throwable) {}
            }, 600)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
    }

    private fun isWbPackage(pkg: String) =
        pkg == prefs.wbPackage || pkg.contains("wildberries", ignoreCase = true)

    private fun intervalMs() = prefs.intervalMin.coerceIn(1, 180) * 60_000L

    /** Прочитать текущий экран. */
    private fun scan(): Parser.Result? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) return null
        val res = Parser.parse(Parser.collect(root))
        val now = System.currentTimeMillis()
        if (res.isTariffScreen) {
            if (prefs.wbPackage != pkg) prefs.wbPackage = pkg
            if (res.dump != lastDumpT) { lastDumpT = res.dump; prefs.dumpTariff = res.dump }
            if (res.prices.isNotEmpty()) onPrices(res.prices, now)
        } else if (isWbPackage(pkg)) {
            savedThisVisit = false
            if (res.dump != lastDumpO) { lastDumpO = res.dump; prefs.dumpOther = res.dump }
        }
        return res
    }

    private fun onPrices(prices: Map<String, Double>, now: Long) {
        val forced = step == Step.WAIT_LOAD
        val changed = prices != lastValues
        if (forced || !savedThisVisit || changed) {
            db.insert(now, prices)
            lastValues = HashMap(prices)
            savedThisVisit = true
            nextRefreshAt = now + intervalMs()
            prefs.status = "Последний замер в ${Fmt.time(now)}"
            checkAlarm(prices)
            sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
        }
        if (forced) step = Step.IDLE
    }

    private fun checkAlarm(prices: Map<String, Double>) {
        if (!prefs.monitoring) return
        val thr = prefs.threshold
        val off = prefs.disabledBlocks
        val hits = prices.filter { it.key !in off && it.value >= thr - 0.0001 }
        // Блоки, которые опустились ниже порога, снова «взводятся».
        alerted.retainAll(hits.keys)
        val fresh = hits.keys - alerted
        if (fresh.isNotEmpty()) {
            alerted.addAll(fresh)
            val text = hits.entries.joinToString(", ") { "${it.key}: ${Fmt.price(it.value)}" } +
                    " (порог ${Fmt.price(thr)})"
            AlarmPlayer.fire(this, text, prefs.soundOn)
        }
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        syncOverlay(now)
        if (!prefs.monitoring) {
            step = Step.IDLE
            return
        }
        when (step) {
            Step.IDLE -> {
                if (now < nextRefreshAt || now - lastAutoScanAt < 3000) return
                lastAutoScanAt = now
                val r = scan() ?: return
                if (!r.isTariffScreen) {
                    prefs.status = "Жду экран «Тарифы операций»"
                    return
                }
                if (now < nextRefreshAt) return // только что сохранили свежие данные
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    savedThisVisit = false
                    step = Step.WAIT_BACK
                    stepAt = now
                    tries = 0
                    prefs.status = "Обновляю тарифы…"
                }
            }
            Step.WAIT_BACK -> {
                if (now - stepAt < 1500) return
                when (clickEntry()) {
                    Click.OK -> { step = Step.WAIT_LOAD; stepAt = now }
                    Click.STILL_ON_TARIFFS -> { step = Step.IDLE; nextRefreshAt = now + 5000 }
                    Click.NOT_FOUND -> {
                        tries++
                        stepAt = now
                        if (tries >= 4) {
                            step = Step.IDLE
                            nextRefreshAt = now + intervalMs()
                            prefs.status = "Не нашёл кнопку «${prefs.entryText}»"
                            AlarmPlayer.info(
                                this, "Тариф-радар потерял экран",
                                "Не нашёл кнопку «${prefs.entryText}». Откройте «Тарифы операций» вручную — слежка продолжится."
                            )
                        }
                    }
                }
            }
            Step.WAIT_LOAD -> {
                if (now - stepAt > 15_000) {
                    step = Step.IDLE
                    nextRefreshAt = now + 5000
                    prefs.status = "Экран тарифов не открылся — жду, пока откроете вручную"
                } else if (now - stepAt >= 1500 && now - lastAutoScanAt >= 1500) {
                    lastAutoScanAt = now
                    scan()
                }
            }
        }
    }

    /** На предыдущем экране найти кнопку «Тарифы» и нажать. */
    private fun clickEntry(): Click {
        val root = rootInActiveWindow ?: return Click.NOT_FOUND
        val items = Parser.collect(root)
        if (Parser.hasTitle(items)) return Click.STILL_ON_TARIFFS
        val d = Parser.dump(items)
        if (d != lastDumpO) { lastDumpO = d; prefs.dumpOther = d }

        val target = prefs.entryText.trim()
        if (target.isEmpty()) return Click.NOT_FOUND
        val nodes = root.findAccessibilityNodeInfosByText(target)
        val node = nodes.firstOrNull { nodeText(it).equals(target, ignoreCase = true) }
            ?: nodes.firstOrNull { nodeText(it).startsWith(target, ignoreCase = true) }
            ?: return Click.NOT_FOUND

        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return Click.OK
            n = n.parent
            depth++
        }
        // Запасной вариант — «тапнуть пальцем» по центру надписи.
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return Click.NOT_FOUND
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return if (dispatchGesture(gesture, null, null)) Click.OK else Click.NOT_FOUND
    }

    private fun nodeText(n: AccessibilityNodeInfo): String =
        (n.text ?: n.contentDescription)?.toString()?.trim() ?: ""

    // ---------- Плашка поверх экрана (держит экран включённым) ----------

    private fun syncOverlay(now: Long) {
        val want = prefs.monitoring
        if (want && overlay == null) showOverlay()
        if (!want && overlay != null) removeOverlay()
        overlay?.text = overlayText(now)
    }

    private fun overlayText(now: Long): String {
        val top = lastValues.filterKeys { it !in prefs.disabledBlocks }.maxByOrNull { it.value }
        val line1 = if (top == null) "Радар: откройте «Тарифы операций»"
        else "Радар: макс ${top.key} ${Fmt.price(top.value)}"
        val line2 = when {
            step != Step.IDLE -> "обновляю…"
            lastValues.isEmpty() || nextRefreshAt == 0L -> "нажми — стоп"
            else -> {
                val s = ((nextRefreshAt - now) / 1000).coerceAtLeast(0)
                "обновлю через %d:%02d · нажми — стоп".format(s / 60, s % 60)
            }
        }
        return "$line1\n$line2"
    }

    private fun showOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dp = resources.displayMetrics.density
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 12f
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0xDD4A2A8C.toInt())
                    cornerRadius = 14 * dp
                }
                setOnClickListener {
                    prefs.monitoring = false
                    prefs.status = "Слежка остановлена"
                    handler.post { syncOverlay(System.currentTimeMillis()) }
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = (12 * dp).toInt()
                y = (120 * dp).toInt()
            }
            wm.addView(tv, lp)
            overlay = tv
        } catch (t: Throwable) {
            prefs.status = "Не удалось показать плашку: ${t.message}"
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {}
    }

    /** Вызывается из приложения при старте слежки. */
    fun onMonitoringStarted() {
        alerted.clear()
        step = Step.IDLE
        nextRefreshAt = 0L
        lastAutoScanAt = 0L
    }
}
