package com.sbwnpc.squad.squad

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import java.util.UUID

/** One shared production clock per barracks; only ticking the block advances it. */
class BarracksRecruitmentQueue {
    data class Request(val squad: UUID, val slot: UUID, val replacement: Boolean)

    private val requests = mutableListOf<Request>()
    var remainingTicks: Int = 0
        private set
    private var replacementStreak = 0

    fun synchronize(wanted: List<Request>, intervalTicks: Int) {
        val valid = wanted.toSet()
        requests.retainAll(valid)
        val wasEmpty = requests.isEmpty()
        val existing = requests.toHashSet()
        wanted.forEach { if (existing.add(it)) requests += it }
        if (wasEmpty && requests.isNotEmpty()) remainingTicks = maxOf(remainingTicks, intervalTicks)
    }

    fun tick() {
        if (remainingTicks > 0) remainingTicks--
    }

    /** Three replacements, then one initial recruit when both kinds keep waiting. */
    fun ordered(): List<Request> {
        val remaining = requests.toMutableList()
        val result = mutableListOf<Request>()
        var streak = replacementStreak
        while (remaining.isNotEmpty()) {
            val replacement = remaining.firstOrNull { it.replacement }
            val initial = remaining.firstOrNull { !it.replacement }
            val next = if (replacement != null && (initial == null || streak < MAX_REPLACEMENT_STREAK)) replacement
                else initial!!
            remaining.remove(next)
            result += next
            streak = if (next.replacement) streak + 1 else 0
        }
        return result
    }

    fun next(): Request? = ordered().firstOrNull()

    fun complete(request: Request, intervalTicks: Int) {
        check(remainingTicks == 0 && requests.remove(request))
        replacementStreak = if (request.replacement) minOf(replacementStreak + 1, MAX_REPLACEMENT_STREAK) else 0
        remainingTicks = intervalTicks
    }

    fun save(): CompoundTag = CompoundTag().apply {
        putInt("RemainingTicks", remainingTicks)
        putInt("ReplacementStreak", replacementStreak)
        put("Requests", ListTag().also { list ->
            requests.forEach { request -> list.add(CompoundTag().apply {
                putUUID("Squad", request.squad)
                putUUID("Slot", request.slot)
                putBoolean("Replacement", request.replacement)
            }) }
        })
    }

    companion object {
        private const val MAX_REPLACEMENT_STREAK = 3

        fun load(tag: CompoundTag) = BarracksRecruitmentQueue().apply {
            remainingTicks = tag.getInt("RemainingTicks").coerceAtLeast(0)
            replacementStreak = tag.getInt("ReplacementStreak").coerceIn(0, MAX_REPLACEMENT_STREAK)
            tag.getList("Requests", Tag.TAG_COMPOUND.toInt()).forEach { raw ->
                val request = raw as CompoundTag
                requests += Request(request.getUUID("Squad"), request.getUUID("Slot"), request.getBoolean("Replacement"))
            }
        }
    }
}
