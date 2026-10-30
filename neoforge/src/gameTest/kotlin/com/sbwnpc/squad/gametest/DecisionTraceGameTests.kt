package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.diagnostics.DecisionTrace
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The decision trace (#193) explains every man of an attacking squad, and the choices it made. */
@PrefixGameTestTemplate(false)
object DecisionTraceGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 600, batch = "decision-trace")
    fun traceExplainsEveryManOfAnAttack(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "decision-trace")
        val manager = SquadManager.get(helper.level)
        val men = (0 until 6).map { scene.spawn(Vec3(30.5 + it * 2, 1.0, 60.5)) }
        for (i in 0 until 2) scene.enemy(Vec3(110.5, 1.0, 58.5 + i * 4))
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        manager.issueOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(110, 1, 60)))
        scene.onClose = { manager.deleteSquad(helper.level, squad.id) }
        helper.runAtTickTime(400) {
            val file = Path.of(System.getProperty("sbwnpc.gameTestReports"), "decision-trace", DecisionTrace.FILE)
            val sections = NativeTactics.traceSections()
            DecisionTrace.dump(file, helper.level.server.tickCount.toLong(), sections)
            val section = sections.single { it.title.endsWith(squad.id.toString()) }
            helper.assertTrue(section.header.first().startsWith("order=ATTACK"), "Header has no order: ${section.header}")
            helper.assertTrue(section.members.size == men.size, "Not every man has a chain: ${section.members.size} of ${men.size}")
            section.members.forEach { line ->
                helper.assertTrue(!line.contains("duty=null") && !line.contains("<- null") && line.contains("action=["), "A chain misses its duty, rule or action: $line")
            }
            helper.assertTrue(section.decisions.any { it.event == "duty" }, "No duty decisions recorded")
            helper.assertTrue(section.decisions.any { it.event == "plan" }, "No plan choice with its options recorded")
            helper.assertTrue(Files.readString(file).contains("== ${squad.name}"), "The dump file has no section for the squad")
            scene.result("PASS", "lines=${section.members.size} decisions=${section.decisions.size}")
            helper.succeed()
        }
    }
}
