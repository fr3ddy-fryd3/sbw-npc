package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.planning.ExecutionStatus
import com.sbwnpc.squad.ai.tactics.Engage
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.npc.SquadFaction
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** A mortar crew with an enemy at arm's length defends itself instead of serving its tube (#258, #286). */
@PrefixGameTestTemplate(false)
object MortarSelfDefenceGameTests {
    /**
     * An enemy at arm's length: the operator fights him off through his engage, his crew duty set
     * aside (#258), and really shoots him.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "mortar-self-defence")
    fun mortarmanDefendsHimselfAtArmsLength(helper: GameTestHelper) = defendsHimself(helper, 0)

    /**
     * The same enemy coming once the crew has served its tube a while (#286): the defence failed
     * on some runs, back at the tube, and seemed to hang on where the crew's plan stood when the
     * enemy came. Here it comes mid-way through laying and firing.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 700, batch = "mortar-self-defence-serving")
    fun mortarmanServingHisTubeDefendsHimself(helper: GameTestHelper) = defendsHimself(helper, 60)

    /** As [mortarmanServingHisTubeDefendsHimself], later still, with salvos under way. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 800, batch = "mortar-self-defence-firing")
    fun mortarmanFiringSalvosDefendsHimself(helper: GameTestHelper) = defendsHimself(helper, 160)

    private fun defendsHimself(helper: GameTestHelper, after: Long) {
        val scene = MortarCrewGameTests.Scene.create(helper)
        var enemy: NpcEntity? = null
        var engaged = false
        var crewedAt: Long? = null
        // Shots before the enemy came are not his defence.
        val shotBefore = HashMap<NpcEntity, Int>()
        cleanup(helper) {
            scene.close()
            enemy?.discard()
        }
        helper.onEachTick {
            if (crewedAt == null && MortarClaims.activeCrew(scene.mortar) == 2) crewedAt = helper.tick
            if (enemy == null && crewedAt?.let { helper.tick - it >= after } == true) {
                enemy = enemyAtArmsLength(helper, scene)
                scene.crew.forEach { shotBefore[it] = it.lastShotTick }
            }
            val man = enemy?.let { foe -> scene.crew.firstOrNull { it.target === foe } } ?: return@onEachTick
            val running = NativeTactics.reports(man).values.filter { it.status == ExecutionStatus.RUNNING }.map { it.ticket.identity }
            if (running.any(Engage::isUnit)) {
                helper.assertTrue(running.none { it.startsWith("crew:") }, "His crew duty still runs beside his engage: $running")
                engaged = true
            }
            val foe = enemy!!
            // He has to fight the man off, not hand him back to the tube once the engage starts.
            val fought = man.lastShotTick > shotBefore.getValue(man) || foe.health < foe.maxHealth || !foe.isAlive
            if (engaged && fought) helper.succeed()
        }
    }

    /** An enemy 4 blocks from the operator, already his target. */
    private fun enemyAtArmsLength(helper: GameTestHelper, scene: MortarCrewGameTests.Scene): NpcEntity {
        val operator = scene.crew.first { it.uuid == MortarClaims.operator(scene.mortar.uuid) }
        return AttackDeploymentGameTests.spawn(helper, helper.relativeVec(operator.position()).add(4.0, 0.0, 0.0), SquadFaction.COW).also { operator.target = it }
    }
}
