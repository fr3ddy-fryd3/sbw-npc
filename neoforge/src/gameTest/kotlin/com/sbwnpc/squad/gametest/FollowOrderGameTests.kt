package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FollowFormation
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import net.minecraft.gametest.framework.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.util.UUID

/** The FOLLOW order (#116): a squad that keeps up with its player, on foot and in his vehicle. */
@PrefixGameTestTemplate(false)
object FollowOrderGameTests {
    /**
     * FOLLOW (#116): a squad keeps up with its owner, spread out, one squad per player, and holds
     * where it stands once he is gone.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1500, batch = "follow-owner")
    fun squadFollowsItsOwnerAndHoldsWhenHeIsGone(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "follow-owner")
        val manager = SquadManager.get(helper.level)
        val owner = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.level, com.mojang.authlib.GameProfile(UUID.randomUUID(), "follow-owner"))
        val start = helper.absoluteVec(Vec3(30.5, 1.0, 80.5))
        owner.moveTo(start.x, start.y, start.z, 0f, 0f)
        helper.level.addNewPlayer(owner)
        val groups = (0..1).map { group -> (0 until 4).map { index -> scene.spawn(Vec3(24.5 + index * 2, 1.0, 70.5 + group * 20)) } }
        val squads = groups.map { group -> manager.create(helper.level, owner.uuid, requireNotNull(group.first().spawnFaction), group.map { it.uuid }) }
        scene.onClose = {
            squads.forEach { manager.deleteSquad(helper.level, it.id) }
            helper.level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
        }
        helper.assertTrue(manager.issueFollow(helper.level, squads[0].id, owner), "The first squad could not follow")
        helper.assertTrue(manager.issueFollow(helper.level, squads[1].id, owner), "The second squad could not follow")
        helper.assertTrue(squads[0].order == SquadOrder.DEFEND && squads[1].order == SquadOrder.FOLLOW, "One squad per player: ${squads.map { it.order }}")
        val followers = groups[1]
        var walkStart: Long? = null
        var gone: Long? = null
        helper.onEachTick {
            val near = followers.count { it.position().distanceTo(owner.position()) <= 20 }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} near=$near order=${squads[1].order} owner=${helper.relativeVec(owner.position())}")
            if (gone == null) {
                if (walkStart == null && helper.tick >= 300) {
                    helper.assertTrue(near == followers.size, "The squad did not gather round its owner: $near of ${followers.size}")
                    helper.assertTrue(followers.map { it.position() }.let { points -> points.indices.all { a -> (0 until a).all { b -> points[a].distanceTo(points[b]) >= 1.0 } } }, "The men stand on top of each other")
                    walkStart = helper.tick
                }
                walkStart?.let { since ->
                    // 0.25 blocks a tick east, about a walking player.
                    owner.moveTo(owner.x + 0.25, owner.y, owner.z, 0f, 0f)
                    if (helper.tick - since >= 400) {
                        helper.assertTrue(near >= followers.size - 1, "The squad fell behind a walking owner: $near of ${followers.size}")
                        helper.assertTrue(squads[1].order == SquadOrder.FOLLOW, "Following ended while he was still here")
                        helper.level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
                        gone = helper.tick
                    }
                }
            } else if (helper.tick - gone!! >= 40) {
                helper.assertTrue(squads[1].order == SquadOrder.DEFEND && squads[1].focusEntity == null, "The squad kept following a player who is gone: ${squads[1].order}")
                helper.succeed()
            }
        }
    }

    /**
     * The owner gets into each of [rides] in turn and, once his men have filled its free seats and
     * sat in them for [HOLD_TICKS], out again; the next ride starts when they are all out.
     */
    private class RideStages(val helper: GameTestHelper, val owner: net.minecraft.world.entity.player.Player, val followers: List<com.sbwnpc.squad.entity.NpcEntity>, val rides: List<net.minecraft.world.entity.Entity>) {
        private var ride = 0
        private var boarded = false
        private var fullFor = 0
        val vehicle get() = rides.getOrNull(ride)
        private fun riding(vehicle: net.minecraft.world.entity.Entity) = followers.count { it.vehicle === vehicle }

        fun trace() = "ride=$ride boarded=$boarded riding=${vehicle?.let(::riding)} ownerIn=${owner.vehicle?.type?.descriptionId}"

