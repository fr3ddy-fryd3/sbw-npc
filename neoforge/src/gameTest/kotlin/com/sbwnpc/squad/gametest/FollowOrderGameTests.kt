package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.domain.port.Ports
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
    @GameTest(templateNamespace = "sbwnpc", template = "terrain_arena", skyAccess = true, timeoutTicks = 1200, batch = "follow-ride")
    fun followersShowOnTheMapIgnoreTheirOwnersShotsAndRideWithHim(helper: GameTestHelper) {
        val scene = CombatIdleGameTests.Scene(helper, "follow-ride")
        val manager = SquadManager.get(helper.level)
        val owner = net.neoforged.neoforge.common.util.FakePlayerFactory.get(helper.level, com.mojang.authlib.GameProfile(UUID.randomUUID(), "follow-ride"))
        val start = helper.absoluteVec(Vec3(30.5, 1.0, 80.5))
        owner.moveTo(start.x, start.y, start.z, 0f, 0f)
        helper.level.addNewPlayer(owner)
        val followers = (0 until 4).map { index -> scene.spawn(Vec3(24.5 + index * 2, 1.0, 74.5)) }
        val squad = manager.create(helper.level, owner.uuid, requireNotNull(followers.first().spawnFaction), followers.map { it.uuid })
        val vehicle = requireNotNull(Ports.vehicles.create(helper.level, com.sbwnpc.squad.npc.TransportVehicle.LAV_150))
        val parked = helper.absoluteVec(Vec3(36.5, 1.0, 80.5))
        vehicle.moveTo(parked.x, parked.y, parked.z, 0f, 0f)
        helper.level.addFreshEntity(vehicle)
        Ports.vehicles.fuelAndArm(vehicle, com.sbwnpc.squad.npc.TransportVehicle.LAV_150)
        scene.onClose = {
            owner.stopRiding()
            manager.deleteSquad(helper.level, squad.id)
            vehicle.discard()
            helper.level.removePlayerImmediately(owner, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
        }
        helper.assertTrue(manager.issueFollow(helper.level, squad.id, owner), "The squad could not follow")
        var boardedAt: Long? = null
        var leftAt: Long? = null
        helper.onEachTick {
            val riding = followers.count { it.vehicle === vehicle }
            if (helper.tick % 50L == 0L) scene.result("RUNNING", "tick=${helper.tick} order=${squad.order} riding=$riding ownerIn=${owner.vehicle === vehicle} listening=${followers.count { it.listening() }} aboard=${vehicle.passengers.size}")
            when {
                helper.tick == 100L -> {
                    val feed = com.sbwnpc.squad.map.MapFeed.build(helper.level.server, helper.level, squad.owner, squad.faction)
                    val entry = feed.getList("Squads", net.minecraft.nbt.Tag.TAG_COMPOUND.toInt()).map { it as net.minecraft.nbt.CompoundTag }.single { it.getUUID("Id") == squad.id }
                    scene.result("MAP", "order=${entry.getString("Order")} goalOrder=${entry.getString("GoalOrder")}")
                    helper.assertTrue(entry.getString("Order") == "FOLLOW" && entry.getString("GoalOrder") in setOf("", "FOLLOW"), "Map shows ${entry.getString("Order")}/${entry.getString("GoalOrder")} for a following squad")
                    com.sbwnpc.squad.combat.Hearing.gunshot(helper.level, owner, 64.0)
                }

                helper.tick == 102L -> helper.assertTrue(followers.none { it.listening() }, "The owner's own shot sent his men to look")

                helper.tick == 150L -> {
                    seat(owner, vehicle)
                    helper.assertTrue(owner.vehicle === vehicle, "The owner could not get in")
                }

                boardedAt == null && helper.tick > 150L -> {
                    // A LAV-150 seats the owner and as many of his men as are left seats for.
                    val seats = Ports.vehicles.seatCount(vehicle) - 1
                    if (riding >= minOf(seats, followers.size)) {
                        boardedAt = helper.tick
                        owner.stopRiding()
                    }
                }

                boardedAt != null && leftAt == null && riding == 0 -> {
                    leftAt = helper.tick
                    helper.assertTrue(squad.order == SquadOrder.FOLLOW, "Following ended on the way: ${squad.order}")
                    scene.result("PASS", "tick=${helper.tick} boardedAt=$boardedAt leftAt=$leftAt")
                    helper.succeed()
                }
            }
        }
    }
}
