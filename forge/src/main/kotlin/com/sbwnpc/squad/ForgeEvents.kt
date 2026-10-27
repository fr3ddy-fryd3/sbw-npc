package com.sbwnpc.squad

import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DeathSites
import com.sbwnpc.squad.combat.FiringSpots
import com.sbwnpc.squad.combat.StrayRounds
import com.sbwnpc.squad.combat.TeamAwareness
import com.sbwnpc.squad.entity.DroneRegistry
import com.sbwnpc.squad.entity.GrenadeRegistry
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.entity.ai.DroneLinks
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.VehicleTransportClaims
import com.sbwnpc.squad.experience.ExperienceAwards
import com.sbwnpc.squad.init.ModItems
import com.sbwnpc.squad.squad.PlayerFactionRegistry
import com.sbwnpc.squad.squad.RouteRecording
import com.sbwnpc.squad.squad.SelectionHighlight
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadSelection
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.PilotlessHelicopters
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.entity.EntityJoinLevelEvent
import net.minecraftforge.event.entity.EntityLeaveLevelEvent
import net.minecraftforge.event.entity.living.LivingDamageEvent
import net.minecraftforge.event.entity.living.LivingDeathEvent
import net.minecraftforge.event.entity.player.PlayerEvent
import net.minecraftforge.event.server.ServerStartedEvent
import net.minecraftforge.event.server.ServerStoppedEvent
import net.minecraftforge.eventbus.api.EventPriority
import java.util.function.Consumer

/**
 * The gameplay event hooks, on Forge's event bus. Each handler calls the shared code the NeoForge
 * module calls from its own event handlers.
 */
