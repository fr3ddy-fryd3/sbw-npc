package com.sbwnpc.squad.experience

import net.minecraft.nbt.CompoundTag
import java.util.UUID

/** One finite reward budget per target life, shared by all contributors and weapon types. */
class ExperienceLedger(private val tag: CompoundTag) {
    fun damage(fraction: Double, budget: Double, shares: Map<UUID, Double>): Map<UUID, Double> {
        if (!fraction.isFinite() || fraction <= 0 || shares.isEmpty() || tag.getBoolean("Finished")) return emptyMap()
        val amount = minOf(fraction * budget * 0.7, (budget * 0.7 - tag.getDouble("Paid")).coerceAtLeast(0.0))
        val eligible = shares.filterValues { it.isFinite() && it > 0 }
        val total = eligible.values.sum()
        if (amount <= 0 || total <= 0) return emptyMap()
        val credits = tag.getCompound("Credits")
        val rewards = eligible.mapValues { amount * it.value / total }
        rewards.forEach { (id, xp) -> credits.putDouble(id.toString(), credits.getDouble(id.toString()) + xp) }
        tag.put("Credits", credits)
        tag.putDouble("Paid", tag.getDouble("Paid") + amount)
        return rewards
    }

    /** Damage retains its ordinary 70% cap; a larger kill bounty is unlocked only on death. */
    fun finish(budget: Double, killMultiplier: Double = 1.0): Map<UUID, Double> {
        if (tag.getBoolean("Finished")) return emptyMap()
        tag.putBoolean("Finished", true)
        val credits = tag.getCompound("Credits")
        val sum = credits.allKeys.sumOf { credits.getDouble(it) }
        if (sum <= 0) return emptyMap()
        val completionBudget = budget * (killMultiplier - 0.7)
        return credits.allKeys.mapNotNull { key ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { it to completionBudget * credits.getDouble(key) / sum }
        }.toMap()
    }
}
