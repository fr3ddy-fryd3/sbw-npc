package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.combat.OffscreenFire
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID
import java.util.function.Consumer

@PrefixGameTestTemplate(false)
object AttackDeploymentGameTests {
    /** Use SBW damage sources directly to distinguish damage from reporting a hostile contact. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "movement_arena", timeoutTicks = 40)
    fun friendlyBulletDoesNotCreateEnemyContact(helper: GameTestHelper) {
        for (x in 0..23) for (z in 0..11) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val victim = spawn(helper, Vec3(6.5, 1.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        val ally = spawn(helper, Vec3(3.5, 1.0, 6.5), SquadFaction.PIG).apply { isNoAi = true }
        val enemy = spawn(helper, Vec3(10.5, 1.0, 6.5), SquadFaction.COW).apply { isNoAi = true }
        try {
            val beforeFriendly = victim.health
            Ports.guns.inHand(ally)!!.inflictHit(victim, 1.0f)
            helper.assertTrue(victim.health < beforeFriendly, "Friendly damage was disabled rather than its retaliation being fixed")
            helper.assertTrue(!victim.incomingFire.pending(helper.level.gameTime), "Friendly bullet created a return-fire episode")
            helper.assertTrue(victim.incomingFire.recentPoint(helper.level.gameTime) == null, "Friendly bullet created an enemy bearing")

            victim.invulnerableTime = 0
            val beforeHostile = victim.health
            Ports.guns.inHand(enemy)!!.inflictHit(victim, 1.0f)
            helper.assertTrue(victim.health < beforeHostile, "Hostile control hit was rejected")
            helper.assertTrue(victim.incomingFire.pending(helper.level.gameTime), "Hostile bullet did not create a return-fire episode")
            helper.assertTrue(victim.incomingFire.recentPoint(helper.level.gameTime) != null, "Hostile bullet did not report an incoming bearing")
            helper.succeed()
        } finally {
            listOf(victim, ally, enemy).forEach { it.discard() }
        }
    }

    /** No return fire: isolate natural detection, attack deployment and weapon participation. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 900)
    fun attackDeploysAndFiresOnOpenGround(helper: GameTestHelper) {
        for (x in 0..159) {
            for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        }
        val attackers = (0..15).map { index ->
            spawn(helper, Vec3(20.5 + (index / 4) * 3, 1.0, 58.5 + (index % 4) * 3), SquadFaction.PIG)
        }
        val targets = (0..3).map { index ->
            spawn(helper, Vec3(96.5, 1.0, 55.5 + index * 6), SquadFaction.COW).apply {
                // Stationary targets survive the observation window; attackers retain normal AI/loadout.
                isNoAi = true
                getAttribute(Attributes.MAX_HEALTH)!!.baseValue = 5000.0
                health = maxHealth
            }
        }

        // NeoForge's fake connection drops packets; vanilla's login helper cannot negotiate SBW payloads.
        val witness = FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "headless-observer"))
        witness.gameMode.changeGameModeForPlayer(GameType.CREATIVE)
        val observerPos = helper.absoluteVec(Vec3(60.5, 5.0, 5.5))
        witness.moveTo(observerPos.x, observerPos.y, observerPos.z, 0.0f, 0.0f)
        witness.setNoGravity(true)
        helper.level.addNewPlayer(witness)
        helper.assertTrue(OffscreenFire.hasWitness(helper.level, attackers.first(), targets.first()), "Test player did not enable real projectiles")

        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, attackers.map { it.uuid })
        val trace = AttackTrace(helper, squad, attackers)
        val flankTrace = FlankTrace(helper, squad, attackers)
        val attackerIds = attackers.map { it.uuid }.toSet()
        val targetIds = targets.map { it.uuid }.toSet()
        DebugFlags.enabledGroups = setOf(LogGroup.ORDER, LogGroup.PATH, LogGroup.STUCK)
        val projectiles = Consumer<EntityJoinLevelEvent> { event ->
            val entity = event.entity
            if (event.level === helper.level && entity is Projectile && Ports.guns.isRound(entity)) {
                val owner = entity.owner?.uuid
                if (owner != null && owner in attackerIds) {
                    trace.round(attackers.first { it.uuid == owner }, entity)
                } else if (owner != null && owner in targetIds) {
                    trace.enemyRound()
                }
            }
        }
        val damage = Consumer<LivingDamageEvent.Post> { event ->
            if (event.entity.uuid in attackerIds) trace.damage(event.entity, event.source, event.newDamage, death = false)
        }
        val deaths = Consumer<LivingDeathEvent> { event ->
            if (event.entity.uuid in attackerIds) trace.damage(event.entity, event.source, 0.0f, death = true)
        }
        val pacer = CombatTickPacer()
        NeoForge.EVENT_BUS.addListener(projectiles)
        NeoForge.EVENT_BUS.addListener(damage)
        NeoForge.EVENT_BUS.addListener(deaths)
        NeoForge.EVENT_BUS.addListener<ServerTickEvent.Pre>(pacer)
        manager.setOrder(squad.id, SquadOrder.ATTACK)
        manager.setObjective(helper.level, squad.id, helper.absolutePos(BlockPos(96, 1, 64)))
        helper.onEachTick {
            trace.sample()
            flankTrace.sample()
        }
        helper.runAtTickTime(899) {
            try {
                trace.finish()
                flankTrace.finish()
                helper.assertTrue(trace.enemyRounds == 0 && targets.none { it.lastShotTick >= 0 }, "Stationary targets unexpectedly fired: invalid isolation scenario")
                helper.assertTrue(attackers.all { it.isAlive }, "Attackers died without enemy return fire; see attack-damage.csv")
                helper.assertTrue(trace.friendlyHits == 0, "Attackers were hit by friendly rounds; see attack-damage.csv")
                helper.assertTrue(trace.maxShotAngle <= 15.0, "Rifle projectile direction diverged from its target by more than 15 degrees; see attack-rounds.csv")
                helper.assertTrue(trace.unsafeRounds == 0, "A round was fired despite a blocked friendly lane; see attack-rounds.csv")
                helper.assertTrue(trace.longestLineHold >= 40, "Attack did not sustain a spaced line of at least 12/16 NPCs for 40 ticks; see attack-summary.txt")
                helper.assertTrue(trace.projectileShooters >= 12, "Fewer than 12/16 NPCs fired real SBW rounds; see attack-summary.txt")
                helper.assertTrue(trace.ammoSpent >= 12, "Weapon ammunition was not consumed")
                helper.assertTrue(flankTrace.moverCount >= 6 && flankTrace.maxArrived >= 6, "Flank did not actually move and arrive; see attack-flank-summary.txt")
                helper.assertTrue(flankTrace.completedCount >= 1, "No flank completed with all its movers arrived; see attack-flank-summary.txt")
                helper.assertTrue(flankTrace.orderCrossings == 0, "Flankers crossed each other's lateral order; see attack-flank-crossings.csv")
                helper.assertTrue(flankTrace.longestCrowding < 40, "Flankers remained within 3 blocks of another NPC for 40 ticks; see attack-flanks.csv")
                helper.succeed()
            } finally {
                NeoForge.EVENT_BUS.unregister(projectiles)
                NeoForge.EVENT_BUS.unregister(damage)
                NeoForge.EVENT_BUS.unregister(deaths)
                NeoForge.EVENT_BUS.unregister(pacer)
                manager.deleteSquad(helper.level, squad.id)
                (attackers + targets).forEach { it.discard() }
                helper.level.removePlayerImmediately(witness, Entity.RemovalReason.DISCARDED)
            }
        }
    }

    internal fun spawn(helper: GameTestHelper, at: Vec3, faction: SquadFaction, identity: UUID? = null, randomSeed: Long? = null, role: NpcClass = NpcClass.RIFLEMAN): NpcEntity {
        val entity = if (identity == null) {
            helper.spawn(ModEntities.NPC.get(), at)
        } else {
            ModEntities.NPC.get().create(helper.level)!!.apply {
                uuid = identity
                setPersistenceRequired()
                val point = helper.absoluteVec(at)
                moveTo(point.x, point.y, point.z, yRot, xRot)
                check(helper.level.addFreshEntity(this)) { "Fixture entity could not be added to the server" }
            }
        }
        return entity.apply {
            randomSeed?.let { random.setSeed(it) }
            npcClass = role
            npcRank = NpcRank.DEFAULT
            spawnFaction = faction
            finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(blockPosition()), MobSpawnType.COMMAND, null)
            SquadTeams.assign(this, faction)
            // No grenades in this rifle/deployment scenario; grenade reactions need their own scene.
            rgnLeft = 0
            rgoLeft = 0
            yRot = -90.0f
            yHeadRot = -90.0f
        }
    }
}
