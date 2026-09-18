package com.sbwnpc.squad.combat

import net.minecraft.world.phys.Vec3
import java.util.UUID

/** A transient snapshot carried through SBW's delayed-shot callback; never saved or sent. */
internal class AutonomousShotTarget(position: Vec3, val targetId: UUID?, val droneTarget: Boolean) :
    Vec3(position.x, position.y, position.z) {
    fun position(): Vec3 = Vec3(x, y, z)
}
