package ru.tarifradar

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

/**
 * Служба «специальных возможностей»: читает экран WB Job.
 *
 * Обычный режим: открыт экран «Тарифы операций» → раз в N минут «назад» + «Тарифы» → читаем.
 * Режим «подглядывания»: WB Job свёрнут → ждём, пока телефон не трогают X секунд →
 *   открываем WB Job, обновляем и читаем тарифы → возвращаем человека в приложение, где он был.
 * Кнопку «назад» служба жмёт ТОЛЬКО когда на экране точно «Тарифы операций».
 */
class TariffService : AccessibilityService() {

    companion object {
        @Volatile var instance: TariffService? = null
        const val ACTION_UPDATED = "ru.tarifradar.UPDATED"

        private const val ACTIVITY_TYPES =
            AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SELECTED

        /** Когда эти приложения на экране — не подглядываем (звонок, шторка, камера, клавиатура). */
        private val BUSY = listOf(
            "systemui", "incallui", "dialer", "telecom", "com.android.phone",
            "camera", "inputmethod", "keyboard", "voip"
        )
    }

    private enum class Step { IDLE, PEEK_OPEN, WAIT_BACK, WAIT_LOAD }
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

    private var peeking = false
    private var returnPkg: String? = null
    private var lastUserActivityAt = 0L
    private var waitReason = ""
    private var homePkg: String? = null