object ForgeEvents {
    fun register() {
        val bus = MinecraftForge.EVENT_BUS
        bus.addListener(EventPriority.NORMAL, false, TickEvent.ServerTickEvent::class.java, Consumer { event ->
            if (event.phase == TickEvent.Phase.END) onServerTick(event.server)
        })
        bus.addListener(EventPriority.NORMAL, false, ServerStartedEvent::class.java, Consumer { com.sbwnpc.squad.team.Diplomacy.attach(it.server) })
        bus.addListener(EventPriority.NORMAL, false, ServerStoppedEvent::class.java, Consumer { onServerStopped() })

        bus.addListener(EventPriority.HIGHEST, false, EntityJoinLevelEvent::class.java, Consumer(::onEntityJoin))
        bus.addListener(EventPriority.NORMAL, false, EntityLeaveLevelEvent::class.java, Consumer(::onEntityLeave))

        bus.addListener(EventPriority.LOWEST, false, LivingDamageEvent::class.java, Consumer(::onDamage))
        bus.addListener(EventPriority.LOWEST, false, LivingDeathEvent::class.java, Consumer(::onDeath))

        bus.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedInEvent::class.java, Consumer { event ->
            val player = event.entity as? ServerPlayer ?: return@Consumer
            PlayerFactionRegistry.get(player.server).sync(player)
            grantTool(player)
        })
        bus.addListener(EventPriority.NORMAL, false, PlayerEvent.PlayerLoggedOutEvent::class.java, Consumer { event ->
            SquadSelection.clear(event.entity.uuid)
            RouteRecording.cancel(event.entity.uuid)
            com.sbwnpc.squad.map.MapFeed.unsubscribe(event.entity.uuid)
            (event.entity as? ServerPlayer)?.let { forgetContacts(it, "logout") }
        })
        bus.addListener(EventPriority.LOWEST, false, PlayerEvent.PlayerRespawnEvent::class.java, Consumer { event ->
            val player = event.entity as? ServerPlayer ?: return@Consumer
            forgetContacts(player, "respawn")
            if (!event.isEndConquered) grantTool(player)
            ForgeSupplySpawns.respawnAt(player)
        })
    }

    private fun onServerTick(server: net.minecraft.server.MinecraftServer) {
        SelectionHighlight.tick(server)
        PilotlessHelicopters.tick(server)
        com.sbwnpc.squad.team.Diplomacy.tick(server)
        com.sbwnpc.squad.map.MapFeed.tick(server)
        com.sbwnpc.squad.squad.SquadChunkLoader.tick(server)
        com.sbwnpc.squad.squad.OrderArrival.tick(server)
        com.sbwnpc.squad.squad.FollowOrders.tick(server)
        com.sbwnpc.squad.squad.SquadReports.tick(server)
        com.sbwnpc.squad.route.PlanBudget.tick(server)
        StrayRounds.tick(server)
    }

    private fun onServerStopped() {
        com.sbwnpc.squad.team.Diplomacy.detach()
        com.sbwnpc.squad.map.MapFeed.clearAll()
        com.sbwnpc.squad.combat.Withdrawal.clearAll()
        com.sbwnpc.squad.combat.SmokeClouds.clearAll()
        com.sbwnpc.squad.squad.FireSupport.clearAll()
        com.sbwnpc.squad.entity.ai.GrenadeUseBehaviour.clearAll()
        com.sbwnpc.squad.entity.ai.SquadMarch.clearAll()
        com.sbwnpc.squad.entity.ai.BoatTrips.clearAll()
        com.sbwnpc.squad.vehicle.WaterMap.clearAll()
        com.sbwnpc.squad.route.GroundMap.clearAll()
        com.sbwnpc.squad.route.PlanBudget.clearAll()
        com.sbwnpc.squad.squad.SquadChunkLoader.clearAll()
        TeamAwareness.clearAll()
        com.sbwnpc.squad.combat.SquadAreas.clearAll()
        com.sbwnpc.squad.combat.tactics.SquadTactics.clearAll()
        com.sbwnpc.squad.squad.SquadReports.clearAll()
        com.sbwnpc.squad.combat.Hearing.clearAll()
        DeathSites.clearAll()
        MortarClaims.clearAll()
        DroneLinks.clearAll()
        VehicleTransportClaims.clearAll()
        SquadSelection.clearAll()
        RouteRecording.clearAll()
        SquadManager.clearCache()
        NpcRegistry.clearAll()
        PilotlessHelicopters.clear()
        FiringSpots.clearAll()
        DroneRegistry.clearAll()
        GrenadeRegistry.clearAll()
        com.sbwnpc.squad.entity.SmokeCloudRegistry.clearAll()
        StrayRounds.clearAll()
        SquadTeams.clearCache()
    }

    private fun onEntityJoin(event: EntityJoinLevelEvent) {
        val level = event.level as? ServerLevel ?: return
        val entity = event.entity
        if (SquadManager.get(level).discardDeletedEntity(entity)) {
            com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.ORDER, "[squad] event=deleted_on_load entity={} type={}", entity.uuid, entity.type)
            event.isCanceled = true
            return
        }
        if (entity is NpcEntity) {
            val manager = SquadManager.get(level)
            manager.reconcileMembership(entity)
            manager.deliverExperience(entity)
            NpcRegistry.add(entity)
        }
        GrenadeRegistry.join(entity, level)
        com.sbwnpc.squad.entity.SmokeCloudRegistry.join(entity, level)
        DroneRegistry.join(entity, level)
        StrayRounds.join(entity, level)
        val projectile = entity as? Projectile ?: return
        val owner = projectile.owner as? NpcEntity ?: return
        val tag = net.minecraft.nbt.CompoundTag()
        ExperienceAwards.shares(owner, projectile).forEach { (id, share) -> tag.putDouble(id.toString(), share) }
        com.sbwnpc.squad.domain.port.Ports.loader.persistentData(projectile).put("sbwnpcExperienceCrew", tag)
    }

    private fun onEntityLeave(event: EntityLeaveLevelEvent) {
        val level = event.level as? ServerLevel ?: return
        val entity: Entity = event.entity
        if (entity is NpcEntity) {
            com.sbwnpc.squad.entity.ai.SquadMarch.forgetIndividual(entity.uuid)
            NpcRegistry.remove(entity)
        }
        GrenadeRegistry.leave(entity, level)
        com.sbwnpc.squad.entity.SmokeCloudRegistry.leave(entity, level)
        com.sbwnpc.squad.combat.SmokeClouds.leave(entity, level)
        DroneRegistry.leave(entity, level)
        StrayRounds.leave(entity, level)
    }

    /** Experience for damage dealt: the health lost is what the hit takes off, capped at what the victim had. */
    private fun onDamage(event: LivingDamageEvent) {
        val entity = event.entity
        if (entity.level().isClientSide || entity.maxHealth <= 0) return
        val lost = minOf(entity.health, event.amount).coerceAtLeast(0f)
        ExperienceAwards.damage(entity, event.source, lost / entity.maxHealth.toDouble(), entity.health - event.amount <= 0f)
    }

    private fun onDeath(event: LivingDeathEvent) {
        val entity = event.entity
        val level = entity.level() as? ServerLevel ?: return
        com.sbwnpc.squad.combat.tactics.SquadTactics.targetDeath(entity) { event.isCanceled }
        SquadManager.get(level).clearDeadFocus(entity.uuid, BlockPos.containing(entity.position()))
        (entity as? ServerPlayer)?.let { forgetContacts(it, "death") }
    }

    private fun forgetContacts(player: ServerPlayer, reason: String) {
        val id = player.uuid
        TeamAwareness.forget(id)
        for (squad in SquadManager.get(player.server).all()) {
            val state = squad.tactics
            if (state.contacts.remove(id) != null || state.snapshot?.contacts?.any { it.id == id } == true) {
                state.snapshot = state.snapshot?.let { view -> view.copy(contacts = view.contacts.filter { it.id != id }) }
                state.nextAssessment = Long.MIN_VALUE
            }
        }
        for (level in player.server.allLevels) {
            for (npc in NpcRegistry.all(level)) {
                if (npc.target?.uuid == id) net.tslat.smartbrainlib.util.BrainUtils.setTargetOfEntity(npc, null)
                if (npc.lastHurtByMob?.uuid == id) npc.lastHurtByMob = null
                npc.forgetContact(id)
            }
        }
        com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.ORDER, "[tactics] event=player_contact_reset target={} reason={}", id, reason)
    }

    /** Survival players get the command tool when they carry none: it has no recipe. */
    private fun grantTool(player: ServerPlayer) {
        if (player.isCreative || player.isSpectator) return
        val tool = ModItems.SQUAD_TOOL.get()
        if (player.inventory.contains(ItemStack(tool)) || player.inventory.items.any { it.`is`(tool) }) return
        if (!player.inventory.add(ItemStack(tool))) player.drop(ItemStack(tool), false)
    }
}
