package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.SidearmKit
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.AttackAmmoPolicy
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadReports
import com.sbwnpc.squad.util.Terrain
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.phys.Vec3

/** Abort through the saved squad order: refilling a rifle cannot silently restart this attack. */
internal object AttackAmmoWithdrawal {
    const val DISTANCE = 100.0

    fun apply(squad: Squad, level: ServerLevel, view: TacticalSnapshot): Boolean {
        if (!view.offensive || squad.attackAmmoPolicy == AttackAmmoPolicy.PRESS_ON) return false
        // Never infer a squad-wide shortage from only the loaded/nearby part of its roster.
        val members = squad.members.mapNotNull { level.getEntity(it) as? NpcEntity }.filter { it.isAlive }
        if (members.size != squad.members.size || members.any { it.vehicle != null }) return false
        val infantry = members.filter {
            it.npcClass !in NON_INFANTRY && Ports.guns.isGun(it.primaryGun())
        }
        if (infantry.isEmpty()) return false
        val empty = infantry.count { !SidearmKit.hasPrimaryAmmo(it) }
        if (empty * 2 < infantry.size) return false

        val threat = view.visible.maxByOrNull { it.priority }?.position
            ?: view.contacts.maxByOrNull { it.seenAt }?.position
            ?: view.incoming.firstOrNull() ?: view.home
        val direction = away(view.center, threat, infantry.first().lookAngle)
        val desired = view.center.add(direction.scale(DISTANCE))
        val destination = rally(level, infantry.first(), view.center, desired, direction)
        val manager = SquadManager.get(level)
        manager.withdrawForAmmo(squad.id)
        manager.setObjective(level, squad.id, BlockPos.containing(destination))
        SquadReports.attackFailedAmmo(level.server, squad, empty, infantry.size)
        DebugFlags.log(
            LogGroup.ORDER,
            "[tactics] {} squad={} event=attack_aborted reason=primary_ammo_exhausted empty={} infantry={} origin={} rally={} distance={}",
            squad.name, squad.id, empty, infantry.size, view.center, destination, destination.subtract(view.center).horizontalDistance(),
        )
        return true
    }

    private fun away(center: Vec3, threat: Vec3?, facing: Vec3): Vec3 {
        val delta = (threat?.let { center.subtract(it) } ?: facing.scale(-1.0)).multiply(1.0, 0.0, 1.0)
        return if (delta.lengthSqr() > 0.01) delta.normalize() else Vec3(0.0, 0.0, -1.0)
    }

    /** Keep the 100-block retreat depth, adjusting sideways for a blocked or flooded column. */
    private fun rally(level: ServerLevel, reference: NpcEntity, center: Vec3, desired: Vec3, direction: Vec3): Vec3 {
        val side = Vec3(-direction.z, 0.0, direction.x)
        for (offset in listOf(0.0, -4.0, 4.0, -8.0, 8.0, -12.0, 12.0)) {
            val raw = desired.add(side.scale(offset))
            val column = BlockPos.containing(raw)
            if (!level.chunkSource.hasChunk(column.x shr 4, column.z shr 4)) continue
            val point = Terrain.feetAt(level, raw, 32) {
                level.noCollision(reference, reference.getDimensions(reference.pose).makeBoundingBox(it))
            } ?: continue
            if (point.subtract(center).dot(direction) >= DISTANCE - 1.0) return point
        }
        // The existing order chunk loader and navigator handle a destination not loaded yet.
        return desired
    }

    private val NON_INFANTRY = setOf(NpcClass.TANK_CREW, NpcClass.HELICOPTER_PILOT, NpcClass.HELICOPTER_GUNNER, NpcClass.MORTAR)
}