    private var overlay: TextView? = null
    private var overlayLp: WindowManager.LayoutParams? = null

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
        homePkg = try {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )?.activityInfo?.packageName
        } catch (_: Throwable) { null }
        prefs.status = "Служба запущена"
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (instance == null) return
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (pkg == packageName) {
            if ((e.eventType and ACTIVITY_TYPES) != 0) lastUserActivityAt = now
            return
        }
        val wb = isWbPackage(pkg)
        // Человек что-то нажимает / листает в другом приложении — значит, пользуется телефоном.
        if (!wb && (e.eventType and ACTIVITY_TYPES) != 0) lastUserActivityAt = now
        if (!wb) return
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

    private fun isBusy(pkg: String) = BUSY.any { pkg.contains(it, ignoreCase = true) }

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
        // Только что вытащили WB Job из фона — на экране старые цифры, их не пишем.
        if (step == Step.PEEK_OPEN || step == Step.WAIT_BACK) return
        val forced = step == Step.WAIT_LOAD
        val changed = prices != lastValues
        if (forced || !savedThisVisit || changed) {
            db.insert(now, prices)
            lastValues = HashMap(prices)
            savedThisVisit = true
            nextRefreshAt = now + intervalMs()
            waitReason = ""
            prefs.status = "Последний замер в ${Fmt.time(now)}"
            checkAlarm(prices)
            sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
        }
        if (forced) {
            step = Step.IDLE
            finishPeek()
        }
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
            if (peeking) finishPeek()
            step = Step.IDLE
            return
        }
        when (step) {
            Step.IDLE -> idleTick(now)

            Step.PEEK_OPEN -> {
                if (now - stepAt < 1200) return
                val r = scan()
                if (r?.isTariffScreen == true) {
                    pressBack(now)
                    return
                }
                // WB Job открылся не на тарифах — пробуем нажать «Тарифы»
                if (now - stepAt >= 2500) {
                    when (clickEntry()) {
                        Click.OK -> { step = Step.WAIT_LOAD; stepAt = now }
                        Click.STILL_ON_TARIFFS -> pressBack(now)
                        Click.NOT_FOUND -> if (now - stepAt > 7000) {
                            fail(now, "WB Job открылся не на тарифах. Откройте «Тарифы операций» и сверните WB Job.")
                        }
                    }
                }
            }

            Step.WAIT_BACK -> {
                if (now - stepAt < 1500) return
                when (clickEntry()) {
                    Click.OK -> { step = Step.WAIT_LOAD; stepAt = now }
                    Click.STILL_ON_TARIFFS -> {
                        step = Step.IDLE
                        nextRefreshAt = now + 5000
                        finishPeek()
                    }
                    Click.NOT_FOUND -> {
                        tries++
                        stepAt = now
                        if (tries >= 4) {
                            fail(now, "Не нашёл кнопку «${prefs.entryText}»")
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
                    fail(now, "Экран тарифов не открылся")
                } else if (now - stepAt >= 1500 && now - lastAutoScanAt >= 1500) {
                    lastAutoScanAt = now
                    scan()
                }
            }
        }
    }

    private fun idleTick(now: Long) {
        if (now < nextRefreshAt || now - lastAutoScanAt < 3000) return
        lastAutoScanAt = now
        val root = rootInActiveWindow
        if (root == null) { waitReason = "жду (экран выключен?)"; return }
        val pkg = root.packageName?.toString() ?: ""
        if (pkg == packageName) { waitReason = "жду, пока закроете Тариф-радар"; return }

        val r = scan() ?: return
        if (r.isTariffScreen) {
            if (now < nextRefreshAt) return // только что сохранили свежие данные
            pressBack(now)
            return
        }
        if (isWbPackage(pkg)) { waitReason = "WB Job открыт не на тарифах"; return }
        if (!prefs.peekMode) { waitReason = "WB Job свёрнут — откройте тарифы"; return }
        if (isBusy(pkg)) { waitReason = "жду (звонок / шторка / клавиатура)"; return }
        val need = prefs.idleSec.coerceIn(3, 600) * 1000L
        val idle = now - lastUserActivityAt
        if (idle < need) {
            waitReason = "жду, пока не трогаете телефон (${(need - idle) / 1000 + 1} с)"
            return
        }
        startPeek(pkg, now)
    }

    private fun pressBack(now: Long) {
        if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            savedThisVisit = false
            step = Step.WAIT_BACK
            stepAt = now
            tries = 0
            prefs.status = "Обновляю тарифы…"
        } else {
            fail(now, "Не получилось нажать «назад»")
        }
    }

    private fun startPeek(fromPkg: String, now: Long) {
        val intent = packageManager.getLaunchIntentForPackage(prefs.wbPackage)
        if (intent == null) {
            waitReason = "не нашёл WB Job на телефоне"
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        returnPkg = fromPkg
        peeking = true
        step = Step.PEEK_OPEN
        stepAt = now
        savedThisVisit = false
        prefs.status = "Подглядываю в WB Job…"
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            peeking = false
            returnPkg = null
            step = Step.IDLE
            nextRefreshAt = now + 60_000
            waitReason = "Xiaomi не дал открыть WB Job (нужно разрешение «всплывающие окна в фоне»)"
        }
    }

    private fun fail(now: Long, msg: String) {
        step = Step.IDLE
        nextRefreshAt = now + intervalMs()
        prefs.status = msg
        finishPeek()
    }

    /** Вернуть человека туда, где он был до «подглядывания». */
    private fun finishPeek() {
        if (!peeking) return
        peeking = false
        val p = returnPkg
        returnPkg = null
        handler.postDelayed({
            try {
                val launch = if (p == null || p == homePkg) null
                else packageManager.getLaunchIntentForPackage(p)
                if (launch == null) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                } else {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                }
            } catch (_: Throwable) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            lastUserActivityAt = System.currentTimeMillis() - prefs.idleSec * 1000L + 2000
        }, 300)
    }

    /** На предыдущем экране найти кнопку «Тарифы» и нажать. */
    private fun clickEntry(): Click {
        val root = rootInActiveWindow ?: return Click.NOT_FOUND
        val pkg = root.packageName?.toString() ?: ""
        if (!isWbPackage(pkg)) return Click.NOT_FOUND
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

    // ---------- Плашка поверх экрана ----------

    private fun syncOverlay(now: Long) {
        val want = prefs.monitoring
        if (want && overlay == null) showOverlay()
        if (!want && overlay != null) removeOverlay()
        overlay?.text = overlayText(now)
    }

    private fun overlayText(now: Long): String {
        val top = lastValues.filterKeys { it !in prefs.disabledBlocks }.maxByOrNull { it.value }
        val line1 = if (top == null) "Радар: ещё нет замеров"
        else "Радар: макс ${top.key} ${Fmt.price(top.value)}"
        val line2 = when {
            peeking || step == Step.PEEK_OPEN -> "подглядываю в WB Job…"
            step != Step.IDLE -> "обновляю…"
            nextRefreshAt == 0L || now >= nextRefreshAt ->
                waitReason.ifEmpty { "откройте «Тарифы операций»" }
            else -> {
                val s = ((nextRefreshAt - now) / 1000).coerceAtLeast(0)
                "обновлю через %d:%02d".format(s / 60, s % 60)
            }
        }
        return "$line1\n$line2\nтап — стоп, тяни — двигать"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dp = resources.displayMetrics.density
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 11f
                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                background = GradientDrawable().apply {
                    setColor(0xDD4A2A8C.toInt())
                    cornerRadius = 14 * dp
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
                x = if (prefs.overlayX >= 0) prefs.overlayX else (12 * dp).toInt()
                y = if (prefs.overlayY >= 0) prefs.overlayY else (120 * dp).toInt()
            }

            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false
            tv.setOnTouchListener { v, ev ->
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY
                        startX = lp.x; startY = lp.y
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX
                        val dy = ev.rawY - downY
                        if (abs(dx) > 12 * dp || abs(dy) > 12 * dp) moved = true
                        if (moved) {
                            // gravity BOTTOM|END: x считается от правого края, y — от нижнего
                            lp.x = (startX - dx).toInt().coerceAtLeast(0)
                            lp.y = (startY - dy).toInt().coerceAtLeast(0)
                            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            prefs.overlayX = lp.x
                            prefs.overlayY = lp.y
                        } else {
                            prefs.monitoring = false
                            prefs.status = "Слежка остановлена"
                            handler.post { syncOverlay(System.currentTimeMillis()) }
                        }
                        true
                    }
                    else -> false
                }
            }
            wm.addView(tv, lp)
            overlay = tv
            overlayLp = lp
        } catch (t: Throwable) {
            prefs.status = "Не удалось показать плашку: ${t.message}"
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        overlayLp = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Throwable) {}
    }

    /** Вызывается из приложения при старте слежки. */
    fun onMonitoringStarted() {
        alerted.clear()
        step = Step.IDLE
        peeking = false
        returnPkg = null
        nextRefreshAt = 0L
        lastAutoScanAt = 0L
        lastUserActivityAt = 0L
        waitReason = ""
    }
}
