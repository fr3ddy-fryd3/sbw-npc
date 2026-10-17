package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * What a squad does when one of its men dies beside it (#66): the allies grow wary of where the
 * threat came from, the killer when known, else the body. Their reaction is also written to a
 * report file.
 */
@PrefixGameTestTemplate(false)
object AllyDeathGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 200, batch = "ally-death")
    fun allyKilledByUnseenShooterMakesTheSquadWary(helper: GameTestHelper) = allyDeath(helper, "unseen-shooter", killer = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 200, batch = "ally-death-unknown")
    fun allyDeadOfUnknownCauseMakesTheSquadWary(helper: GameTestHelper) = allyDeath(helper, "unknown-cause", killer = false)

    private fun allyDeath(helper: GameTestHelper, name: String, killer: Boolean) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // The shooter stands behind a wall, out of every ally's sight.
        for (z in 10..38) for (y in 1..4) helper.setBlock(BlockPos(50, y, z), Blocks.STONE)
        val men = (0..4).map { AttackDeploymentGameTests.spawn(helper, Vec3(20.5 + it % 3 * 3, 1.0, 22.5 + it / 3 * 3), SquadFaction.PIG) }
        val shooter = AttackDeploymentGameTests.spawn(helper, Vec3(60.5, 1.0, 24.5), SquadFaction.COW).apply { isNoAi = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        val victim = men.first()
        val allies = men.drop(1)
        val start = allies.associateWith { it.position() }
        val startYaw = allies.associateWith { it.yHeadRot }
        val lines = ArrayList<String>()
        val body = victim.position()
        helper.runAtTickTime(40) {
            if (killer) victim.hurt(helper.level.damageSources().mobAttack(shooter), 1000f) else victim.kill()
            lines += "tick 40: victim dead=${!victim.isAlive} killer=$killer"
        }
        for (tick in listOf(45, 60, 100, 160)) {
            helper.runAtTickTime(tick.toLong()) {
                allies.forEach {
                    lines += "tick $tick ${it.uuid.toString().take(8)} wary=${it.wary()} alert=${it.isAlert()} listening=${it.listening()} target=${it.target?.uuid?.toString()?.take(8)} " +
                        "moved=${"%.1f".format(it.position().distanceTo(start.getValue(it)))} turned=${"%.0f".format(Math.abs(it.yHeadRot - startYaw.getValue(it)))}"
                }
            }
        }
        helper.runAtTickTime(45) {
            val threat = if (killer) shooter.position() else body
            helper.assertTrue(allies.all { it.wary() && it.waryToward() == threat }, "Not every ally grew wary of where the threat came from")
        }
        helper.runAtTickTime(170) {
            helper.assertTrue(allies.all { it.wary() }, "An ally stopped being wary within 6 seconds")
            helper.assertTrue(allies.all { it.target != null || it.position().distanceTo(start.getValue(it)) < 4.0 }, "A wary ally with nothing to fight wandered off")
            val reports = Files.createDirectories(Path.of(System.getProperty("sbwnpc.gameTestReports")))
            Files.writeString(reports.resolve("ally-death-$name.txt"), lines.joinToString("\n") + "\n")
            manager.deleteSquad(helper.level, squad.id)
            (men + shooter).forEach { it.discard() }
            helper.succeed()
        }
    }
}
