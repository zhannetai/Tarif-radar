package ru.tarifradar

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Разбирает экран «Тарифы операций».
 * На экране идёт так:  ЕКБ5 → «Собран на потоковой сборке.» → «10,00 ₽» → ЕКБ6 → ...
 * Берём только операции, где есть слово «сборк».
 */
object Parser {
    const val TITLE = "Тарифы операций"
    private const val OPERATION_KEY = "сборк"

    /** Название блока: несколько заглавных букв и цифра, например ЕКБ5, ЕКПБ2. */
    private val blockRe = Regex("^[A-ZА-ЯЁ]{2,8}\\d{1,3}$")
    private val priceRe = Regex("(\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})*[.,]\\d{1,2})\\s*₽")

    data class Item(val text: String, val top: Int, val left: Int)

    class Result(
        val isTariffScreen: Boolean,
        val prices: LinkedHashMap<String, Double>,
        val dump: String
    )

    fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>()
        walk(root, out, 0)
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<Item>, depth: Int) {
        if (depth > 40) return
        val t = (node.text?.toString()?.takeIf { it.isNotBlank() }
            ?: node.contentDescription?.toString())?.trim()
        if (!t.isNullOrEmpty() && node.isVisibleToUser) {
            val r = Rect()
            node.getBoundsInScreen(r)
            out.add(Item(t, r.top, r.left))
        }
        for (i in 0 until node.childCount) {
            val ch = node.getChild(i) ?: continue
            walk(ch, out, depth + 1)
        }
    }

    fun hasTitle(items: List<Item>) = items.any { it.text.contains(TITLE, ignoreCase = true) }

    fun dump(items: List<Item>): String =
        items.sortedWith(compareBy<Item>({ it.top }, { it.left }))
            .joinToString("\n") { "[${it.top}] ${it.text}" }
            .take(5000)

    fun parse(items: List<Item>): Result {
        val sorted = items.sortedWith(compareBy<Item>({ it.top }, { it.left }))
        val isTariff = hasTitle(sorted)
        val prices = LinkedHashMap<String, Double>()
        if (isTariff) {
            var block: String? = null
            var op: String? = null
            for (item in sorted) {
                for (raw in item.text.split('\n')) {
                    val line = raw.trim()
                    if (line.isEmpty()) continue
                    if (blockRe.matches(line)) {
                        block = line; op = null; continue
                    }
                    val m = priceRe.find(line)
                    if (m != null) {
                        val before = line.substring(0, m.range.first).trim()
                        if (before.any { it.isLetter() }) op = before
                        val value = m.groupValues[1]
                            .replace(" ", "").replace(" ", "").replace(" ", "")
                            .replace(',', '.').toDoubleOrNull()
                        val b = block
                        val o = op
                        if (b != null && o != null && value != null &&
                            o.contains(OPERATION_KEY, ignoreCase = true)
                        ) {
                            if (!prices.containsKey(b)) prices[b] = value
                        }
                    } else if (line.any { it.isLetter() } && !line.contains(TITLE, true)) {
                        op = line
                    }
                }
            }
        }
        return Result(isTariff, prices, dump(sorted))
    }
}
