package ru.tarifradar

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Настройки приложения (хранятся на телефоне). */
class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("radar", Context.MODE_PRIVATE)

    /** Идёт ли слежка (авто-обновление + будильник). */
    var monitoring: Boolean
        get() = p.getBoolean("monitoring", false)
        set(v) = p.edit().putBoolean("monitoring", v).apply()

    /** Порог тарифа в рублях — при нём и выше звонит будильник. */
    var threshold: Double
        get() = p.getFloat("threshold", 6f).toDouble()
        set(v) = p.edit().putFloat("threshold", v.toFloat()).apply()

    /** Как часто обновлять экран тарифов, минут. */
    var intervalMin: Int
        get() = p.getInt("interval", 2)
        set(v) = p.edit().putInt("interval", v).apply()

    /** Текст кнопки, по которой открываются тарифы с предыдущего экрана. */
    var entryText: String
        get() {
            val v = p.getString("entry", null)?.trim()
            // Раньше по умолчанию было «Тарифы» — это заголовок меню, а нажимать нужно пункт.
            return if (v.isNullOrEmpty() || v.equals("Тарифы", ignoreCase = true)) "Тарифы операций" else v
        }
        set(v) = p.edit().putString("entry", v).apply()

    /** Подглядывать в WB Job, когда он свёрнут (открыть на пару секунд и вернуть назад). */
    var peekMode: Boolean
        get() = p.getBoolean("peek", true)
        set(v) = p.edit().putBoolean("peek", v).apply()

    /** Сколько секунд телефон должен быть «без касаний», чтобы подглядеть. */
    var idleSec: Int
        get() = p.getInt("idle", 15)
        set(v) = p.edit().putInt("idle", v).apply()

    var overlayX: Int
        get() = p.getInt("ovx", -1)
        set(v) = p.edit().putInt("ovx", v).apply()

    var overlayY: Int
        get() = p.getInt("ovy", -1)
        set(v) = p.edit().putInt("ovy", v).apply()

    var soundOn: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    /** Блоки, которые выключены (не звонят и не рисуются). */
    var disabledBlocks: Set<String>
        get() = HashSet(p.getStringSet("disabled", emptySet()) ?: emptySet())
        set(v) = p.edit().putStringSet("disabled", HashSet(v)).apply()

    /** Имя пакета WB Job (уточняется автоматически, когда служба увидит экран тарифов). */
    var wbPackage: String
        get() = p.getString("wbpkg", "ru.wildberries.team") ?: "ru.wildberries.team"
        set(v) = p.edit().putString("wbpkg", v).apply()

    var status: String
        get() = p.getString("status", "") ?: ""
        set(v) = p.edit().putString("status", v).apply()

    /** Что служба последний раз видела на экране тарифов и на предыдущем экране — для диагностики. */
    var dumpTariff: String
        get() = p.getString("dumpT", "") ?: ""
        set(v) = p.edit().putString("dumpT", v).apply()

    var dumpOther: String
        get() = p.getString("dumpO", "") ?: ""
        set(v) = p.edit().putString("dumpO", v).apply()
}

object Fmt {
    private val ru = Locale.forLanguageTag("ru")
    fun price(v: Double): String = String.format(ru, "%.2f ₽", v)
    fun time(ts: Long): String = SimpleDateFormat("HH:mm", ru).format(Date(ts))
    fun dateTime(ts: Long): String = SimpleDateFormat("dd.MM HH:mm", ru).format(Date(ts))
    fun full(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", ru).format(Date(ts))
}
