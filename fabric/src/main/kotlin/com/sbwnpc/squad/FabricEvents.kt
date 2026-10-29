package com.sbwnpc.squad

import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.block.entity.SupplyPoints
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
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.item.ItemStack
import java.util.WeakHashMap

/**
 * The gameplay event hooks NeoForge delivers as bus events, on Fabric API's events and, where it has
 * none, on this module's mixins. Each handler calls the shared code the NeoForge module calls.
 */
object FabricEvents {
    private val healthBefore = WeakHashMap<LivingEntity, Float>()

    fun register() {
        ServerTickEvents.END_SERVER_TICK.register(::onServerTick)
        ServerLifecycleEvents.SERVER_STARTED.register { com.sbwnpc.squad.team.Diplomacy.attach(it) }
        ServerLifecycleEvents.SERVER_STOPPED.register { onServerStopped() }

        ServerEntityEvents.ENTITY_LOAD.register(::onEntityLoad)
        ServerEntityEvents.ENTITY_UNLOAD.register(::onEntityUnload)
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register { entity, level ->
            if (entity is SupplyBlockEntity) SupplyPoints.get(level).remember(entity.blockPos, entity.faction)
        }

        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            healthBefore[entity] = entity.health
            true
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, damageTaken, _ -> afterDamage(entity, source, damageTaken) }
        ServerLivingEntityEvents.AFTER_DEATH.register(::onDeath)

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            PlayerFactionRegistry.get(player.server).sync(player)
            grantTool(player)
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            SquadSelection.clear(player.uuid)
            RouteRecording.cancel(player.uuid)
            com.sbwnpc.squad.map.MapFeed.unsubscribe(player.uuid)
            forgetContacts(player, "logout")
        }
        ServerPlayerEvents.AFTER_RESPAWN.register { _, player, alive ->
            forgetContacts(player, "respawn")
            if (!alive) grantTool(player)
            FabricSupplySpawns.respawnAt(player)
        }
    }

    private fun onServerTick(server: net.minecraft.server.MinecraftServer) {
        SelectionHighlight.tick(server)
        com.sbwnpc.squad.squad.DeployPreview.tick(server)
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
        healthBefore.clear()
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

    private fun onEntityLoad(entity: Entity, level: ServerLevel) {
        // NeoForge cancels the join; Fabric has no such event, so a deleted squad's entity is removed on arrival.
        if (SquadManager.get(level).discardDeletedEntity(entity)) {
            com.sbwnpc.squad.combat.DebugFlags.log(com.sbwnpc.squad.combat.LogGroup.ORDER, "[squad] event=deleted_on_load entity={} type={}", entity.uuid, entity.type)
            entity.discard()
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

    private fun onEntityUnload(entity: Entity, level: ServerLevel) {
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

    private fun afterDamage(entity: LivingEntity, source: DamageSource, damageTaken: Float) {
        val before = healthBefore.remove(entity) ?: return
        val lost = minOf(before, damageTaken).coerceAtLeast(0f)
        if (entity.maxHealth > 0) ExperienceAwards.damage(entity, source, lost / entity.maxHealth.toDouble(), entity.health <= 0f)
    }

    private fun onDeath(entity: LivingEntity, @Suppress("UNUSED_PARAMETER") source: DamageSource) {
        val level = entity.level() as? ServerLevel ?: return
        com.sbwnpc.squad.combat.tactics.SquadTactics.targetDeath(entity) { false }
        SquadManager.get(level).clearDeadFocus(entity.uuid, BlockPos.containing(entity.position()))
        (entity as? ServerPlayer)?.let { forgetContacts(it, "death") }
    }

    /** Player contacts describe a life, not the persistent profile UUID used by the next entity. */
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
        val tool = ModItems.SQUAD_TOOL
        if (player.inventory.contains { it.`is`(tool) }) return
        if (!player.inventory.add(ItemStack(tool))) player.drop(ItemStack(tool), false)
    }
}
