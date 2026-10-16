package com.sbwnpc.squad.combat.tactics

/**
 * Preconditions for phase tests: assume the listed NPCs already reached their assigned front posts.
 * This fixture changes snapshot coordinates and selected endpoints directly. It runs no world queries,
 * navigation, movement or firing, and cannot establish whether those endpoints are usable in-game.
 * Deployment and failure scenarios must supply their own coordinates rather than call this helper.
 */
internal fun TacticalSnapshot.assumingFrontArrival(plan: TacticalPlan): TacticalSnapshot =
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
