package com.sbwnpc.squad.client.animation

import com.atsuishio.superbwarfare.client.animation.entity.BasicEntityContext
import com.sbwnpc.squad.SquadMod.Companion.loc
import com.sbwnpc.squad.entity.NpcEntity
import kotlin.math.abs

class NpcContext(entity: NpcEntity) : BasicEntityContext<NpcEntity>(entity, ANIM) {
    companion object {
        val ANIM = loc("animations/bedrock/entity/npc_placeholder.animation.json")
    }

    fun isMoving(): Boolean {
        val velocity = entity.deltaMovement
        val avgVelocity = (abs(velocity.x) + abs(velocity.z)).toFloat() / 2f
        return avgVelocity > 0.015f
    }
}
