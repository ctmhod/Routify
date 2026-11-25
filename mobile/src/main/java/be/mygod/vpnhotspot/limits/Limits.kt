package be.mygod.vpnhotspot.limits

import android.os.Parcelable
import be.mygod.librootkotlinx.RootCommandNoResult
import be.mygod.vpnhotspot.App.Companion.app
import be.mygod.vpnhotspot.room.AppDatabase
import be.mygod.vpnhotspot.root.RootManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.parcelize.Parcelize
import org.json.JSONObject
import timber.log.Timber
import java.util.Calendar

enum class ResetRule(val label: String) {
    MANUAL("Manual only"),
    DAILY("Every day"),
    WEEKLY("Every week"),
    MONTHLY("Every month"),
}

enum class OverAction(val label: String) {
    BLOCK("Block the device"),
    THROTTLE("Slow to 32 kB/s"),
}

/** Values of 0 mean "no limit". Speeds are in kilobytes per second, quota in megabytes. */
data class Defaults(
    val down: Long = 0,
    val up: Long = 0,
    val quota: Long = 0,
    val rule: ResetRule = ResetRule.MONTHLY,
    val action: OverAction = OverAction.BLOCK,
)

/** Null fields mean "use the default from Settings". */
data class LimitOverride(
    val down: Long? = null,
    val up: Long? = null,
    val quota: Long? = null,
    val rule: ResetRule? = null,
    val action: OverAction? = null,
) {
    val isEmpty get() = down == null && up == null && quota == null && rule == null && action == null
}

data class Usage(val base: Long, val start: Long)

object LimitsStore {
    private const val KEY_DEFAULTS = "routify.limits.defaults"
    private const val KEY_CLIENTS = "routify.limits.clients"
    private const val KEY_USAGE = "routify.limits.usage"

    private fun JSONObject.longOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private inline fun <reified E : Enum<E>> JSONObject.enumOrNull(key: String): E? =
        if (has(key) && !isNull(key)) enumValues<E>().firstOrNull { it.name == getString(key) } else null

    private fun readObject(key: String): JSONObject = try {
        JSONObject(app.pref.getString(key, null) ?: "{}")
    } catch (e: Exception) {
        Timber.w(e)
        JSONObject()
    }

    @Synchronized
    fun defaults(): Defaults {
        val o = readObject(KEY_DEFAULTS)
        return Defaults(
            down = o.longOrNull("down") ?: 0,
            up = o.longOrNull("up") ?: 0,
            quota = o.longOrNull("quota") ?: 0,
            rule = o.enumOrNull<ResetRule>("rule") ?: ResetRule.MONTHLY,
            action = o.enumOrNull<OverAction>("action") ?: OverAction.BLOCK,
        )
    }

    @Synchronized
    fun setDefaults(d: Defaults) {
        val o = JSONObject().put("down", d.down).put("up", d.up).put("quota", d.quota)
            .put("rule", d.rule.name).put("action", d.action.name)
        app.pref.edit().putString(KEY_DEFAULTS, o.toString()).apply()
    }

    @Synchronized
    fun clientOverride(mac: String): LimitOverride {
        val o = readObject(KEY_CLIENTS).optJSONObject(mac) ?: return LimitOverride()
        return LimitOverride(
            down = o.longOrNull("down"),
            up = o.longOrNull("up"),
            quota = o.longOrNull("quota"),
            rule = o.enumOrNull<ResetRule>("rule"),
            action = o.enumOrNull<OverAction>("action"),
        )
    }

    @Synchronized
    fun setOverride(mac: String, v: LimitOverride) {
        val all = readObject(KEY_CLIENTS)
        if (v.isEmpty) all.remove(mac) else {
            val o = JSONObject()
            v.down?.let { o.put("down", it) }
            v.up?.let { o.put("up", it) }
            v.quota?.let { o.put("quota", it) }
            v.rule?.let { o.put("rule", it.name) }
            v.action?.let { o.put("action", it.name) }
            all.put(mac, o)
        }
        app.pref.edit().putString(KEY_CLIENTS, all.toString()).apply()
    }

    @Synchronized
    fun overrideMacs(): Set<String> {
        val all = readObject(KEY_CLIENTS)
        val result = LinkedHashSet<String>()
        val keys = all.keys()
        while (keys.hasNext()) result.add(keys.next())
        return result
    }

    @Synchronized
    fun usage(mac: String): Usage? {
        val o = readObject(KEY_USAGE).optJSONObject(mac) ?: return null
        return Usage(o.getLong("base"), o.getLong("start"))
    }

    @Synchronized
    fun setUsage(mac: String, u: Usage) {
        val all = readObject(KEY_USAGE)
        all.put(mac, JSONObject().put("base", u.base).put("start", u.start))
        app.pref.edit().putString(KEY_USAGE, all.toString()).apply()
    }

    class Effective(val down: Long, val up: Long, val quota: Long, val rule: ResetRule, val action: OverAction)

    fun effective(mac: String): Effective {
        val d = defaults()
        val o = clientOverride(mac)
        return Effective(o.down ?: d.down, o.up ?: d.up, o.quota ?: d.quota, o.rule ?: d.rule, o.action ?: d.action)
    }
}

