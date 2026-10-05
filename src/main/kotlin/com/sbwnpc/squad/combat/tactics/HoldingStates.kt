package com.sbwnpc.squad.combat.tactics

import com.sbwnpc.squad.combat.SquadFormation
import net.minecraft.world.phys.Vec3

class FollowOrderState : TacticalState() {
    override val stability = TacticalStability(passive = true, quickReplacement = true)
    override fun assign(context: TacticalContext, retainCover: Boolean, trigger: String) =
        context.tasks.replace(emptyMap(), context.view.now, trigger)
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.cover(member)
}

class HoldHeightState : TacticalState() {
    override val positions = HoldHeightPositions
    override val supportsDefensiveOverwatch = true
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.cover(member)
}

class ReorientState : TacticalState() {
    override val supportsDefensiveOverwatch = true
    override val stability = TacticalStability(passive = true, keepsThreatSector = true)
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask = layout.intent(TacticalJob.COVER, member.position,
        layout.view.visible.minByOrNull { it.position.distanceToSqr(member.position) }?.position ?: layout.focus)

    override fun refreshSectors(context: TacticalContext) {
        for (member in context.view.members) {
            val task = context.plan.tasks[member.id] ?: continue
            val focus = context.view.visible.minByOrNull { it.position.distanceToSqr(member.position) }?.position ?: continue
            if (task.position == null && task.focus?.distanceTo(focus)?.let { it > 8.0 } == true) task.search = null
            task.focus = focus
        }
    }
}

class RepelState : TacticalState() {
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.intent(TacticalJob.COVER,
        if (member.role in TacticalLayout.SUPPORT_ROLES) member.position.subtract(layout.forward.scale(5.0))
        else member.position.add(layout.lateral(index).scale(0.5)))
}

class AntiArmourState : TacticalState() {
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        if (member.rockets) return layout.intent(TacticalJob.ANTI_ARMOUR,
            member.position.add(layout.side.scale(if (index % 2 == 0) 5.0 else -5.0)))
        val focus = layout.view.visible.filter { !it.armoured }.minByOrNull { it.position.distanceToSqr(member.position) }?.position
        return layout.intent(if (focus != null) TacticalJob.COVER else TacticalJob.OBSERVE,
            member.position.subtract(layout.forward.scale(4.0)).add(layout.lateral(index)), focus)
    }
}

class EvadeState : TacticalState() {
    override val stability = TacticalStability(canLeaveImmediately = true)
    override fun guardsPatient() = false
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val angle = index * Math.PI * 2.0 / layout.view.members.size.coerceAtLeast(1)
        return layout.intent(TacticalJob.REGROUP, member.position.add(Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)).scale(8.0)))
    }
}

class ConsolidateState : TacticalState() {
    override val supportsDefensiveOverwatch = true
    override val stability = TacticalStability(passive = true, quickReplacement = true)
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int): TacticalTask {
        val angle = index * Math.PI * 2.0 / layout.view.members.size.coerceAtLeast(1)
        return layout.intent(TacticalJob.OBSERVE, (layout.view.home ?: layout.view.center)
            .add(Vec3(kotlin.math.cos(angle), 0.0, kotlin.math.sin(angle)).scale(SquadFormation.perimeterRadius(layout.view.members.size))))
    }
}

class ReturnFireState : TacticalState() {
    override val supportsDefensiveOverwatch = true
    override val stability = TacticalStability(passive = true, quickReplacement = true)
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.intent(TacticalJob.OBSERVE,
        layout.view.center.subtract(layout.forward.scale(5.0)).add(layout.lateral(index)))
}

class SearchState : TacticalState() {
    override val stability = TacticalStability(passive = true, quickReplacement = true)
    override fun task(layout: TacticalLayout, member: TacticalMember, index: Int) = layout.intent(
        if (index < 2) TacticalJob.SEARCH else TacticalJob.OBSERVE,
        if (index < 2) layout.focus.add(layout.side.scale(if (index % 2 == 0) 5.0 else -5.0)) else member.position)
}
