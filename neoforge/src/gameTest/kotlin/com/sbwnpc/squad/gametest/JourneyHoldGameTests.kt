package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.GroupOrders
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/**
 * An attack ordered from afar meets the defence on the way in, then loses sight of it. In the forest
 * replay of 10.11 the defenders dropped in and out of view and the squad went back to its march each
 * time, walking towards them with no tactics and starting its post search over on the next contact
 * (#312).
 */
@PrefixGameTestTemplate(false)
object JourneyHoldGameTests {
    /** Once its attack has met the defence, the squad does not go back to the march when the enemy hides. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 800, batch = "journey-hold")
    fun attackThatMetTheDefenceDoesNotMarchAgain(helper: GameTestHelper) {
        AmmunitionTacticsGameTests.floor(helper)
        val manager = SquadManager.get(helper.level)
        val men = (0 until 4).map { AttackDeploymentGameTests.spawn(helper, Vec3(8.5, 1.0, 60.5 + it * 2), SquadFaction.PIG) }
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(45.5, 1.0, 64.5), SquadFaction.COW).apply {
            isNoAi = true
            getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 10000.0
            health = maxHealth
        }
        val oldGroups = DebugFlags.enabledGroups
        DebugFlags.enabledGroups = oldGroups + setOf(LogGroup.ORDER)
        AmmunitionTacticsGameTests.cleanup(helper) {
            DebugFlags.enabledGroups = oldGroups
            manager.deleteSquad(helper.level, squad.id)
            (men + enemy).forEach(NpcEntity::discard)
        }
        val objective = helper.absolutePos(BlockPos(152, 1, 64))
        GroupOrders.apply(helper.level, manager, listOf(squad.id), SquadOrder.ATTACK, objective.x, objective.z)
        val goal = Vec3.atBottomCenterOf(objective)
        var engaged = false
        helper.onEachTick {
            val living = men.filter { it.isAlive }
            if (helper.tick < HIDDEN_AT && living.any { it.target == enemy }) engaged = true
            if (helper.tick == HIDDEN_AT) {
                helper.assertTrue(engaged, "The squad never engaged the enemy on its way in")
                val median = living.map { it.position().distanceTo(goal) }.sorted()[living.size / 2]
                helper.assertTrue(median > FAR, "The squad was only ${"%.0f".format(median)} blocks out when the enemy hid; the scene needs it far")
                enemy.discard()
            }
            if (helper.tick > HIDDEN_AT) {
                helper.assertTrue(living.none(NativeTactics::travelling), "The squad went back to its march once the enemy it had fought hid")
            }
            if (helper.tick == HIDDEN_AT + WATCH) helper.succeed()
        }
    }

    /** When the enemy hides: long enough for the squad to stop for it and the attack to see it. */
    private const val HIDDEN_AT = 200L

    /** How long after that the squad must stay off the march: past the 10 s contact memory. */
    private const val WATCH = 400L

    /** [com.sbwnpc.squad.entity.ai.SquadTransit.TRANSPORT_DISTANCE], below which a squad never travels. */
    private const val FAR = 100.0
}
