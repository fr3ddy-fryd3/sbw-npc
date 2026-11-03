package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.tactics.DutyUtility
import com.sbwnpc.squad.combat.DetectionSightline
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** A wounded man in an attack takes cover by his squad's plan instead of the front row (#262). */
@PrefixGameTestTemplate(false)
object WoundedCoverGameTests {
    /**
     * A wounded rifleman in an attack takes cover by his squad's plan instead of the front row
     * (#262): at 30 % health he fires from behind the wall a few blocks from him while three
     * fit riflemen move up. With [hit] the enemy also hits him now and then; without, nothing
     * fires at him and the shelter is from the enemy he faces.
     */
    private fun woundedTakesCover(helper: GameTestHelper, name: String, hit: Boolean) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val wounded = scene.spawn(Vec3(20.5, 1.0, 57.5))
        val fit = listOf(60.5, 64.5, 68.5).map { scene.spawn(Vec3(20.5, 1.0, it)) }
        val enemy = scene.enemy(Vec3(90.5, 1.0, 57.5))
        PlannedCoverGameTests.EXACT_WALL.forEach { z -> for (y in 1..2) helper.setBlock(BlockPos(24, y, z), Blocks.STONE) }
        wounded.health = wounded.maxHealth * WOUNDED_SHARE
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(wounded.spawnFaction), (listOf(wounded) + fit).map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, enemy.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        var hidden = 0
        var chose = false
        val samples = ArrayList<String>()
        helper.onEachTick {
            val now = helper.tick
            val woundDue = hit && now >= WOUND_FROM && now % WOUND_EVERY == 0L
            if (woundDue && wounded.isAlive) Ports.guns.inHand(enemy)!!.inflictHit(wounded, WOUND_DAMAGE)
            if (DecisionTrace.decisionsOf(wounded.uuid).any { it.event == "duty" && "<- ${DutyUtility.TAKE_COVER}" in it.detail }) chose = true
            if (!DetectionSightline.visible(helper.level, enemy.eyePosition, wounded.eyePosition, wounded)) hidden++
            val furthestFit = fit.maxOf { helper.relativeVec(it.position()).x }
            if (now % 25L == 0L) {
                samples += "$now ${helper.relativeVec(wounded.position())} hp=${wounded.health}/${wounded.maxHealth} hidden=$hidden ${DecisionTrace.decisionsOf(wounded.uuid).filter { it.event == "duty" || it.event == "plan" }.takeLast(2)}"
                scene.result("RUNNING", samples.joinToString("\n"))
            }
            if (now >= WOUNDED_CHECK_TICK) {
                helper.assertTrue(wounded.isAlive, "The wounded man died")
                helper.assertTrue(chose, "His duty never came from take_cover: ${DecisionTrace.decisionsOf(wounded.uuid).filter { it.event == "duty" }}")
                helper.assertTrue(hidden >= WOUNDED_CHECK_TICK / 2, "He was out of the enemy's line for only $hidden of $now ticks")
                helper.assertTrue(helper.relativeVec(wounded.position()).x < furthestFit, "The wounded man went as far forward as the fit ones")
                scene.result("PASS", "tick=$now hidden=$hidden")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "wounded-cover-hit")
    fun woundedRiflemanUnderFireTakesCoverInsteadOfTheFrontRow(helper: GameTestHelper) = woundedTakesCover(helper, "wounded-cover-hit", hit = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 500, batch = "wounded-cover-quiet")
    fun woundedRiflemanTakesCoverFromTheEnemyHeFacesWithoutIncomingFire(helper: GameTestHelper) = woundedTakesCover(helper, "wounded-cover-quiet", hit = false)

    /** The wounded man's share of his health: under `DutyUtility.WOUNDED`. */
    private const val WOUNDED_SHARE = 0.3f

    /** From this tick the enemy hits him. */
    private const val WOUND_FROM = 20L

    /** A light hit every 3 s keeps him under fire without killing him in the scene. */
    private const val WOUND_EVERY = 60L
    private const val WOUND_DAMAGE = 0.2f

    /** By then the fit men have moved up and he has been in cover for a while. */
    private const val WOUNDED_CHECK_TICK = 400L
}
