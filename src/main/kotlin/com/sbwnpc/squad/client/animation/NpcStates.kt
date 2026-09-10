package com.sbwnpc.squad.client.animation

import com.atsuishio.superbwarfare.client.animation.AnimationPlayType
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.SimpleAnimationState
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.animation.SimpleTransition

object NpcStates {
    val INIT: SimpleAnimationState<NpcContext> = SimpleAnimationState.Builder<NpcContext>()
        .evaluatePose { it.getPose() }
        .build()

    val IDLE: SimpleAnimationState<NpcContext> = SimpleAnimationState.Builder<NpcContext>()
        .evaluatePose { it.getPose() }
        .build()

    val WALK: SimpleAnimationState<NpcContext> = SimpleAnimationState.Builder<NpcContext>()
        .evaluatePose { it.getPose() }
        .build()

    val DIE: SimpleAnimationState<NpcContext> = SimpleAnimationState.Builder<NpcContext>()
        .evaluatePose { it.getPose() }
        .build()

    val INIT_TRANS: SimpleTransition<NpcContext> = SimpleTransition.Builder<NpcContext>()
        .predicate { true }
        .target(IDLE)
        .from(INIT)
        .afterTrigger { it.playAnimation("animation.npc_placeholder.idle", AnimationPlayType.LOOP) }
        .build()

    val TO_IDLE: SimpleTransition<NpcContext> = SimpleTransition.Builder<NpcContext>()
        .predicate { !it.isMoving() }
        .target(IDLE)
        .from(WALK)
        .afterTrigger { it.playAnimation("animation.npc_placeholder.idle", AnimationPlayType.LOOP) }
        .build()

    val TO_WALK: SimpleTransition<NpcContext> = SimpleTransition.Builder<NpcContext>()
        .predicate { it.isMoving() }
        .target(WALK)
        .from(IDLE)
        .afterTrigger { it.playAnimation("animation.npc_placeholder.walk", AnimationPlayType.LOOP) }
        .build()

    val TO_DIE: SimpleTransition<NpcContext> = SimpleTransition.Builder<NpcContext>()
        .predicate { it.entity.isDeadOrDying }
        .target(DIE)
        .from(IDLE, WALK)
        .afterTrigger { it.playAnimation("animation.npc_placeholder.die", AnimationPlayType.PLAY_ONCE_HOLD) }
        .build()
}
