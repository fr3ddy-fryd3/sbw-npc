package com.sbwnpc.squad.combat.tactics

/** Planner scenarios omit Minecraft navigation; explicitly finish front deployment before testing later phases. */
internal fun TacticalSnapshot.atFrontPosts(plan: TacticalPlan): TacticalSnapshot =
    copy(
        members = members.map { member ->
            val task = plan.tasks[member.id]
            val post = task?.frontage
            if (post == null || member.moving || task.job !in TacticalJob.FIRING_POSITIONS) {
                member
            } else {
                if (task.position == null || task.deployingFront(task.position!!)) task.position = post.point
                member.copy(position = post.point)
            }
        },
    )
