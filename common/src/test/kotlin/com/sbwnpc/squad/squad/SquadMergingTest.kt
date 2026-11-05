package com.sbwnpc.squad.squad

import com.sbwnpc.squad.combat.tactics.TacticalContact
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
    fun `a measured attack merged into an unmeasured attacking squad counts each man once`() {
        val from = group().apply {
            order = SquadOrder.ATTACK
            attackBase = 1
        }
        val into = group().apply { order = SquadOrder.ATTACK }
        SquadMerging.transfer(from, into, 0)
        assertEquals(2, into.attackBase)
        assertFalse(into.attackRemnant)
    }

    @Test
    fun `a squad merged into one that is not attacking keeps no base for its next attack`() {
        val from = group().apply {
            order = SquadOrder.ATTACK
            attackBase = 1
            attackRemnant = true
        }
        val into = group()
        SquadMerging.transfer(from, into, 0)
        assertNull(into.attackBase)
        assertFalse(into.attackRemnant)
    }

    @Test
    fun `merge preserves receiver commands and only occupied places across save and selection`() {
        val from = group(NpcClass.SNIPER)
        val into = group(NpcClass.MEDIC)
        into.order = SquadOrder.ATTACK
        into.attackBase = 3
        into.attackRemnant = true
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
        // The receiver's attack goes on with the other squad's man in its base.
        assertEquals(3 + from.members.size, result.attackBase)
        assertFalse(result.attackRemnant)
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
}
