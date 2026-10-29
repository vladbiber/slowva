package com.slowva.player.ui.wheel

import android.content.Context
import java.security.SecureRandom

enum class WheelMode(val label: String) {
    NO_REPEATS("No repeats"),
    PURE("Pure random"),
    FAVORITES("Favorites ×3"),
}

/** A built wheel: its slices and the slot that will win. */
data class Wheel(val slices: List<WheelItem>, val winner: Int, val poolSize: Int, val left: Int)

/** Picks the winner first, then builds the wheel around it. One SecureRandom for everything. */
class WheelPicker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wheel_deck", Context.MODE_PRIVATE)

    var mode: WheelMode
        get() = runCatching { WheelMode.valueOf(prefs.getString("mode", null)!!) }.getOrDefault(WheelMode.NO_REPEATS)
        set(v) = prefs.edit().putString("mode", v.name).apply()

    fun nextInt(bound: Int) = rng.nextInt(bound)
    fun nextFloat() = rng.nextFloat()

    /** The next winner is only peeked here; [commit] uses it up when the wheel spins. */
    fun build(key: String, pool: List<WheelItem>, isFavorite: (WheelItem) -> Boolean): Wheel? {
        if (pool.isEmpty()) return null
        val byId = pool.associateBy { it.id }
        var left = pool.size
        val winner = when (mode) {
            WheelMode.PURE -> pool[rng.nextInt(pool.size)]
            WheelMode.FAVORITES -> weighted(pool) { if (isFavorite(it)) 3 else 1 }
            WheelMode.NO_REPEATS -> {
                val deck = deck(key, pool)
                left = deck.size
                byId[deck.last()] ?: pool[rng.nextInt(pool.size)]
            }
        }
        val n = if (pool.size < MIN_SLICES) pool.size else minOf(MIN_SLICES + rng.nextInt(MAX_SLICES - MIN_SLICES + 1), pool.size)
        val others = sample(pool.filter { it.id != winner.id }, n - 1).toMutableList()
        val slot = rng.nextInt(n)
        others.add(slot, winner)
        return Wheel(others, slot, pool.size, left)
    }

    /** The spin happened; take the winner out of the deck. */
    fun commit(key: String, id: String) {
        if (mode != WheelMode.NO_REPEATS) return
        val ids = prefs.getString("deck_$key", null)?.split('\n')?.filter { it.isNotEmpty() } ?: return
        save(key, prefs.getString("sig_$key", "")!!, ids - id)
    }

    private fun deck(key: String, pool: List<WheelItem>): List<String> {
        val sig = signature(pool)
        val saved = prefs.getString("deck_$key", null)?.split('\n')?.filter { it.isNotEmpty() }
        if (saved != null && saved.isNotEmpty() && prefs.getString("sig_$key", null) == sig) return saved
        val fresh = pool.map { it.id }.toMutableList()
        // Fisher-Yates
        for (i in fresh.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = fresh[i]; fresh[i] = fresh[j]; fresh[j] = t
        }
        save(key, sig, fresh)
        return fresh
    }

    private fun save(key: String, sig: String, ids: List<String>) {
        prefs.edit().putString("deck_$key", ids.joinToString("\n")).putString("sig_$key", sig).apply()
    }

    private fun signature(pool: List<WheelItem>): String {
        val ids = pool.map { it.id }.sorted()
        return "${ids.size}:${ids.hashCode()}"
    }

    private fun weighted(pool: List<WheelItem>, weight: (WheelItem) -> Int): WheelItem {
        val w = pool.map(weight)
        var x = rng.nextInt(w.sum())
        pool.forEachIndexed { i, item -> x -= w[i]; if (x < 0) return item }
        return pool.last()
    }

    /** k distinct items, partial Fisher-Yates */
    private fun sample(list: List<WheelItem>, k: Int): List<WheelItem> {
        val a = list.toMutableList()
        val n = minOf(k, a.size)
        for (i in 0 until n) {
            val j = i + rng.nextInt(a.size - i)
            val t = a[i]; a[i] = a[j]; a[j] = t
        }
        return a.subList(0, n)
    }

    companion object {
        const val MIN_SLICES = 8
        const val MAX_SLICES = 12
        private val rng = SecureRandom()
    }
}
