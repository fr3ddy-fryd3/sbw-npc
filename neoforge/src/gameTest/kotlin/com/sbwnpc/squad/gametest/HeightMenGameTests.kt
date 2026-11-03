package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.tactics.DutyUtility
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** A sniper and a machine gunner in an attack take a rise by the front, and keep the line where there is none (#262). */
@PrefixGameTestTemplate(false)
object HeightMenGameTests {
    /** The ground a height-men scene stands on: a flat field, a hill they can walk up, or a pillar they cannot. */
    private enum class Ground { FLAT, HILL, PILLAR }

    /**
     * A sniper and a machine gunner attacking with four riflemen take the rise by the front (#262):
     * a hill stepped one block at a time, 6 high, between them and the enemy. On flat ground
     * they keep their places in the line, and a sheer pillar, which the heightmap offers as a rise
     * too, is refused once they find no post on it, and they go back to the line.
     */
    private fun heightMen(helper: GameTestHelper, name: String, ground: Ground) {
        build(helper, ground)
        val scene = CombatIdleGameTests.Scene(helper, name)
        val manager = SquadManager.get(helper.level)
        val heavy = listOf(scene.spawn(Vec3(14.5, 1.0, 60.5), NpcClass.SNIPER), scene.spawn(Vec3(14.5, 1.0, 64.5), NpcClass.MACHINE_GUNNER))
        val riflemen = listOf(56.5, 60.5, 64.5, 68.5).map { scene.spawn(Vec3(10.5, 1.0, it)) }
        val enemy = scene.enemy(Vec3(110.5, 1.0, 64.5))
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, (heavy + riflemen).map { it.uuid })
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, enemy.blockPosition())
        manager.setFocus(squad.id, enemy.uuid)
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val chose = HashSet<UUID>()
        var highest = 0.0
        val start = heavy.map { it.position() }
        helper.onEachTick {
            val now = helper.tick
            heavy.filter { man -> DecisionTrace.decisionsOf(man.uuid).any { it.event == "duty" && "<- ${DutyUtility.OBSERVE_HEIGHT}" in it.detail } }.forEach { chose += it.uuid }
            highest = maxOf(highest, heavy.maxOf { helper.relativeVec(it.position()).y })
            if (now % 50L == 0L) scene.result("RUNNING", "tick=$now chose=${chose.size} highest=$highest at=${heavy.map { helper.relativeVec(it.position()) }}")
            if (ground == Ground.HILL && chose.size == heavy.size && highest >= HILL_HEIGHT - 1) {
                scene.result("PASS", "tick=$now highest=$highest")
                helper.succeed()
            }
            if (ground != Ground.HILL && now >= FLAT_CHECK_TICK) {
                checkLine(helper, ground, heavy, start, chose)
                scene.result("PASS", "tick=$now chose=${chose.size}")
                helper.succeed()
            }
        }
    }

    /** Clears the field and raises [ground] on it. */
    private fun build(helper: GameTestHelper, ground: Ground) {
        AmmunitionTacticsGameTests.floor(helper)
        fill(helper, 0..FIELD_X, FIELD_Z, { 1..FIELD_CLEAR }, Blocks.AIR)
        when (ground) {
            Ground.FLAT -> Unit

            // Stepped one block at a time up to its top and down again.
            Ground.HILL -> fill(helper, HILL_FROM..HILL_TO, HILL_Z, { x -> 1..minOf(x - HILL_FROM, HILL_TO - x, HILL_HEIGHT) }, Blocks.STONE)

            Ground.PILLAR -> fill(helper, PILLAR_X, PILLAR_Z, { 1..PILLAR_HEIGHT }, Blocks.STONE)
        }
    }

    /** Sets [block] over [xs] by [zs], each column up through [heights] of its x. */
    private fun fill(helper: GameTestHelper, xs: IntRange, zs: IntRange, heights: (Int) -> IntRange, block: Block) {
        for (x in xs) {
            val ys = heights(x)
            for (z in zs) for (y in ys) helper.setBlock(BlockPos(x, y, z), block)
        }
    }

    /** Off the hill: no height kept, none taken on flat ground, and the height men came up to the line. */
    private fun checkLine(helper: GameTestHelper, ground: Ground, heavy: List<NpcEntity>, start: List<Vec3>, chose: Set<UUID>) {
        val duties = heavy.map { man -> DecisionTrace.decisionsOf(man.uuid).lastOrNull { it.event == "duty" }?.detail }
        if (ground == Ground.FLAT) helper.assertTrue(chose.isEmpty(), "A height man took a height on flat ground")
        helper.assertTrue(duties.none { it != null && "<- ${DutyUtility.OBSERVE_HEIGHT}" in it }, "A height man still held a height he cannot climb: $duties")
        helper.assertTrue(heavy.indices.all { heavy[it].position().x - start[it].x >= LINE_ADVANCE }, "The height men did not go up to the line: ${heavy.map { helper.relativeVec(it.position()) }}")
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "height-men-hill")
    fun sniperAndGunnerInAnAttackTakeTheRiseByTheFront(helper: GameTestHelper) = heightMen(helper, "height-men-hill", Ground.HILL)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "height-men-flat")
    fun sniperAndGunnerOnFlatGroundKeepTheirPlacesInTheLine(helper: GameTestHelper) = heightMen(helper, "height-men-flat", Ground.FLAT)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900, batch = "height-men-pillar")
    fun sniperAndGunnerRefuseAPillarTheyCannotClimbAndJoinTheLine(helper: GameTestHelper) = heightMen(helper, "height-men-pillar", Ground.PILLAR)

    /** The hill the height men are to take: its foot and far foot along x, and its width. */
    private const val HILL_FROM = 30
    private const val HILL_TO = 58
    private const val HILL_HEIGHT = 6
    private val HILL_Z = 44..84

    /** A sheer pillar by the front, 8 high, that no man can climb. */
    private val PILLAR_X = 40..42
    private val PILLAR_Z = 62..64
    private const val PILLAR_HEIGHT = 8

    /** The field cleared of the arena's own blocks, so flat is flat. */
    private const val FIELD_X = 130
    private val FIELD_Z = 30..100
    private const val FIELD_CLEAR = 12

    /** By then a height man has gone up to the line, and been given a height if any was offered. */
    private const val FLAT_CHECK_TICK = 500L

    /** How far towards the enemy a height man in the line has come from where he started. */
    private const val LINE_ADVANCE = 10.0
}
