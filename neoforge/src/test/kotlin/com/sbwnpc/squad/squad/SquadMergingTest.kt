package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.tactics.TacticalContact
import com.sbwnpc.squad.combat.tactics.TacticalEvent
import com.sbwnpc.squad.combat.tactics.TacticalEventSink
import com.sbwnpc.squad.combat.tactics.TacticalMember
import com.sbwnpc.squad.combat.tactics.TacticalSnapshot
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class SquadMergingTest {
    private val owner = UUID.randomUUID()
    private fun group(role: NpcClass = NpcClass.RIFLEMAN, ownerId: UUID = owner) =
        Squad(
            UUID.randomUUID(), "Squad", SquadFaction.PIG, SquadOrder.DEFEND, mutableListOf(UUID.randomUUID()),
            BlockPos(100, 64, 200), null, ownerId, originalComposition = listOf(role),
        ).apply { recruitmentRoster = RecruitmentRoster.migrate(originalComposition, members.associateWith { role }) }

    @Test
    fun `merge preserves receiver commands and only occupied places across save and selection`() {
        val from = group(NpcClass.SNIPER)
        val into = group(NpcClass.MEDIC)
        into.order = SquadOrder.ATTACK
        into.attackAmmoPolicy = AttackAmmoPolicy.PRESS_ON
        val focus = UUID.randomUUID()
        into.focusEntity = focus
        from.recruitmentRoster!!.slots += RecruitmentRoster.Slot(UUID.randomUUID(), NpcClass.RIFLEMAN)
        val tags = CompoundTag().apply {
            put(
                "Squads",
                ListTag().apply {
                    add(from.save())
                    add(into.save())
                },
            )
        }
        val manager = SquadManager.load(tags)
        SquadSelection.selectSquad(owner, from.id)
        SquadSelection.armObjective(owner, from.id)
        SquadSelection.armFocus(owner, into.id)
        assertNull(manager.mergeProblem(from.id, into.id, owner))
        manager.mergeMembership(manager.get(from.id)!!, manager.get(into.id)!!, 100)
        val loaded = SquadManager.load(manager.saveState(CompoundTag()))
        val result = loaded.get(into.id)!!
        assertNull(loaded.get(from.id))
        assertEquals(into.name, result.name)
        assertEquals(SquadOrder.ATTACK, result.order)
        assertEquals(AttackAmmoPolicy.PRESS_ON, result.attackAmmoPolicy)
        assertEquals(into.objective, result.objective)
        assertEquals(focus, result.focusEntity)
        assertEquals(into.members + from.members, result.members)
        assertEquals(listOf(NpcClass.MEDIC, NpcClass.SNIPER), result.originalComposition)
        assertEquals(2, result.recruitmentRoster!!.slots.size)
        assertTrue(result.recruitmentRoster!!.vacancies().isEmpty())
        assertEquals(into.id, SquadSelection.selectedSquad(owner))
        assertNull(SquadSelection.takeObjectiveArm(owner))
        assertNull(SquadSelection.takeFocusArm(owner))
        SquadSelection.clear(owner)
    }

    @Test
    fun `merge rejects barracks foreign owners factions and specialized crews before mutating`() {
        val from = group()
        for (into in listOf(
            group(ownerId = UUID.randomUUID()),
            group().apply { faction = SquadFaction.COW },
            group().apply { barracks = BarracksRef(Level.OVERWORLD, BlockPos.ZERO) },
            group(NpcClass.MORTAR),
            group(NpcClass.TANK_CREW),
            group(NpcClass.HELICOPTER_PILOT),
            group().apply { recruitmentRoster = null },
            from,
        )) {
            val before = from.save()
            assertNotNull(SquadMerging.problem(from, into, owner))
            assertEquals(before, from.save())
        }
        val into = group()
        from.barracks = BarracksRef(Level.OVERWORLD, BlockPos.ZERO)
        assertNotNull(SquadMerging.problem(from, into, owner))
        assertNotNull(SquadMerging.problem(null, into, owner))
    }

    @Test
    fun `merge releases tasks from both active plans before changing the receiver composition`() {
        val from = group()
        val into = group()
        val released = mutableListOf<TacticalEvent.TaskReleased>()
        val expected = mutableSetOf<UUID>()
        for (squad in listOf(from, into)) {
            squad.members.addAll(List(2) { UUID.randomUUID() })
            squad.originalComposition = List(3) { NpcClass.RIFLEMAN }
            squad.recruitmentRoster = RecruitmentRoster.migrate(squad.originalComposition, squad.members.associateWith { NpcClass.RIFLEMAN })
            squad.tactics.events.runtime = TacticalEventSink { if (it is TacticalEvent.TaskReleased) released += it }
            val origin = Vec3(0.0, 64.0, 0.0)
            squad.tactics.assess(
                squad.id,
                TacticalSnapshot(
                    10,
                    SquadOrder.DEFEND,
                    0,
                    origin,
                    origin,
                    squad.members.map { TacticalMember(it, origin, canFire = true) },
                    listOf(TacticalContact(UUID.randomUUID(), Vec3(50.0, 64.0, 0.0), 10)),
                ),
            )
            val assigned = squad.tactics.plan!!.tasks.keys
            assertFalse(assigned.isEmpty())
            expected += assigned
        }
        SquadMerging.transfer(from, into, 20)
        assertNull(from.tactics.plan)
        assertNull(into.tactics.plan)
        assertEquals(expected, released.map { it.member }.toSet())
        assertEquals(expected.size, released.size)
        assertTrue(released.all { it.trigger == "squad_merged" })
        assertEquals(6, into.members.size)
    }
}
