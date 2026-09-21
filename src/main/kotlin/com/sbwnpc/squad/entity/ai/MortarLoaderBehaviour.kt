package com.sbwnpc.squad.entity.ai

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.item.projectile.MortarShellItem
import com.mojang.datafixers.util.Pair
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.ai.memory.MemoryModuleType
import net.minecraft.world.entity.ai.memory.MemoryStatus
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.tslat.smartbrainlib.api.core.behaviour.ExtendedBehaviour

/**
 * SmartBrain migration (finishing the plan's "full migration, not partial" decision) — direct port
 * of the old `MortarLoaderGoal` onto `ExtendedBehaviour`, placed in `NpcEntity.getCoreTasks()` for
 * the same reason as [MortarOperatorBehaviour] — a loader keeps resupplying regardless of the
 * current Fight/Idle activity, same as the old goal ran unconditionally at priority 1.
 *
 * Simplified ammo logistics (no carried shells / resupply points): stand near a mortar and it
 * stays topped up. Separate claim from the operator so both can post at the same mortar.
 */
class MortarLoaderBehaviour : ExtendedBehaviour<NpcEntity>() {

    // ExtendedBehaviour defaults to a 60-tick runtime cap (confirmed via javap — see
    // SeekCoverBehaviour's doc comment for the full story); resupplying a mortar is meant to be
    // indefinite, not force-interrupted and immediately re-evaluated every 3 seconds.
    init {
        noTimeout()
    }

    private var mortar: MortarEntity? = null
    private var nextCheckTick = 0
    private var nextMortarSearchTick = 0
    /** The squadmate currently carrying the crew's mortar, if it is being displaced. */
    private var carrier: NpcEntity? = null

    companion object {
        private const val SEARCH_RANGE = 30.0
        private const val MORTAR_SEARCH_INTERVAL_TICKS = 40
        private const val START_CHECK_INTERVAL_TICKS = 5
        private const val SELF_DEFENSE_RANGE_SQR = 6.0 * 6.0
        /** How close the loader keeps to an operator carrying the mortar. */
        private const val FOLLOW_DISTANCE_SQR = 5.0 * 5.0
        private const val FOLLOW_SPEED = 1.0
    }

    override fun getMemoryRequirements(): List<Pair<MemoryModuleType<*>, MemoryStatus>> = emptyList()

    private fun eligible(entity: NpcEntity): Boolean {
        if (entity.npcClass != NpcClass.MORTAR_LOADER) return false
        val personalThreat = entity.target?.takeIf { it.isAlive && entity.distanceToSqr(it) <= SELF_DEFENSE_RANGE_SQR }
        if (personalThreat != null) return false
        // A dug-in loader (badly hurt, took cover) must stay put like everything else that respects
        // NpcEntity.diggedIn (PM review finding — this was the one Core task that still didn't) —
        // resupplying the mortar can wait until it's healed/no longer holding.
        if (entity.diggedIn) return false
        if (entity.vehicleTransport) return false

        // The crew is displacing (MortarOperatorBehaviour broke the tube down): stay with the
        // operator rather than falling back to the squad's own movement, which would walk the
        // loader all the way to the objective and leave the mortar unsupplied behind it.
        carrier = carryingSquadmate(entity)
        if (carrier != null) return true

        val current = mortar
        if (current != null && current.isAlive && !current.isWreck && !MortarClaims.isLoaderClaimedByOther(current.uuid, entity.uuid)) return true

        val level = entity.level() as? ServerLevel ?: return false
        // Same every-tick-search problem as MortarOperatorBehaviour — see its MORTAR_SEARCH_INTERVAL_TICKS.
        if (entity.tickCount < nextMortarSearchTick) return false
        nextMortarSearchTick = entity.tickCount + MORTAR_SEARCH_INTERVAL_TICKS
        val found = level.getEntitiesOfClass(
            MortarEntity::class.java, AABB.ofSize(entity.position(), SEARCH_RANGE * 2, SEARCH_RANGE * 2, SEARCH_RANGE * 2)
        ).firstOrNull {
            it.isAlive && !it.isWreck && entity.distanceToSqr(it) <= SEARCH_RANGE * SEARCH_RANGE &&
                !MortarClaims.isLoaderClaimedByOther(it.uuid, entity.uuid)
        } ?: return false

        MortarClaims.claimLoader(found.uuid, entity.uuid)
        mortar = found
        // Non-"intelligent" mortars auto-fire on any inventory change (MortarEntity.setChanged),
        // simulating a dumb mortar that discharges as soon as a shell is dropped in. We drive
        // firing ourselves through MortarOperatorBehaviour's own aim/cooldown checks, so flip this
        // on (same flag a player sets by binding a Monitor item) to stop setItem() below from
        // triggering an uncontrolled shot every time we resupply.
        found.intelligent = true
        return true
    }

    private val startCheck = StartCheckThrottle(START_CHECK_INTERVAL_TICKS)
    override fun checkExtraStartConditions(level: ServerLevel, entity: NpcEntity): Boolean =
        startCheck.check(entity) { eligible(entity) }
    override fun shouldKeepRunning(entity: NpcEntity): Boolean = eligible(entity)

    /** A squadmate with the mortar on its back. Only ever a handful of members to check, and only
     *  when there is no mortar in range to claim. */
    private fun carryingSquadmate(entity: NpcEntity): NpcEntity? {
        val level = entity.level() as? ServerLevel ?: return null
        val members = entity.currentSquad()?.members ?: return null
        return members.asSequence()
            .mapNotNull { level.getEntity(it) as? NpcEntity }
            .firstOrNull { it !== entity && it.isAlive && it.carryingMortar }
    }

    override fun stop(entity: NpcEntity) {
        startCheck.reset()
        MortarClaims.releaseLoader(entity.uuid)
        mortar = null
        carrier = null
    }

    override fun tick(entity: NpcEntity) {
        carrier?.takeIf { it.isAlive && it.carryingMortar }?.let { with ->
            if (entity.distanceToSqr(with) > FOLLOW_DISTANCE_SQR) {
                entity.navigateTo(with.x, with.y, with.z, FOLLOW_SPEED)
            } else {
                entity.navigation.stop()
            }
            return
        }
        val m = mortar ?: return
        val dist = entity.position().distanceTo(m.position())
        if (dist > 2.5) {
            entity.navigateTo(m.x, m.y, m.z, 1.0)
            return
        }
        entity.navigation.stop()
        entity.lookAt(m, 30f, 30f)

        if (entity.tickCount < nextCheckTick) return
        nextCheckTick = entity.tickCount + 60

        // The mortar's own container caps this slot at 1 shell (VehicleEntity.maxStackSize
        // override), not a real stack — it holds exactly one round in the tube at a time. Only
        // touch it when actually empty; re-setting a full slot every check just spams SBW's
        // "exceeding max stack size" clamp warning for nothing.
        val loaded = m.getItems().firstOrNull()
        if (loaded == null || loaded.isEmpty || loaded.item !is MortarShellItem) {
            m.setItem(0, ItemStack(ModItems.MORTAR_SHELL.get(), 1))
        }
    }
}