        fun step() {
            val vehicle = vehicle ?: return
            when {
                !boarded && owner.vehicle == null -> seat(owner, vehicle)

                // The owner and as many of his men as there are seats left for, and they stay in while
                // he does: another ride's behaviour took them for its own and put them straight out.
                !boarded && riding(vehicle) >= minOf(Ports.vehicles.seatCount(vehicle) - 1, followers.size) -> {
                    if (++fullFor < HOLD_TICKS) return
                    boarded = true
                    fullFor = 0
                    owner.stopRiding()
                }

                !boarded && fullFor > 0 -> helper.fail("A man got out of the ${vehicle.type.descriptionId} while his owner sat in it")

                boarded && riding(vehicle) == 0 -> {
                    ride++
                    boarded = false
                }
            }
        }

        private companion object {
            const val HOLD_TICKS = 60
        }
    }

    private fun assertMapShowsFollow(helper: GameTestHelper, scene: CombatIdleGameTests.Scene, squad: com.sbwnpc.squad.squad.Squad) {
        val feed = com.sbwnpc.squad.map.MapFeed.build(helper.level.server, helper.level, squad.owner, squad.faction)
        val entry = feed.getList("Squads", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt()).map { it as net.minecraft.nbt.CompoundTag }.single { it.getUUID("Id") == squad.id }
        scene.result("MAP", "order=${entry.getString("Order")} goalOrder=${entry.getString("GoalOrder")}")
        helper.assertTrue(entry.getString("Order") == "FOLLOW" && entry.getString("GoalOrder") in setOf("", "FOLLOW"), "Map shows ${entry.getString("Order")}/${entry.getString("GoalOrder")} for a following squad")
    }

    /**
     * Puts [player] in [vehicle]. NeoForge's FakePlayer refuses every startRiding, so this does
     * what Entity.startRiding does once it has said yes.
     */
    private fun seat(player: net.minecraft.world.entity.player.Player, vehicle: net.minecraft.world.entity.Entity) {
        net.minecraft.world.entity.Entity::class.java.getDeclaredField("vehicle").apply { isAccessible = true }.set(player, vehicle)
        net.minecraft.world.entity.Entity::class.java.getDeclaredMethod("addPassenger", net.minecraft.world.entity.Entity::class.java).apply { isAccessible = true }.invoke(vehicle, player)
    }

