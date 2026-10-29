package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.entity.projectile.ProjectileEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * What a squad does when one of its men dies or is shot beside it (#66): the allies grow wary of
 * where the threat came from, the killer or the fire when known, else the body. Their reaction to
 * a death is also written to a report file.
 */
@PrefixGameTestTemplate(false)
object AllyDeathGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 300, batch = "ally-death")
    fun allyKilledByUnseenShooterMakesTheSquadWary(helper: GameTestHelper) = allyDeath(helper, "unseen-shooter", killer = true)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 300, batch = "ally-death-unknown")
    fun allyDeadOfUnknownCauseMakesTheSquadWary(helper: GameTestHelper) = allyDeath(helper, "unknown-cause", killer = false)

    /** A man shot by an enemy he cannot see grows wary of the fire, and so do the men round him. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 200, batch = "ally-hit")
    fun hitByUnseenShooterMakesTheManAndThoseRoundHimWary(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        for (z in 10..38) for (y in 1..4) helper.setBlock(BlockPos(50, y, z), Blocks.STONE)
        val men = (0..4).map { AttackDeploymentGameTests.spawn(helper, Vec3(20.5 + it % 3 * 3, 1.0, 22.5 + it / 3 * 3), SquadFaction.PIG) }
        // Farther than the warning reaches.
        val far = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 45.5), SquadFaction.PIG)
        val shooter = AttackDeploymentGameTests.spawn(helper, Vec3(60.5, 1.0, 24.5), SquadFaction.COW).apply { isNoAi = true }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        val hit = men.first()
        helper.runAtTickTime(40) {
            helper.assertTrue(men.none { it.wary() }, "A man was wary before the shot")
            hit.hurt(ModDamageTypes.causeGunFireDamage(helper.level.registryAccess(), shooter, shooter), 2f)
        }
        helper.runAtTickTime(45) {
            helper.assertTrue(hit.isAlive && hit.wary(), "The man shot from out of sight did not grow wary")
            helper.assertTrue(men.drop(1).all { it.wary() }, "The men round him did not grow wary")
            helper.assertTrue(!far.wary(), "A man beyond the warning radius grew wary")
            manager.deleteSquad(helper.level, squad.id)
            (men + far + shooter).forEach { it.discard() }
            helper.succeed()
        }
    }

    /** A round striking the ground beside a man makes him wary of where it came from; one farther off does not. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 200, batch = "near-miss")
    fun roundLandingBesideAManMakesHimWary(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        // The shooter stands behind a wall, so the men have no target to explain the round.
        for (z in 10..38) for (y in 1..4) helper.setBlock(BlockPos(50, y, z), Blocks.STONE)
        val man = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 22.5), SquadFaction.PIG)
        val far = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 32.5), SquadFaction.PIG)
        val shooter = AttackDeploymentGameTests.spawn(helper, Vec3(60.5, 1.0, 22.5), SquadFaction.COW).apply { isNoAi = true }
        val impact = helper.absoluteVec(Vec3(22.5, 1.0, 22.5))
        helper.runAtTickTime(40) {
            helper.assertTrue(!man.wary() && !far.wary(), "A man was wary before the round")
            val start = helper.absoluteVec(Vec3(40.5, 6.0, 22.5))
            ProjectileEntity(helper.level).shooter(shooter).damage(1f).apply {
                setPos(start)
                shoot(null, impact.x - start.x, impact.y - start.y, impact.z - start.z, 8f, 0f)
                helper.level.addFreshEntity(this)
            }
        }
        helper.runAtTickTime(60) {
            helper.assertTrue(man.wary(), "A round landing two blocks off did not make the man wary")
            val toward = man.waryToward()!!
            helper.assertTrue(toward.x > impact.x && toward.y > impact.y, "The man looked away from where the round came from: $toward")
            helper.assertTrue(!far.wary(), "A man ten blocks from the impact grew wary")
            (listOf(man, far, shooter)).forEach { it.discard() }
            helper.succeed()
        }
    }

    /** Thirty seconds after a man last had a target he grows wary of where the enemy was last seen (#186). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 800, batch = "contact-end")
    fun manGrowsWaryThirtySecondsAfterContactEnds(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val man = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 22.5), SquadFaction.PIG)
        val enemy = AttackDeploymentGameTests.spawn(helper, Vec3(40.5, 1.0, 22.5), SquadFaction.COW).apply { isNoAi = true }
        val lastSeen = helper.absoluteVec(Vec3(40.5, 1.0, 22.5))
        helper.runAtTickTime(20) {
            man.target = enemy
            helper.assertTrue(!man.wary(), "The man was wary before the contact ended")
        }
        helper.runAtTickTime(40) {
            man.target = null
            enemy.discard()
        }
        helper.runAtTickTime(500) {
            helper.assertTrue(!man.wary(), "The man grew wary before 30 seconds had passed")
        }
        helper.runAtTickTime(700) {
            helper.assertTrue(man.wary(), "The man did not grow wary 30 seconds after the contact ended")
            val toward = man.waryToward()!!
            helper.assertTrue(toward.distanceTo(lastSeen) < 2.0, "The man is wary of $toward, not where the enemy was last seen")
            man.discard()
            helper.succeed()
        }
    }

    /** Within 100 blocks of its objective a squad on foot is wary, and on ATTACK walks, until its first target (#148). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 200, batch = "objective-approach")
    fun squadApproachingItsObjectiveIsWaryAndWalksUntilContact(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val manager = SquadManager.get(helper.level)
        fun squad(at: Vec3, order: SquadOrder, objective: BlockPos): NpcEntity {
            val man = AttackDeploymentGameTests.spawn(helper, at, SquadFaction.PIG)
            val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, listOf(man.uuid))
            manager.setOrder(squad.id, order)
            manager.setObjective(helper.level, squad.id, helper.absolutePos(objective))
            return man
        }
        val attacker = squad(Vec3(10.5, 1.0, 10.5), SquadOrder.ATTACK, BlockPos(70, 1, 10))
        val mover = squad(Vec3(10.5, 1.0, 20.5), SquadOrder.MOVE, BlockPos(70, 1, 20))
        val distant = squad(Vec3(10.5, 1.0, 30.5), SquadOrder.ATTACK, BlockPos(10 + 150, 1, 30))
        val base = attacker.getAttributeValue(Attributes.MOVEMENT_SPEED)
        var enemy: NpcEntity? = null
        helper.runAtTickTime(60) {
            helper.assertTrue(attacker.wary() && mover.wary(), "A man within 100 blocks of his objective was not wary: attacker=${attacker.wary()} mover=${mover.wary()} squad=${attacker.currentSquad()?.order} obj=${attacker.currentSquad()?.objective} pos=${attacker.blockPosition()}")
            helper.assertTrue(!distant.wary(), "A man 150 blocks from his objective was wary")
            helper.assertTrue(attacker.getAttributeValue(Attributes.MOVEMENT_SPEED) < base * 0.7, "The attacking man did not slow to a walk")
            helper.assertTrue(mover.getAttributeValue(Attributes.MOVEMENT_SPEED) > base * 0.95, "The MOVE order was slowed further")
            enemy = AttackDeploymentGameTests.spawn(helper, Vec3(60.5, 1.0, 40.5), SquadFaction.COW).apply { isNoAi = true }
            attacker.target = enemy
        }
        helper.runAtTickTime(80) {
            helper.assertTrue(!attacker.wary(), "The man stayed wary after his first contact")
            helper.assertTrue(attacker.getAttributeValue(Attributes.MOVEMENT_SPEED) > base * 0.95, "The man kept walking after his first contact")
            listOf(attacker, mover, distant, enemy!!).forEach { it.discard() }
            helper.succeed()
        }
    }

    /** Wariness only steers an idle NPC's eyes: an enemy in the open is still fought. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 400, batch = "ally-death-engage")
    fun waryAlliesStillEngageAVisibleEnemy(helper: GameTestHelper) {
        for (x in 0..79) for (z in 0..47) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val men = (0..4).map { AttackDeploymentGameTests.spawn(helper, Vec3(20.5 + it % 3 * 3, 1.0, 22.5 + it / 3 * 3), SquadFaction.PIG) }
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, men.map { it.uuid })
        val allies = men.drop(1)
        val engaged = HashSet<UUID>()
        var enemy: NpcEntity? = null
        helper.runAtTickTime(40) { men.first().kill() }
        helper.runAtTickTime(60) {
            helper.assertTrue(allies.all { it.wary() }, "The allies did not grow wary")
            // Invulnerable: the first ally to fire must not kill it before the others take it as target.
            enemy = AttackDeploymentGameTests.spawn(helper, Vec3(45.5, 1.0, 24.5), SquadFaction.COW).apply {
                isNoAi = true
                isInvulnerable = true
            }
        }
        helper.onEachTick {
            val seen = enemy ?: return@onEachTick
            allies.filter { it.target == seen }.forEach { engaged += it.uuid }
            if (allies.all { it.uuid in engaged }) {
                manager.deleteSquad(helper.level, squad.id)
                (men + seen).forEach { it.discard() }
                helper.succeed()
            }
        }
    }

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
        checkSweep(helper, allies, lines)
        helper.runAtTickTime(240) {
            val reports = Files.createDirectories(Path.of(System.getProperty("sbwnpc.gameTestReports")))
            Files.writeString(reports.resolve("ally-death-$name.txt"), lines.joinToString("\n") + "\n")
            helper.assertTrue(allies.all { it.wary() }, "An ally stopped being wary within 10 seconds")
            helper.assertTrue(allies.all { it.target != null || it.position().distanceTo(start.getValue(it)) < 4.0 }, "A wary ally with nothing to fight wandered off")
            manager.deleteSquad(helper.level, squad.id)
            (men + shooter).forEach { it.discard() }
            helper.succeed()
        }
    }

    /**
     * Checks that once the turn to the threat is over (the heard look, 100 ticks) an idle wary ally
     * sweeps its sector: its head keeps moving rather than staying fixed on one bearing.
     */
    private fun checkSweep(helper: GameTestHelper, allies: List<NpcEntity>, lines: MutableList<String>) {
        val sweeps = allies.associateWith { ArrayList<Float>() }
        for (tick in 150..230 step 10) helper.runAtTickTime(tick.toLong()) { sweeps.forEach { (ally, yaws) -> yaws += ally.yHeadRot } }
        helper.runAtTickTime(239) {
            lines += sweeps.entries.joinToString("\n") { (ally, yaws) -> "sweep ${ally.uuid.toString().take(8)} yaws=$yaws" }
            val idle = allies.filter { it.target == null }
            helper.assertTrue(
                idle.all { sweeping(sweeps.getValue(it)) },
                "A wary ally with nothing to fight kept its eyes fixed instead of sweeping: $sweeps",
            )
        }
    }

    /** Whether the head yaws sampled over time spread over more than a few degrees. */
    private fun sweeping(yaws: List<Float>) = yaws.any { kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(it - yaws.first())) > 5f }
}
