package com.sbwnpc.squad.squad

import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.item.SquadToolItem
import com.sbwnpc.squad.npc.HelicopterModel
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.NpcRank
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.npc.SquadPreset
import com.sbwnpc.squad.npc.TankModel
import com.sbwnpc.squad.npc.TransportVehicle
import com.sbwnpc.squad.team.SquadTeams
import com.sbwnpc.squad.vehicle.Helicopters
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.MobSpawnType
import net.minecraft.world.entity.Pose
import java.util.UUID

/**
 * Turns a [SquadToolItem.Config] into NPCs on the ground, their support vehicle, and the squad
 * around them.
 *
 * Lifted out of [SquadToolItem] when the Barracks gained the same settings the deploy tool has:
 * both now describe a deployment the same way and hand it here, so a preset behaves identically
 * whether a player clicked it into the world or a block did.
 */
object SquadDeployment {

    /** [vehicleBlocked] is true when the preset called for a vehicle and there was nowhere within
     *  reach to put one — the infantry still deploys, but the caller should say so. */
    class Result(val members: List<NpcEntity>, val squad: Squad?, val vehicleBlocked: Boolean = false)

    /**
     * Deploys [cfg]'s preset centred on [pos], facing away from [facingYaw], and forms a squad
     * around it when the preset is more than one NPC. Returns null if nothing could be spawned.
     */
    fun deploy(level: ServerLevel, pos: BlockPos, facingYaw: Float, cfg: SquadToolItem.Config, owner: UUID): Result? {
        val composition = when (cfg.preset) {
            SquadPreset.SINGLE -> listOf(cfg.cls)
            // Who flies out depends on which airframe was picked, not on the preset alone.
            SquadPreset.HELI_CREW -> cfg.heliModel.crew
            else -> cfg.preset.composition
        }
        val difficulty = level.getCurrentDifficultyAt(pos)
        val spawned = deployLine(level, pos, facingYaw, composition, cfg.rank, cfg.faction, difficulty, cfg.preset.spacing)
        if (spawned.isEmpty()) return null

        val vehiclePlaced = when (cfg.preset) {
            SquadPreset.MORTAR_CREW -> spawnMortar(level, pos, facingYaw, cfg.faction)
            SquadPreset.T90_CREW -> spawnTankCrew(level, pos, facingYaw, cfg.faction, spawned, cfg.tankModel)
            SquadPreset.HELI_CREW -> spawnHeliCrew(level, pos, facingYaw, cfg.faction, spawned, cfg.heliModel)
            // Unmanned — left for the squad's own vehicle-transport/combat-support AI to claim,
            // same as any vehicle it finds parked in the world.
            SquadPreset.FIVE -> !cfg.vehicle || spawnTransport(level, pos, facingYaw, cfg.faction, cfg.vehicleModel)
            SquadPreset.SEVEN -> !cfg.vehicle || spawnTransport(level, pos, facingYaw, cfg.faction, TransportVehicle.BMP_2)
            else -> true
        }

        val squad = if (spawned.size > 1 || cfg.preset == SquadPreset.T90_CREW) {
            val mgr = SquadManager.get(level)
            mgr.create(level, owner, cfg.faction, spawned.map { it.uuid }).also {
                // Defend right where it was deployed by default — see SquadManager.create's
                // initialOrder — rather than a DEFEND with nothing to actually guard.
                mgr.setObjective(level, it.id, pos)
            }
        } else null
        return Result(spawned, squad, vehicleBlocked = !vehiclePlaced)
    }

    /** Spawns [composition] side by side, centred on [center] and facing the player, perpendicular
     *  to the direction they're looking — a natural "line abreast" for a squad, and identical to a
     *  single deploy when composition has one entry. */
    fun deployLine(
        level: ServerLevel,
        center: BlockPos,
        facingYaw: Float,
        composition: List<NpcClass>,
        rank: NpcRank,
        faction: SquadFaction,
        difficulty: net.minecraft.world.DifficultyInstance,
        spacing: Double = 2.0
    ): List<NpcEntity> {
        val yawRad = Math.toRadians(facingYaw.toDouble())
        val rightX = Math.cos(yawRad)
        val rightZ = Math.sin(yawRad)
        val n = composition.size

        val result = mutableListOf<NpcEntity>()
        for ((i, cls) in composition.withIndex()) {
            val offset = (i - (n - 1) / 2.0) * spacing
            val npc = ModEntities.NPC.get().create(level) ?: continue
            val spawnX = center.x + 0.5 + rightX * offset
            val spawnZ = center.z + 0.5 + rightZ * offset
            // A line spread sideways from the click point can easily cross a step, overhang, or
            // wall — without this, a member off to either side could spawn with its feet inside a
            // solid block and suffocate before doing anything at all.
            val spawnY = SafeSpawn.findSafeY(level, spawnX, spawnZ, center.y, npc.getDimensions(Pose.STANDING)) ?: center.y.toDouble()
            npc.moveTo(spawnX, spawnY, spawnZ, facingYaw + 180f, 0f)
            npc.npcClass = cls
            npc.npcRank = rank
            npc.spawnFaction = faction
            npc.finalizeSpawn(level, difficulty, MobSpawnType.SPAWN_EGG, null)
            level.addFreshEntity(npc)
            result.add(npc)
        }
        return result
    }