    /**
     * FOLLOW (#116, in-game 20.10): the map shows the squad following, not defending; the owner's
     * own shots do not send his men to look; and the men get into the vehicle he sits in and out
     * again after him.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1600, batch = "follow-ride")
    fun followersShowOnTheMapIgnoreTheirOwnersShotsAndRideWithHim(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "follow-ride")
        val manager = SquadManager.get(helper.level)
        val owner = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.level, com.mojang.authlib.GameProfile(UUID.randomUUID(), "follow-ride"))
        val start = helper.absoluteVec(Vec3(30.5, 1.0, 80.5))
        owner.moveTo(start.x, start.y, start.z, 0f, 0f)
        helper.level.addNewPlayer(owner)
        // Gunners and a sniper too: on DEFEND they take height posts, and in game they held a roof
        // instead of following (in-game 20.10).
        val roles = listOf(NpcClass.RIFLEMAN, NpcClass.MACHINE_GUNNER, NpcClass.SNIPER, NpcClass.MACHINE_GUNNER)
        val followers = roles.mapIndexed { index, role -> scene.spawn(Vec3(24.5 + index * 2, 1.0, 74.5), role) }
        val squad = manager.create(helper.level, owner.uuid, requireNotNull(followers.first().spawnFaction), followers.map { it.uuid })
        fun park(model: com.sbwnpc.squad.domain.port.VehicleModel, at: Vec3) =
            requireNotNull(Ports.vehicles.create(helper.level, model)).also {
                val point = helper.absoluteVec(at)
                it.moveTo(point.x, point.y, point.z, 0f, 0f)
                helper.level.addFreshEntity(it)
                Ports.vehicles.fuelAndArm(it, model)
            }
        val rides = listOf(park(com.sbwnpc.squad.npc.TransportVehicle.LAV_150, Vec3(36.5, 1.0, 80.5)), park(com.sbwnpc.squad.npc.HelicopterModel.AH_6, Vec3(36.5, 1.0, 96.5)))
        scene.onClose = {
            owner.stopRiding()
            manager.deleteSquad(helper.level, squad.id)
            rides.forEach { it.discard() }
            helper.level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
        }
        helper.assertTrue(manager.issueFollow(helper.level, squad.id, owner), "The squad could not follow")
        val stages = RideStages(helper, owner, followers, rides)
        helper.onEachTick {
            val vehicle = stages.vehicle
            if (helper.tick % 50L == 0L) scene.result("RUNNING", "tick=${helper.tick} ${stages.trace()} order=${squad.order} listening=${followers.count { it.listening() }} roles=${followers.map { "${it.npcClass}@${helper.relativeVec(it.position())}" }}")
            when {
                helper.tick == 100L -> {
                    assertMapShowsFollow(helper, scene, squad)
                    com.sbwnpc.squad.combat.Hearing.gunshot(helper.level, owner, 64.0, suppressed = false)
                }

                helper.tick == 102L -> helper.assertTrue(followers.none { it.listening() }, "The owner's own shot sent his men to look")

                helper.tick < 150L -> Unit

                vehicle == null -> {
                    helper.assertTrue(squad.order == SquadOrder.FOLLOW, "Following ended on the way: ${squad.order}")
                    scene.result("PASS", "tick=${helper.tick}")
                    helper.succeed()
                }

                else -> stages.step()
            }
        }
    }

    /**
     * In a fight a following squad keeps up with its player by bounds (#116, owner's call 21.10):
     * one half runs to its places by him while the other covers, turn about. Before, the contact
     * froze every man where it found him and the player walked off alone.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 2000, batch = "follow-bounds")
    fun followingSquadBoundsAfterItsPlayerInAFight(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "follow-bounds")
        val manager = SquadManager.get(helper.level)
        val owner = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.level, com.mojang.authlib.GameProfile(UUID.randomUUID(), "follow-bounds"))
        val start = helper.absoluteVec(Vec3(60.5, 1.0, 60.5))
        owner.moveTo(start.x, start.y, start.z, 0f, 0f)
        helper.level.addNewPlayer(owner)
        val men = (0 until 6).map { scene.spawn(Vec3(56.5 + it * 2, 1.0, 56.5)) }
        // Kept alive for the whole scene: the contact must last while the player walks off.
        val enemy = scene.enemy(Vec3(60.5, 1.0, 105.5)).also { it.isInvulnerable = true }
        val squad = manager.create(helper.level, owner.uuid, requireNotNull(men.first().spawnFaction), men.map { it.uuid })
        scene.onClose = {
            manager.deleteSquad(helper.level, squad.id)
            helper.level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
        }
        helper.assertTrue(manager.issueFollow(helper.level, squad.id, owner), "The squad could not follow")
        // Who ran on which bound: both halves must, on different bounds.
        val ranOn = HashMap<UUID, MutableSet<Long>>()
        var walkFrom: Long? = null
        helper.onEachTick {
            if (walkFrom == null && men.count { it.target?.uuid == enemy.uuid } >= 2) walkFrom = helper.tick
            val since = walkFrom ?: return@onEachTick
            // Off to the west at about a walking pace for 40 blocks, then he stops.
            if (helper.tick - since < 160) owner.moveTo(owner.x - 0.25, owner.y, owner.z, 0f, 0f)
            val beat = helper.level.gameTime / com.sbwnpc.squad.ai.tactics.OperationTactics.BOUND_TICKS
            men.filter { it.isAlive && com.sbwnpc.squad.combat.tactics.SquadTactics.task(it)?.job == com.sbwnpc.squad.combat.tactics.TacticalJob.ADVANCE }
                .forEach { ranOn.getOrPut(it.uuid) { HashSet() } += beat }
            val near = men.count { it.isAlive && it.position().distanceTo(owner.position()) <= FollowFormation.NEAR_DISTANCE }
            if (helper.tick % 100L == 0L) scene.result("RUNNING", "tick=${helper.tick} near=$near ran=${ranOn.mapValues { it.value.size }.values} enemyAlive=${enemy.isAlive}")
            if (helper.tick - since >= 600) {
                val beats = ranOn.values.flatten().toSet()
                helper.assertTrue(ranOn.size >= 3 && beats.size >= 2, "No bounding: runners ${ranOn.size} on ${beats.size} bounds")
                helper.assertTrue(near >= 4, "The squad did not keep up with its player: $near of ${men.size} within ${FollowFormation.NEAR_DISTANCE}")
                scene.result("PASS", "near=$near runners=${ranOn.size} bounds=${beats.size}")
                helper.succeed()
            }
        }
    }
}
