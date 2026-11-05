package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.combat.AntiArmourKit
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate

/** Regression scenes for the launcher's planned armour response. */
@PrefixGameTestTemplate(false)
object ArmourLauncherGameTests {
    /** An unrelated shot event cannot complete the launcher's planned firing action. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 900, batch = "armour-other-shot")
    fun unrelatedShotDoesNotFinishLauncherFire(helper: GameTestHelper) {
        val front = ArmourCoverGameTests.Front(helper, "armour-other-shot", wall = true, roles = listOf(NpcClass.MACHINE_GUNNER), infantry = false)
        front.lav.isInvulnerable = true
        val man = front.men.single()
        front.manager.setOrder(front.squad.id, SquadOrder.ATTACK)
        front.manager.setObjective(helper.level, front.squad.id, front.lav.blockPosition())
        front.manager.setFocus(front.squad.id, front.crew.uuid)
        val launcher = { if (AntiArmourKit.isLauncher(man.mainHandItem)) man.mainHandItem else man.antiArmourWeapon }
        var ticket: com.sbwnpc.squad.ai.planning.ExecutionTicket? = null
        var injectedAt = 0L
        var roundsBefore = 0
        helper.onEachTick {
            if (helper.tick == 10L) com.sbwnpc.squad.combat.tactics.SquadTactics.observe(man, front.crew)
            val fire = NativeTactics.reports(man).values.firstOrNull { ":FIRE:" in it.ticket.identity && it.status == com.sbwnpc.squad.ai.planning.ExecutionStatus.RUNNING }
            if (ticket == null && fire != null) {
                roundsBefore = Ports.guns.roundsLeft(launcher())
                helper.assertTrue(roundsBefore > 0, "Fixture entered FIRE without a loaded launcher")
                val towards = front.crew.position().subtract(man.position()).multiply(1.0, 0.0, 1.0).normalize()
                val blocker = front.scene.spawn(helper.relativeVec(man.position().add(towards.scale(3.0))))
                blocker.isNoAi = true
                blocker.isInvulnerable = true
                ticket = fire.ticket
                injectedAt = helper.tick
                man.lastShotTick = man.tickCount
            }
            if (ticket != null && helper.tick >= injectedAt + 3) {
                helper.assertTrue(Ports.guns.roundsLeft(launcher()) == roundsBefore, "A real rocket was spent before the unrelated-shot check")
                helper.assertTrue(fire?.ticket == ticket, "An unrelated shot completed or replaced the launcher's FIRE action")
                front.scene.result("PASS", "unrelated shot did not finish FIRE")
                helper.succeed()
            }
        }
    }
}