@Parcelize
data class RunShell(val script: String) : RootCommandNoResult {
    override suspend fun execute(): Parcelable? = withContext(Dispatchers.IO) {
        val process = ProcessBuilder("/system/bin/sh", "-c", script).redirectErrorStream(true).start()
        process.outputStream.close()
        val output = process.inputStream.bufferedReader().readText()
        val code = process.waitFor()
        if (code != 0) Timber.w("Routify limits script exited with $code: $output")
        null
    }
}

object LimitsEnforcer {
    private const val TICK_MS = 20_000L
    private const val THROTTLE_KB = 32L
    private const val MB = 1024L * 1024L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)
    @Volatile
    private var knownMacs: Set<String> = emptySet()
    private var rulesInstalled = false

    fun updateClients(macs: Collection<String>) {
        val newMacs = macs.map { it.lowercase() }.toSet()
        if (newMacs != knownMacs) {
            knownMacs = newMacs
            requestUpdate()
        }
    }

    fun requestUpdate() {
        kick.trySend(Unit)
    }

    @Synchronized
    fun ensureRunning() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e)
                }
                withTimeoutOrNull(TICK_MS) { kick.receive() }
            }
        }
    }

    private suspend fun totalBytes(mac: String): Long {
        val parsed = android.net.MacAddress.fromString(mac)
        val stats = AppDatabase.instance.trafficRecordDao.queryStats(parsed)
        return stats.entries.sumOf { it.sentBytes + it.receivedBytes }
    }

    /** Bytes used in the current period, or null if the period has not started yet. */
    suspend fun usedBytes(mac: String): Long? {
        val usage = LimitsStore.usage(mac) ?: return null
        return (totalBytes(mac) - usage.base).coerceAtLeast(0)
    }

    suspend fun resetUsage(mac: String) {
        LimitsStore.setUsage(mac, Usage(totalBytes(mac), System.currentTimeMillis()))
        requestUpdate()
    }

    private fun nextReset(start: Long, rule: ResetRule): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = start
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        when (rule) {
            ResetRule.MANUAL -> return Long.MAX_VALUE
            ResetRule.DAILY -> c.add(Calendar.DAY_OF_YEAR, 1)
            ResetRule.WEEKLY -> {
                c.set(Calendar.DAY_OF_WEEK, c.firstDayOfWeek)
                c.add(Calendar.WEEK_OF_YEAR, 1)
            }
            ResetRule.MONTHLY -> {
                c.set(Calendar.DAY_OF_MONTH, 1)
                c.add(Calendar.MONTH, 1)
            }
        }
        return c.timeInMillis
    }

    private suspend fun tick() {
        val macs = (knownMacs + LimitsStore.overrideMacs()).toSortedSet()
        val now = System.currentTimeMillis()
        val body = StringBuilder()
        for (mac in macs) {
            val eff = LimitsStore.effective(mac)
            if (eff.down <= 0 && eff.up <= 0 && eff.quota <= 0) continue
            var down = eff.down
            var up = eff.up
            var drop = false
            if (eff.quota > 0) {
                val total = totalBytes(mac)
                var usage = LimitsStore.usage(mac)
                if (usage == null || total < usage.base || now >= nextReset(usage.start, eff.rule)) {
                    usage = Usage(total, now)
                    LimitsStore.setUsage(mac, usage)
                }
                if (total - usage.base >= eff.quota * MB) when (eff.action) {
                    OverAction.BLOCK -> drop = true
                    OverAction.THROTTLE -> {
                        down = THROTTLE_KB
                        up = THROTTLE_KB
                    }
                }
            }
            if (!drop && down <= 0 && up <= 0) continue
            val tag = mac.replace(":", "").takeLast(10)
            body.append("for ip in \$(\$IP -4 neigh show | grep -i \"lladdr $mac \" | cut -d' ' -f1); do\n")
            if (drop) {
                body.append("  \$IPT -w -A routify_limits -s \$ip -j DROP\n")
                body.append("  \$IPT -w -A routify_limits -d \$ip -j DROP\n")
            } else {
                if (up > 0) body.append("  \$IPT -w -A routify_limits -s \$ip -m hashlimit " +
                    "--hashlimit-above ${up}kb/s --hashlimit-mode srcip --hashlimit-name ru$tag -j DROP\n")
                if (down > 0) body.append("  \$IPT -w -A routify_limits -d \$ip -m hashlimit " +
                    "--hashlimit-above ${down}kb/s --hashlimit-mode dstip --hashlimit-name rd$tag -j DROP\n")
            }
            body.append("done\n")
        }
        if (body.isEmpty() && !rulesInstalled) return
        val script = buildString {
            append("IPT=/system/bin/iptables\n")
            append("IP=/system/bin/ip\n")
            append("\$IPT -w -N routify_limits 2>/dev/null\n")
            append("\$IPT -w -F routify_limits\n")
            append("\$IPT -w -D FORWARD -j routify_limits 2>/dev/null\n")
            if (body.isEmpty()) {
                append("\$IPT -w -X routify_limits 2>/dev/null\n")
            } else {
                append("\$IPT -w -I FORWARD 1 -j routify_limits\n")
                append(body)
            }
        }
        RootManager.use { it.execute(RunShell(script)) }
        rulesInstalled = body.isNotEmpty()
    }
}

