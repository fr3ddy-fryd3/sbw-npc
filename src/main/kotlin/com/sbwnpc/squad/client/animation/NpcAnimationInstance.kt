package com.sbwnpc.squad.client.animation

import com.maydaymemory.mae.basic.Pose
import com.maydaymemory.mae.control.statemachine.AnimationStateMachine
import com.sbwnpc.squad.entity.NpcEntity

class NpcAnimationInstance(entity: NpcEntity) {
    val context: NpcContext = NpcContext(entity)
    private val stateMachine = AnimationStateMachine(NpcStates.INIT, context) { System.nanoTime() }

    fun tick() {
        stateMachine.tick()
        context.tick()
    }

    fun getPose(): Pose {
        return stateMachine.getPose()
    }
}
