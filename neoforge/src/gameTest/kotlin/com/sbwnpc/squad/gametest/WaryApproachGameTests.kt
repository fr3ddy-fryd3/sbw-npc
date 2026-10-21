package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.WaryApproach
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

@PrefixGameTestTemplate(false)
object WaryApproachGameTests {
    /**
     * An attack sent 180 blocks runs the first stretch and walks, wary, inside the last 100 (#148).
     * An enemy met there ends it: from then on the squad attacks at combat pace.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 3600, batch = "wary-approach")
    fun attackWalksWaryInsideTheLastHundredBlocksUntilContact(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "wary-approach")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 8).map { scene.spawn(Vec3(82.5 + it % 4 * 3, 1.0, 8.5 + it / 4 * 3)) }
        val squad = manager.create(helper.level, UUID(90, 41), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        val objective = helper.absolutePos(BlockPos(88, 1, 200))
        manager.issueOrderAt(helper.level, squad.id, SquadOrder.ATTACK, objective)
        val center = Vec3.atBottomCenterOf(objective)
        val tally = Tally()
        var enemy: com.sbwnpc.squad.entity.NpcEntity? = null
        var contactAt: Long? = null
        helper.onEachTick {
            val lead = lead(men, center)
            val distance = lead.position().subtract(center).horizontalDistance()
            if (enemy == null) men.filter { it.isAlive }.forEach { tally.count(it, center) }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} lead=${distance.toInt()} $tally contactAt=$contactAt")
            // Well inside the band and wary a while: put an enemy in its way.
            if (enemy == null && distance < 60.0 && tally.inside > 200) {
                helper.assertTrue(tally.outside > 0 && tally.outsideWary == 0, "Wary beyond ${WaryApproach.RANGE.toInt()} blocks: $tally")
                helper.assertTrue(tally.insideWary >= tally.inside * 0.8, "Not wary inside ${WaryApproach.RANGE.toInt()} blocks: $tally")
                enemy = scene.enemy(lead.position().add(0.0, 0.0, 30.0))
            }
            val foe = enemy ?: return@onEachTick
            if (contactAt == null && men.any { it.target?.uuid == foe.uuid }) contactAt = helper.tick
            val met = contactAt ?: return@onEachTick
            if (helper.tick > met + 5) {
                helper.assertTrue(men.none { it.isAlive && WaryApproach.applies(it) }, "Still on a wary approach after contact at tick $met")
                scene.result("PASS", "contactAt=$met $tally")
                helper.succeed()
            }
        }
    }

    /** The living man nearest [center]; the scene cannot lose all eight before the enemy is met. */
    private fun lead(men: List<com.sbwnpc.squad.entity.NpcEntity>, center: Vec3) = men.filter { it.isAlive }.minBy { it.position().distanceTo(center) }

    /** Man-ticks beyond the wary band and inside it, and how many of each were wary. */
    private class Tally {
        var outside = 0
        var outsideWary = 0
        var inside = 0
        var insideWary = 0

        fun count(man: com.sbwnpc.squad.entity.NpcEntity, center: Vec3) {
            if (man.position().subtract(center).horizontalDistance() > WaryApproach.RANGE + 2) {
                outside++
                if (WaryApproach.applies(man)) outsideWary++
            } else {
                inside++
                if (WaryApproach.applies(man) && man.wary()) insideWary++
            }
        }

        override fun toString() = "outside=$outsideWary/$outside inside=$insideWary/$inside"
    }
}
