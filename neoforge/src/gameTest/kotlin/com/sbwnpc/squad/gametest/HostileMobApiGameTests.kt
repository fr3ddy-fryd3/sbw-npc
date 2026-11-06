package com.sbwnpc.squad.gametest

import com.mojang.authlib.GameProfile
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.NeutralMob
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.monster.Zombie
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.common.util.FakePlayerFactory
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** Native targeting and friendliness boundaries that a mob-combat extension must preserve. */
@PrefixGameTestTemplate(false)
object HostileMobApiGameTests {
    /** Real mobs and players for API probes, without their autonomous movement changing the setup. */
    private class ApiScene(val helper: GameTestHelper, name: String) {
        val scene = CombatIdleGameTests.Scene(helper, name)
        val npc = scene.spawn(Vec3(40.5, 1.0, 40.5))
        private val mobs = mutableListOf<Mob>()
        private val players = mutableListOf<FakePlayer>()
        private val previousDay = helper.level.dayTime

        init {
            scene.onClose = {
                mobs.forEach(Entity::discard)
                players.forEach { helper.level.removePlayerImmediately(it, Entity.RemovalReason.DISCARDED) }
                helper.level.dayTime = previousDay
            }
        }

        fun <T : Mob> mob(type: EntityType<T>, at: Vec3): T = track(requireNotNull(type.create(helper.level)), at)

        fun <T : Mob> track(mob: T, at: Vec3): T =
            mob.also {
                val point = helper.absoluteVec(at)
                it.moveTo(point.x, point.y, point.z, 0f, 0f)
                it.isNoAi = true
                helper.level.addFreshEntity(it)
                mobs += it
            }

        fun player(at: Vec3): FakePlayer =
            FakePlayerFactory.get(helper.level, GameProfile(UUID.randomUUID(), "mob-api-player")).also {
                it.gameMode.changeGameModeForPlayer(GameType.SURVIVAL)
                val point = helper.absoluteVec(at)
                it.moveTo(point.x, point.y, point.z, 0f, 0f)
                helper.level.addNewPlayer(it)
                players += it
            }
    }

    /**
     * A spider hunts a squad man only in the dark: its daylight gate runs before the search that
     * finds NPCs (#135). The man is its only quarry, so the night catch proves the search reaches
     * him and the day refusal is the gate, not a man it could not find.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 300, batch = "mob-api-spider-light")
    fun originalSpiderGoalRetainsItsBrightnessCondition(helper: GameTestHelper) {
        val probe = ApiScene(helper, "mob-api-spider-light")
        // He must not shoot the spider first: a hurt spider hunts its attacker by day too.
        probe.npc.isNoAi = true
        // Mobs hunt only squad men: a loose man is no one's soldier.
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), requireNotNull(probe.npc.spawnFaction), listOf(probe.npc.uuid))
        val close = probe.scene.onClose
        probe.scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            close()
        }
        val spider = probe.mob(EntityType.SPIDER, Vec3(44.5, 1.0, 40.5))
        spider.isNoAi = false
        helper.level.dayTime = 6000L
        helper.onEachTick {
            if (helper.tick < SPIDER_DAY_TICKS) {
                helper.assertTrue(spider.lightLevelDependentMagicValue >= 0.5f, "The daylight fixture was not bright")
                helper.assertTrue(spider.target == null, "The spider hunted the man in daylight at tick ${helper.tick}")
                return@onEachTick
            }
            if (helper.tick == SPIDER_DAY_TICKS) helper.level.dayTime = 18000L
            if (spider.target === probe.npc) {
                helper.assertTrue(spider.lightLevelDependentMagicValue < 0.5f, "The night fixture was not dark")
                probe.scene.result("PASS", "daylightRefused=$SPIDER_DAY_TICKS nightCaughtAt=${helper.tick}")
                helper.succeed()
            }
        }
    }

    /** Ticks of daylight the spider is watched in: a goal that hunts at all picks a man 4 blocks off within a few seconds. */
    private const val SPIDER_DAY_TICKS = 100L

    /** Enemy/NeutralMob markers do not replace actual neutral, tame-owner and mod-alliance decisions. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "mob-api-friendly")
    fun neutralMarkersDoNotOverrideTameOrModAlliances(helper: GameTestHelper) {
        val probe = ApiScene(helper, "mob-api-friendly")
        val enderman: Mob = probe.mob(EntityType.ENDERMAN, Vec3(44.5, 1.0, 40.5))
        val piglin: Mob = probe.mob(EntityType.PIGLIN, Vec3(46.5, 1.0, 40.5))
        helper.assertTrue(enderman is Enemy && enderman is NeutralMob, "The enderman marker contract changed")
        helper.assertTrue(piglin is Enemy && piglin !is NeutralMob, "The piglin marker contract changed")
        helper.assertTrue(!SquadTeams.isHostile(probe.npc, enderman) && !SquadTeams.isHostile(probe.npc, piglin), "An unprovoked neutral mob became hostile")
        val owner = probe.player(Vec3(48.5, 1.0, 40.5))
        PlayerFactionRegistry.get(helper.level).set(owner.uuid, SquadFaction.PIG)
        val wolf = probe.mob(EntityType.WOLF, Vec3(50.5, 1.0, 40.5))
        wolf.tame(owner)
        helper.assertTrue(wolf.isAlliedTo(probe.npc) && !SquadTeams.isHostile(probe.npc, wolf), "The owner's wolf lost its alliance")
        val friendly = probe.track(
            object : Zombie(EntityType.ZOMBIE, helper.level) {
                override fun isAlliedTo(other: Entity): Boolean = other === probe.npc || super.isAlliedTo(other)
            },
            Vec3(52.5, 1.0, 40.5),
        )
        helper.assertTrue(friendly.isAlliedTo(probe.npc) && !SquadTeams.isHostile(probe.npc, friendly), "A mod-provided alliance became hostile")
        probe.scene.result("PASS", "neutralMarkers=true ownerPet=true modAlliance=true")
        helper.succeed()
    }

    /** An unprovoked monster is a squad's enemy only within the engage range, not its whole detection range. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 100, batch = "mob-api-range")
    fun unprovokedMonsterCountsOnlyWithinEngageRange(helper: GameTestHelper) {
        val probe = ApiScene(helper, "mob-api-range")
        val near = probe.mob(EntityType.ZOMBIE, Vec3(60.5, 1.0, 40.5))
        val far = probe.mob(EntityType.ZOMBIE, Vec3(100.5, 1.0, 40.5))
        helper.assertTrue(SquadTeams.isHostile(probe.npc, near), "A zombie 20 blocks off was not an enemy")
        helper.assertTrue(!SquadTeams.isHostile(probe.npc, far), "A zombie 60 blocks off, after nobody, was an enemy")
        far.target = probe.npc
        helper.assertTrue(SquadTeams.isHostile(probe.npc, far), "A far zombie going for the man himself was not an enemy")
        probe.scene.result("PASS", "near=true far=false farOnHim=true")
        helper.succeed()
    }
}
