package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.goal.Goal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import java.util.EnumSet

/**
 * Simplified ammo logistics (no carried shells / resupply points): stand near a mortar and it
 * stays topped up. Separate claim from the operator so both can post at the same mortar.
 */
class MortarLoaderGoal(private val mob: NpcEntity) : Goal() {

    private var mortar: MortarEntity? = null
    private var nextCheckTick = 0

    init {
        setFlags(EnumSet.of(Flag.MOVE))
    }

    override fun canUse(): Boolean {
        if (mob.npcClass != NpcClass.MORTAR_LOADER) return false
        if (mob.target != null) return false

        val current = mortar
        if (current != null && current.isAlive && !MortarClaims.isLoaderClaimedByOther(current.uuid, mob.uuid)) return true

        val level = mob.level() as? ServerLevel ?: return false
        val found = level.getEntitiesOfClass(
            MortarEntity::class.java, AABB.ofSize(mob.position(), SEARCH_RANGE, SEARCH_RANGE, SEARCH_RANGE)
        ).firstOrNull { !MortarClaims.isLoaderClaimedByOther(it.uuid, mob.uuid) } ?: return false

        MortarClaims.claimLoader(found.uuid, mob.uuid)
        mortar = found
        return true
    }

    override fun canContinueToUse() = canUse()

    override fun stop() {
        MortarClaims.releaseLoader(mob.uuid)
        mortar = null
    }

    override fun tick() {
        val m = mortar ?: return
        val dist = mob.position().distanceTo(m.position())
        if (dist > 2.5) {
            mob.navigation.moveTo(m.x, m.y, m.z, 1.0)
            return
        }
        mob.navigation.stop()
        mob.lookAt(m, 30f, 30f)

        if (mob.tickCount < nextCheckTick) return
        nextCheckTick = mob.tickCount + 60

        val loaded = m.getItems().firstOrNull()
        if (loaded == null || loaded.item !is MortarShellItem || loaded.count < RESUPPLY_THRESHOLD) {
            m.setItem(0, ItemStack(ModItems.MORTAR_SHELL.get(), RESUPPLY_STACK))
        }
    }

    companion object {
        private const val SEARCH_RANGE = 30.0
        private const val RESUPPLY_THRESHOLD = 4
        private const val RESUPPLY_STACK = 16
    }
}