    private fun spawnMortar(level: ServerLevel, center: BlockPos, yaw: Float, faction: SquadFaction): Boolean {
        val mortar = Ports.mortars.create(level, yaw + 180f)
        val spot = SafeSpawn.findClearSpot(
            level, center.x + 0.5, center.z + 0.5, center.y, mortar.getDimensions(Pose.STANDING)
        ) ?: return false
        mortar.moveTo(spot.x, spot.y, spot.z, yaw + 180f, 0f)
        level.addFreshEntity(mortar)
        SquadTeams.assign(mortar, faction)
        return true
    }

    private fun spawnTankCrew(
        level: ServerLevel,
        center: BlockPos,
        yaw: Float,
        faction: SquadFaction,
        crew: List<NpcEntity>,
        model: TankModel
    ): Boolean {
        val tank = Ports.vehicles.create(level, model) ?: return false
        // Off the deploy point, not on it — spawned right where the player clicked, a tank this
        // size drops/lands right on top of them. A tank's footprint is far wider than the block it
        // is placed on, so the spot has to be searched for rather than assumed.
        val standoff = 8.0
        val yawRad = Math.toRadians(yaw.toDouble())
        val tx = center.x + 0.5 - Math.sin(yawRad) * standoff
        val tz = center.z + 0.5 + Math.cos(yawRad) * standoff
        val spot = SafeSpawn.findClearSpot(level, tx, tz, center.y, tank.getDimensions(Pose.STANDING))
            ?: return false
        tank.moveTo(spot.x, spot.y, spot.z, yaw + 180f, 0f)
        level.addFreshEntity(tank)
        Ports.vehicles.fuelAndArm(tank, model)
        SquadTeams.assign(tank, faction)
        crew.singleOrNull()?.let { crewman ->
            if (crewman.startRiding(tank, false)) {
                crewman.assignedVehicleId = tank.uuid
            }
        }
        return true
    }

    /**
     * Helicopter plus its crew. Unlike every other vehicle here it is seated immediately and
     * deliberately: an SBW helicopter with no first passenger has its controls zeroed and its rotor
     * bled off every tick, so an unmanned one would just settle back onto the ground.
     */
    private fun spawnHeliCrew(
        level: ServerLevel,
        center: BlockPos,
        yaw: Float,
        faction: SquadFaction,
        crew: List<NpcEntity>,
        model: HelicopterModel
    ): Boolean {
        val heli = Ports.vehicles.create(level, model) ?: return false
        // Well off the deploy point, and high enough that the rotor isn't inside the canopy the
        // player happened to be standing under.
        val standoff = 12.0
        val yawRad = Math.toRadians(yaw.toDouble())
        val hx = center.x + 0.5 - Math.sin(yawRad) * standoff
        val hz = center.z + 0.5 + Math.cos(yawRad) * standoff
        // Ground clearance for the hull first — a helicopter dropped into a treeline is stuck
        // there — then the rotor clearance on top of whatever spot that found.
        val spot = SafeSpawn.findClearSpot(level, hx, hz, center.y, heli.getDimensions(Pose.STANDING))
            ?: return false
        val ground = Helicopters.groundY(level, spot.x, spot.z)
        val hy = Helicopters.clearSpawnY(level, spot.x, spot.z, maxOf(ground, spot.y.toInt()))
        heli.moveTo(spot.x, hy, spot.z, yaw + 180f, 0f)
        level.addFreshEntity(heli)
        Ports.vehicles.fuelAndArm(heli, model)
        SquadTeams.assign(heli, faction)

        // Pilot first so it takes seat 0 — SBW treats the first passenger as the one flying. The
        // gunship's gunner goes straight into the turret seat; the transport's riflemen walk
        // aboard themselves when the squad is actually sent somewhere (HelicopterRideBehaviour).
        val pilot = crew.firstOrNull { it.npcClass == NpcClass.HELICOPTER_PILOT }
        val gunner = crew.firstOrNull { it.npcClass == NpcClass.HELICOPTER_GUNNER }
        for (member in listOfNotNull(pilot, gunner)) {
            if (member.startRiding(heli, false)) {
                member.assignedVehicleId = heli.uuid
            }
        }
        return true
    }

    /** Unmanned transport for a [SquadPreset.FIVE]/[SquadPreset.SEVEN] squad — the squad's own
     *  VehicleTransportBehaviour/VehicleCombatSupportBehaviour finds and boards it like any other
     *  vehicle parked nearby; no crew is seated here. */
    private fun spawnTransport(level: ServerLevel, center: BlockPos, yaw: Float, faction: SquadFaction, model: TransportVehicle): Boolean {
        val vehicle = Ports.vehicles.create(level, model) ?: return false
        // Off the squad's own spawn line (perpendicular to it), not at its center — FIVE/SEVEN are
        // both odd-sized, so a member always lands exactly on center and the vehicle would spawn
        // on top of them.
        val standoff = 6.0
        val yawRad = Math.toRadians(yaw.toDouble())
        val forwardX = -Math.sin(yawRad)
        val forwardZ = Math.cos(yawRad)
        val vx = center.x + 0.5 + forwardX * standoff
        val vz = center.z + 0.5 + forwardZ * standoff
        val spot = SafeSpawn.findClearSpot(level, vx, vz, center.y, vehicle.getDimensions(Pose.STANDING))
            ?: return false
        vehicle.moveTo(spot.x, spot.y, spot.z, yaw + 180f, 0f)
        level.addFreshEntity(vehicle)
        Ports.vehicles.fuelAndArm(vehicle, model)
        SquadTeams.assign(vehicle, faction)
        return true
    }
}
