package com.sbwnpc.squad.entity.ai

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SafeSpawn
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Pose
import net.minecraft.world.phys.Vec3

/** Physical pack/deploy lifecycle; preserve loaded shell and accepted-shot cooldown. */
object MortarDeployment {

    /** How far to the side of the operator the tube goes back down, so the two don't overlap. */
    private const val PLACE_OFFSET = 2.0

    /** Breaks [mortar] down. The operator carries it from here until [deploy]. */
    fun pack(mortar: Entity, carrier: NpcEntity): Boolean {
        if (Ports.mortars.isFiring(mortar)) return false
        carrier.assignedMortarId = mortar.uuid
        carrier.packedMortarState = MortarCycle.pack(mortar)
        MortarClaims.releaseMortar(mortar.uuid)
        mortar.discard()
        carrier.carryingMortar = true
        return true
    }

    /**
     * Sets the mortar up next to [carrier], facing [facing], and hands it the carrier's faction so
     * it is not a neutral object anyone can use. Returns null (and keeps the mortar packed) if
     * there is nowhere alongside the operator it could stand.
     */
    fun deploy(level: ServerLevel, carrier: NpcEntity, facing: Vec3?): Entity? {
        if (!carrier.carryingMortar) return null
        val yaw = facing?.let { aim ->
            val dx = aim.x - carrier.x
            val dz = aim.z - carrier.z
            Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
        } ?: carrier.yRot

        // Off to the operator's left, so it isn't set up inside whoever is carrying it.
        val sideways = Math.toRadians(yaw.toDouble() - 90.0)
        val x = carrier.x + Math.cos(sideways) * PLACE_OFFSET
        val z = carrier.z + Math.sin(sideways) * PLACE_OFFSET

        val mortar = Ports.mortars.create(level, yaw)
        val y = SafeSpawn.findSafeY(level, x, z, carrier.blockY, mortar.getDimensions(Pose.STANDING))
            ?: return null
        mortar.moveTo(x, y, z, yaw, 0f)
        finishDeployment(level, mortar, carrier)
        return mortar
    }

    private fun finishDeployment(level: ServerLevel, mortar: Entity, carrier: NpcEntity) {
        MortarCycle.restore(mortar, carrier.packedMortarState)
        val previousId = carrier.assignedMortarId
        if (previousId != null) {
            com.sbwnpc.squad.entity.NpcRegistry.all(level).filter { it.assignedMortarId == previousId }.forEach { it.assignedMortarId = mortar.uuid }
        }
        carrier.assignedMortarId = mortar.uuid
        carrier.packedMortarState = net.minecraft.nbt.CompoundTag()
        level.addFreshEntity(mortar)
        SquadTeams.factionOf(carrier)?.let { SquadTeams.assign(mortar, it) }
        carrier.carryingMortar = false
    }

    /** A carrier that dies sets its load down rather than taking it with it. */
    fun dropOnDeath(level: ServerLevel, carrier: NpcEntity) {
        if (!carrier.carryingMortar) return
        // Straight down where it fell — no side offset, and no safe-spot search worth failing on.
        val mortar = Ports.mortars.create(level, carrier.yRot)
        mortar.moveTo(carrier.x, carrier.y, carrier.z, carrier.yRot, 0f)
        finishDeployment(level, mortar, carrier)
    }
}
