package com.sbwnpc.squad.ai.integration

import com.sbwnpc.squad.ai.coordination.ObjectiveKey
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import com.sbwnpc.squad.ai.tactics.*
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.nbt.CompoundTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class IssuedCommandCodecTest {
    private val principal = UUID(1, 1)
    private val command = IssuedCommand(UUID(2, 2), principal, TacticalOrder.ATTACK, ObjectiveKey.Area("minecraft:overworld", -100, 70, 200))

    @Test fun `all objective kinds retain command identity authority and source across storage`() {
        val goals = listOf(null, command.goal, ObjectiveKey.Entity("minecraft:the_nether", UUID(3, 3)), ObjectiveKey.Structure("minecraft:the_end", UUID(4, 4)))
        goals.forEach { goal ->
            val issued = command.copy(goal = goal, source = CommandSource.AUTONOMOUS)
            assertEquals(issued, IssuedCommandCodec.load(IssuedCommandCodec.save(issued)))
        }
    }

    @Test fun `execution retreat and command failure survive reload without replacing original attack`() {
        val squad = Squad(UUID(5, 5), "Test", SquadFaction.PIG, SquadOrder.RETREAT, mutableListOf(), null, null, principal)
        squad.command = command
        squad.commandOutcome = CommandOutcome.FAILED
        squad.commandReason = "primary_ammo_exhausted"
        val loaded = Squad.load(squad.save())
        assertEquals(SquadOrder.RETREAT, loaded.order)
        assertEquals(command, loaded.command)
        assertEquals(CommandOutcome.FAILED, loaded.commandOutcome)
        assertEquals("primary_ammo_exhausted", loaded.commandReason)
        val foreign = squad.save().apply { put("IssuedCommand", IssuedCommandCodec.save(command.copy(principal = UUID(9, 9)))) }
        assertNull(Squad.load(foreign).command)
    }

    @Test fun `malformed intent is rejected without breaking saved world load`() {
        assertNull(IssuedCommandCodec.load(CompoundTag()))
        assertNull(IssuedCommandCodec.load(IssuedCommandCodec.save(command).apply { putString("Order", "INVALID") }))
        val entity = command.copy(goal = ObjectiveKey.Entity("minecraft:overworld", UUID(3, 3)))
        assertNull(IssuedCommandCodec.load(IssuedCommandCodec.save(entity).apply { remove("Target") }))
        assertNull(IssuedCommandCodec.load(IssuedCommandCodec.save(command).apply { putString("Dimension", "") }))
    }
}
