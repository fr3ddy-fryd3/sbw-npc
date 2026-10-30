package com.sbwnpc.squad.combat

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModMemories
import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.LivingEntity
import net.tslat.smartbrainlib.util.BrainUtils

/**
 * Pins a man who cannot hurt an armoured vehicle that has a line to him (#237): he takes cover
 * from it ([com.sbwnpc.squad.entity.ai.SeekCoverBehaviour]) and fights its infantry from there,
 * instead of standing in its line of fire holding a target he may not shoot.
 *
 * The vehicles are the ones his squad knows of, so a vehicle nobody has noticed pins nobody; the
 * line is from the crewman's eye to his, the line the vehicle's guns would fire along. A medic is
 * left out (owner, 30.10): he keeps treating. Once pinned he stays so while the vehicle is known to
 * his squad and in range, and [PIN_TICKS] after.
 */
object ArmourCover {
    const val PIN_TICKS = 100

    fun check(npc: NpcEntity, level: ServerLevel) {
        if (npc.vehicle != null || npc.npcClass == NpcClass.MEDIC || AntiArmourKit.loaded(npc)) return
        val squad = npc.currentSquad() ?: return
        val now = level.gameTime
        val near = squad.tactics.contacts.values.asSequence()
            .filter { it.armoured && SquadKnowledge.known(it.seenAt, it.firstSeenAt, now, npc.uuid in it.seenBy) }
            .filter { it.position.distanceToSqr(npc.position()) <= NpcEntity.DETECTION_RANGE * NpcEntity.DETECTION_RANGE }
            .mapNotNull { level.getEntity(it.id) as? LivingEntity }
            .filter { it.isAlive && AntiArmourKit.armoured(it) }
            .sortedBy { it.distanceToSqr(npc) }
        // Pinned once, he stays pinned while the armour is about: out of its sight in cover, a pin
        // that lapsed sent him back to his post in its line, and round again every few seconds.
        val crew = if (npc.armourPin != null) near.firstOrNull() else near.firstOrNull { DetectionSightline.visible(level, it.eyePosition, npc.eyePosition, npc) }
        crew ?: return
        // The crewman's eye, as suppression keeps the shooter's: cover is searched from where the guns look.
        BrainUtils.setForgettableMemory(npc, ModMemories.ARMOUR_PIN.get(), crew.eyePosition, PIN_TICKS)
    }
}
