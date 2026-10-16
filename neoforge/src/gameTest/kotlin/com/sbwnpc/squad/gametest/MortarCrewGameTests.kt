package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.init.ModBlocks
import com.sbwnpc.squad.init.ModEntities
import com.sbwnpc.squad.npc.NpcClass
import com.sbwnpc.squad.npc.SquadFaction
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.squad.SquadOrder
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.*
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.function.Consumer

@PrefixGameTestTemplate(false)
object MortarCrewGameTests {
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1600, batch = "mortar-gunner-loss")
    fun mortarLoaderTakesOverAtThirdCadence(helper: GameTestHelper) = cadence(helper, MortarClaims.Role.OPERATOR)

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1600, batch = "mortar-loader-loss")
    fun mortarGunnerLoadsAloneAtThirdCadence(helper: GameTestHelper) = cadence(helper, MortarClaims.Role.LOADER)

    private fun cadence(helper: GameTestHelper, lost: MortarClaims.Role) {
        val scene = scene(helper)
        val shots = mutableListOf<Long>()
        val owners = mutableListOf<UUID?>()
        var survivor: NpcEntity? = null
        var reinforced = false
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val shell = event.entity as? MortarShellEntity ?: return@Consumer
            if (event.level === helper.level && shell.owner?.uuid in scene.crew.map { it.uuid }) {
                shots += helper.tick
                owners += shell.owner?.uuid
            }
        }
        NeoForge.EVENT_BUS.addListener(listener)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(listener)
            scene.close()
            report("mortar-cadence-$lost.txt", "shots=$shots\nowners=$owners\nsurvivor=${survivor?.uuid}\n")
        }
        helper.onEachTick {
            if (shots.size >= 3 && survivor == null) {
                helper.assertTrue(shots.take(3).zipWithNext().all { (a, b) -> b - a in 100..108 }, "Full crew cadence is not five seconds: $shots")
                val victimId = if (lost == MortarClaims.Role.OPERATOR) MortarClaims.operator(scene.mortar.uuid) else MortarClaims.loader(scene.mortar.uuid)
                val victim = scene.crew.firstOrNull { it.uuid == victimId }
                helper.assertTrue(victim != null, "Crew posts were not assigned")
                survivor = scene.crew.first { it !== victim }
                victim!!.kill()
            }
            if (shots.size >= 5 && !reinforced) {
                helper.assertTrue(shots.subList(2, 5).zipWithNext().all { (a, b) -> b - a in 300..308 }, "Surviving crew did not fire at one third cadence: $shots")
                helper.assertTrue(owners.takeLast(2).all { it == survivor!!.uuid }, "The survivor did not own the real shells")
                val npc = AttackDeploymentGameTests.spawn(helper, helper.relativeVec(scene.mortar.position()).add(0.0, 0.0, 1.8), SquadFaction.PIG, role = NpcClass.MORTAR)
                scene.crew += npc
                npc.squadId = scene.squad.id
                scene.squad.members += npc.uuid
                reinforced = true
            }
            if (shots.size >= 7) {
                helper.assertTrue(shots.subList(4, 7).zipWithNext().all { (a, b) -> b - a in 100..108 }, "Second crew member reset/did not restore the shared timer: $shots")
                helper.assertTrue(MortarClaims.activeCrew(scene.mortar) == 2, "Reinforcement is not physically working at the tube")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1500, batch = "mortar-shell-supply")
    fun mortarCrewReturnsFromSupplyToItsAssignedTube(helper: GameTestHelper) {
        val scene = scene(helper)
        val point = BlockPos(5, 1, 20)
        helper.setBlock(point, ModBlocks.SUPPLY.get())
        (helper.getBlockEntity(point) as SupplyBlockEntity).faction = SquadFaction.PIG
        var away: NpcEntity? = null
        var origin: Vec3? = null
        var traveled = false
        var returned = false
        var soloObserved = false
        val trace = StringBuilder("tick,x,y,z,shells,serving,errand,navDone,toSupply,toTube\n")
        var singleShots = 0
        var restoredShots = 0
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val shell = event.entity as? MortarShellEntity ?: return@Consumer
            if (event.level !== helper.level || shell.owner?.uuid !in scene.crew.map { it.uuid }) return@Consumer
            if (traveled && !returned) singleShots++
            if (returned) restoredShots++
        }
        NeoForge.EVENT_BUS.addListener(listener)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(listener)
            scene.close()
            helper.setBlock(point, Blocks.AIR)
            report("mortar-supply.csv", trace.toString())
            report("mortar-supply.txt", "traveled=$traveled returned=$returned soloShots=$singleShots restoredShots=$restoredShots\n")
        }
        helper.onEachTick {
            if (away == null && MortarClaims.activeCrew(scene.mortar) == 2 && Ports.mortars.isFiring(scene.mortar)) {
                away = scene.crew.first { it.uuid == MortarClaims.operator(scene.mortar.uuid) }
                origin = away!!.position()
                away!!.mortarShellsLeft = 0
            }
            away?.let { npc ->
                val at = helper.relativeVec(npc.position())
                trace.append("${helper.tick},${at.x},${at.y},${at.z},${npc.mortarShellsLeft},${npc.servingMortar},${npc.mortarErrand},${npc.navigation.isDone},${npc.position().distanceTo(helper.absolutePos(point).center)},${npc.distanceTo(scene.mortar)}\n")
                helper.assertTrue(npc.assignedMortarId == scene.mortar.uuid, "Supply errand lost the original tube assignment")
                if (npc.position().distanceTo(origin!!) > 10.0) traveled = true
                if (traveled && npc.distanceToSqr(scene.mortar) > 6.25 && MortarClaims.activeCrew(scene.mortar) == 1) soloObserved = true
                if (traveled && npc.mortarShellsLeft > 0 && MortarClaims.atPost(npc, scene.mortar)) returned = true
            }
            if (returned && restoredShots >= 2) {
                helper.assertTrue(soloObserved, "Absent supply runner still counted as working crew")
                helper.assertTrue(MortarClaims.activeCrew(scene.mortar) == 2, "Returned loader did not restore the working crew")
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1500, batch = "mortar-relocation")
    fun mortarRelocatesWithoutLosingLoadedShellAndSaveState(helper: GameTestHelper) {
        val scene = scene(helper)
        var expected: Int? = null
        var lastShot: Long? = null
        val deployedIds = HashSet<UUID>()
        val spawnListener = Consumer<EntityJoinLevelEvent> { event ->
            if (event.level === helper.level && Ports.mortars.isMortar(event.entity)) deployedIds += event.entity.uuid
        }
        NeoForge.EVENT_BUS.addListener(spawnListener)
        var carrying = false
        var deployed: Entity? = null
        cleanup(helper) {
            scene.close()
            deployed?.discard()
        }
        helper.onEachTick {
            if (expected == null && scene.mortar.persistentData.contains("sbwnpcLastMortarShot") && !Ports.mortars.isFiring(scene.mortar) && Ports.mortars.hasShell(scene.mortar)) {
                expected = scene.crew.sumOf { it.mortarShellsLeft } + 1
                lastShot = scene.mortar.persistentData.getLong("sbwnpcLastMortarShot")
                scene.manager.setOrder(scene.squad.id, SquadOrder.DEFEND)
                scene.manager.setObjective(helper.level, scene.squad.id, helper.absolutePos(BlockPos(55, 1, 64)))
            }
            scene.crew.firstOrNull { it.carryingMortar }?.let { npc ->
                carrying = true
                val tag = CompoundTag()
                npc.saveWithoutId(tag)
                val loaded = ModEntities.NPC.get().create(helper.level)!!
                loaded.load(tag)
                helper.assertTrue(loaded.carryingMortar && loaded.assignedMortarId == npc.assignedMortarId && loaded.packedMortarState == npc.packedMortarState, "Carrier save lost tube state")
                for ((name, ordinal) in listOf("MORTAR_OPERATOR" to 5, "MORTAR_LOADER" to 6)) {
                    tag.putString("NpcClass", name)
                    tag.putInt("MortarShells", 0)
                    loaded.load(tag)
                    helper.assertTrue(loaded.mortarShellsLeft == (if (name == "MORTAR_OPERATOR") NpcEntity.MORTAR_SHELLS else 0), "Legacy migration reset a spent loader or stranded an unequipped operator")
                    helper.assertTrue(loaded.npcClass == NpcClass.MORTAR && NpcClass.byOrdinal(ordinal) == NpcClass.MORTAR, "Legacy crew did not migrate")
                }
                helper.assertTrue(NpcClass.TANK_CREW.ordinal == 7 && NpcClass.DRONE_OPERATOR.ordinal == 10 && NpcClass.selectable.none { it.name == "MORTAR_OPERATOR" || it.name == "MORTAR_LOADER" }, "Role migration shifted unrelated IDs or exposed legacy types")
            }
            if (carrying && scene.crew.none { it.carryingMortar }) {
                deployed = scene.crew.first().assignedMortarId?.let(helper.level::getEntity)
                val tube = deployed ?: return@onEachTick
                helper.assertTrue(tube.uuid != scene.mortar.uuid && tube.position().distanceTo(helper.absoluteVec(Vec3(55.5, 1.0, 64.5))) <= 8.0, "Mortar did not physically redeploy at the new objective")
                helper.assertTrue(tube.persistentData.getLong("sbwnpcLastMortarShot") == lastShot, "Relocation reset the accepted-shot timer")
                helper.assertTrue(deployedIds.size == 1, "Tube repeatedly packed and redeployed around the arrival threshold")
                helper.assertTrue(scene.crew.all { it.assignedMortarId == tube.uuid }, "Second soldier lost redeployed tube")
                helper.assertTrue(scene.crew.sumOf { it.mortarShellsLeft } + (if (Ports.mortars.hasShell(tube)) 1 else 0) == expected, "Packing lost or duplicated shells")
                if (MortarClaims.activeCrew(tube) == 2) helper.succeed()
            }
        }
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "mortar-two-tubes")
    fun twoMortarsKeepIndependentCrewsAndTimers(helper: GameTestHelper) {
        val scene = scene(helper)
        val second = Ports.mortars.create(helper.level, -90f)
        val pos = helper.absoluteVec(Vec3(24.5, 1.0, 84.5))
        second.moveTo(pos.x, pos.y, pos.z, -90f, 0f)
        helper.level.addFreshEntity(second)
        SquadTeams.assign(second, SquadFaction.PIG)
        for (at in listOf(Vec3(22.5, 1.0, 84.5), Vec3(24.5, 1.0, 86.3))) {
            val npc = AttackDeploymentGameTests.spawn(helper, at, SquadFaction.PIG, role = NpcClass.MORTAR)
            scene.crew += npc
            scene.squad.members += npc.uuid
            npc.squadId = scene.squad.id
        }
        val tubes = listOf(scene.mortar, second)
        val shots = IntArray(2)
        val listener = Consumer<EntityJoinLevelEvent> { event ->
            val shell = event.entity as? MortarShellEntity ?: return@Consumer
            if (event.level !== helper.level) return@Consumer
            val index = tubes.indexOfFirst { it.position().distanceTo(shell.position()) < 3.0 }
            if (index >= 0) shots[index]++
        }
        NeoForge.EVENT_BUS.addListener(listener)
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(listener)
            scene.close()
            MortarClaims.releaseMortar(second.uuid)
            second.discard()
        }
        helper.onEachTick {
            val holders = tubes.flatMap { listOfNotNull(MortarClaims.operator(it.uuid), MortarClaims.loader(it.uuid)) }
            helper.assertTrue(holders.size == holders.toSet().size, "One soldier claimed posts at two different mortars")
            if (shots.all { it >= 2 } && helper.tick >= 200) {
                helper.assertTrue(tubes.all { MortarClaims.activeCrew(it) == 2 }, "One tube stole the other's crew")
                helper.assertTrue(scene.crew.all { npc -> tubes.any { it.uuid == npc.assignedMortarId && MortarClaims.atPost(npc, it) } }, "Crew assignments do not match the physical posts")
                helper.succeed()
            }
        }
    }

    private class Scene(val helper: GameTestHelper, val mortar: Entity, val crew: MutableList<NpcEntity>) {
        val manager = SquadManager.get(helper.level)
        val squad = manager.create(helper.level, UUID.randomUUID(), SquadFaction.PIG, crew.map { it.uuid })
        fun close() {
            manager.deleteSquad(helper.level, squad.id)
            crew.forEach {
                MortarClaims.release(it.uuid)
                it.discard()
            }
            MortarClaims.releaseMortar(mortar.uuid)
            mortar.discard()
        }
    }
    private fun scene(helper: GameTestHelper): Scene {
        for (x in 0..159) for (z in 0..127) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        DebugFlags.enabledGroups = setOf(LogGroup.FIRE_SUPPORT, LogGroup.ORDER)
        val tube = Ports.mortars.create(helper.level, -90f)
        val pos = helper.absoluteVec(Vec3(24.5, 1.0, 64.5))
        tube.moveTo(pos.x, pos.y, pos.z, -90f, 0f)
        helper.level.addFreshEntity(tube)
        SquadTeams.assign(tube, SquadFaction.PIG)
        val crew = listOf(Vec3(22.5, 1.0, 64.5), Vec3(24.5, 1.0, 66.3)).map {
            AttackDeploymentGameTests.spawn(helper, it, SquadFaction.PIG, role = NpcClass.MORTAR)
        }.toMutableList()
        val scene = Scene(helper, tube, crew)
        scene.manager.setOrder(scene.squad.id, SquadOrder.ATTACK)
        scene.manager.setObjective(helper.level, scene.squad.id, helper.absolutePos(BlockPos(105, 1, 64)))
        helper.assertTrue(Ports.mortars.canReach(tube, scene.squad.objective!!), "Mortar fixture is outside real ballistic limits")
        return scene
    }
    private fun report(name: String, content: String) {
        val path = Path.of(System.getProperty("sbwnpc.gameTestReports"), name)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
    private fun cleanup(helper: GameTestHelper, action: () -> Unit) {
        helper.testInfo.addListener(object : GameTestListener {
            override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
            override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = action()
            override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = action()
        })
    }
}
