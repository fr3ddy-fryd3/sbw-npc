package com.sbwnpc.squad.gametest

import com.atsuishio.superbwarfare.entity.projectile.MortarShellEntity
import com.sbwnpc.squad.ai.integration.NativeTactics
import com.sbwnpc.squad.ai.planning.ExecutionStatus
import com.sbwnpc.squad.ai.tactics.Engage
import com.sbwnpc.squad.block.entity.SupplyBlockEntity
import com.sbwnpc.squad.combat.DebugFlags
import com.sbwnpc.squad.combat.LogGroup
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcActivity
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.ai.MortarClaims
import com.sbwnpc.squad.entity.ai.MortarDeployment
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
        val scene = Scene.create(helper)
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
        val scene = Scene.create(helper)
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
                trace.append("${helper.tick},${at.x},${at.y},${at.z},${npc.mortarShellsLeft},${npc.holds(NpcActivity.MORTAR_CREW)},${npc.holds(NpcActivity.MORTAR_ERRAND)},${npc.navigation.isDone},${npc.position().distanceTo(helper.absolutePos(point).center)},${npc.distanceTo(scene.mortar)}\n")
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

    /**
     * Ordered to a new point 30 blocks off, the crew packs its tube, carries it and sets it up
     * there once: no shell lost or duplicated in the packing, the shot timer kept, both men on the
     * new tube.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 1500, batch = "mortar-relocation")
    fun mortarRelocatesWithoutLosingItsShellsOrTimer(helper: GameTestHelper) {
        val scene = Scene.create(helper)
        var expected: Int? = null
        var lastShot: Long? = null
        val deployedIds = HashSet<UUID>()
        val spawnListener = Consumer<EntityJoinLevelEvent> { event ->
            if (event.level === helper.level && Ports.mortars.isMortar(event.entity)) deployedIds += event.entity.uuid
        }
        NeoForge.EVENT_BUS.addListener(spawnListener)
        var carrying = false
        var deployed: Entity? = null
        val point = helper.absoluteVec(Vec3(55.5, 1.0, 64.5))
        cleanup(helper) {
            NeoForge.EVENT_BUS.unregister(spawnListener)
            scene.close()
            deployed?.discard()
        }
        helper.onEachTick {
            if (expected == null && scene.mortar.persistentData.contains("sbwnpcLastMortarShot") && !Ports.mortars.isFiring(scene.mortar) && Ports.mortars.hasShell(scene.mortar)) {
                expected = scene.crew.sumOf { it.mortarShellsLeft } + 1
                lastShot = scene.mortar.persistentData.getLong("sbwnpcLastMortarShot")
                scene.manager.setOrder(scene.squad.id, SquadOrder.DEFEND)
                scene.manager.setObjective(helper.level, scene.squad.id, BlockPos.containing(point))
            }
            carrying = carrying || scene.crew.any { it.holds(NpcActivity.CARRYING_MORTAR) }
            if (helper.tick % 100L == 0L) {
                report("mortar-relocation.txt", "result=RUNNING tick=${helper.tick} ordered=${expected != null} carrying=$carrying crew=${scene.crew.map { "${helper.relativeVec(it.position())} carry=${it.holds(NpcActivity.CARRYING_MORTAR)} tube=${it.assignedMortarId} shells=${it.mortarShellsLeft}" }} tubes=${deployedIds.size}")
            }
            if (!carrying || scene.crew.any { it.holds(NpcActivity.CARRYING_MORTAR) }) return@onEachTick
            deployed = scene.crew.first().assignedMortarId?.let(helper.level::getEntity)
            val tube = deployed ?: return@onEachTick
            helper.assertTrue(tube.uuid != scene.mortar.uuid && tube.position().distanceTo(point) <= 8.0, "Mortar did not physically redeploy at the new objective")
            helper.assertTrue(tube.persistentData.getLong("sbwnpcLastMortarShot") == lastShot, "Relocation reset the accepted-shot timer")
            helper.assertTrue(deployedIds.size == 1, "Tube repeatedly packed and redeployed around the arrival threshold")
            scene.assertCrewKept(tube, expected)
            if (MortarClaims.activeCrew(tube) == 2) {
                report("mortar-relocation.txt", "result=PASS tick=${helper.tick} tube=${helper.relativeVec(tube.position())}")
                helper.succeed()
            }
        }
    }

    /** A carrier's save keeps the tube he carries; a save from before the crew roles merged loads them as mortarmen. */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 40, batch = "mortar-carrier-save")
    fun mortarCarrierSaveKeepsTheTubeAndLegacyCrewMigrates(helper: GameTestHelper) {
        val carrier = AttackDeploymentGameTests.spawn(helper, Vec3(20.5, 1.0, 64.5), SquadFaction.PIG, role = NpcClass.MORTAR)
        cleanup(helper) { carrier.discard() }
        carrier.claim(NpcActivity.CARRYING_MORTAR, MortarDeployment)
        carrier.assignedMortarId = UUID.randomUUID()
        carrier.packedMortarState = CompoundTag().apply { putInt("probe", 7) }
        val tag = CompoundTag()
        carrier.saveWithoutId(tag)
        val loaded = ModEntities.NPC.get().create(helper.level)!!
        loaded.load(tag)
        helper.assertTrue(loaded.holds(NpcActivity.CARRYING_MORTAR) && loaded.assignedMortarId == carrier.assignedMortarId && loaded.packedMortarState == carrier.packedMortarState, "Carrier save lost tube state")
        for (name in listOf("MORTAR_OPERATOR", "MORTAR_LOADER")) {
            tag.putString("NpcClass", name)
            tag.putInt("MortarShells", 0)
            loaded.load(tag)
            helper.assertTrue(loaded.npcClass == NpcClass.MORTAR, "Legacy $name did not migrate")
            // An operator was saved without the loader's shells; a spent loader stays spent.
            helper.assertTrue(loaded.mortarShellsLeft == (if (name == "MORTAR_OPERATOR") NpcEntity.MORTAR_SHELLS else 0), "Legacy migration reset a spent loader or stranded an unequipped operator")
        }
        helper.succeed()
    }

    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "mortar-two-tubes")
    fun twoMortarsKeepIndependentCrewsAndTimers(helper: GameTestHelper) {
        val scene = Scene.create(helper)
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

    /**
     * An enemy at arm's length: the operator fights him off through his engage, his crew duty set
     * aside (#258), and really shoots him.
     */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "mortar-self-defence")
    fun mortarmanDefendsHimselfAtArmsLength(helper: GameTestHelper) {
        val scene = Scene.create(helper)
        var enemy: NpcEntity? = null
        var engaged = false
        cleanup(helper) {
            scene.close()
            enemy?.discard()
        }
        helper.onEachTick {
            if (enemy == null && MortarClaims.activeCrew(scene.mortar) == 2) {
                val operator = scene.crew.first { it.uuid == MortarClaims.operator(scene.mortar.uuid) }
                enemy = AttackDeploymentGameTests.spawn(helper, helper.relativeVec(operator.position()).add(4.0, 0.0, 0.0), SquadFaction.COW).also { operator.target = it }
            }
            val man = enemy?.let { foe -> scene.crew.firstOrNull { it.target === foe } } ?: return@onEachTick
            val running = NativeTactics.reports(man).values.filter { it.status == ExecutionStatus.RUNNING }.map { it.ticket.identity }
            if (running.any(Engage::isUnit)) {
                helper.assertTrue(running.none { it.startsWith("crew:") }, "His crew duty still runs beside his engage: $running")
                engaged = true
            }
            val foe = enemy!!
            // He has to fight the man off, not hand him back to the tube once the engage starts.
            val fought = man.lastShotTick >= 0 || foe.health < foe.maxHealth || !foe.isAlive
            if (engaged && fought) helper.succeed()
        }
    }

    /** A squadless mortarman takes a free friendly tube in reach and serves it, through a crew plan of his own (#258). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 600, batch = "mortar-squadless")
    fun squadlessMortarmanTakesAFreeTube(helper: GameTestHelper) {
        for (x in 0..63) for (z in 40..90) helper.setBlock(BlockPos(x, 0, z), Blocks.STONE)
        val tube = Ports.mortars.create(helper.level, -90f)
        val pos = helper.absoluteVec(Vec3(24.5, 1.0, 64.5))
        tube.moveTo(pos.x, pos.y, pos.z, -90f, 0f)
        helper.level.addFreshEntity(tube)
        SquadTeams.assign(tube, SquadFaction.PIG)
        val man = AttackDeploymentGameTests.spawn(helper, Vec3(12.5, 1.0, 64.5), SquadFaction.PIG, role = NpcClass.MORTAR)
        cleanup(helper) {
            MortarClaims.release(man.uuid)
            man.discard()
            MortarClaims.releaseMortar(tube.uuid)
            tube.discard()
        }
        helper.onEachTick {
            if (MortarClaims.role(tube.uuid, man.uuid) != null && MortarClaims.atPost(man, tube)) {
                helper.assertTrue(NativeTactics.reports(man).values.any { it.ticket.identity.startsWith("crew:") }, "He serves the tube with no crew plan")
                helper.succeed()
            }
        }
    }

    /** A squad of a crew alone on DEFEND keeps its crew's plan from one update to the next (#258 v2.1). */
    @JvmStatic
    @GameTest(templateNamespace = "sbwnpc", template = "attack_arena", timeoutTicks = 500, batch = "mortar-defend-agents")
    fun mortarOnlyDefendSquadKeepsItsPlan(helper: GameTestHelper) {
        val scene = Scene.create(helper)
        scene.manager.setOrder(scene.squad.id, SquadOrder.DEFEND)
        scene.manager.setObjective(helper.level, scene.squad.id, BlockPos.containing(scene.mortar.position()))
        val tickets = scene.crew.associateWith { HashSet<Any>() }
        cleanup(helper) { scene.close() }
        helper.onEachTick {
            if (helper.tick < SETTLE) return@onEachTick
            scene.crew.forEach { man ->
                helper.assertTrue(NativeTactics.hasAgent(man), "${man.uuid} lost his crew agent at tick ${helper.tick}")
                NativeTactics.reports(man).values.filter { it.ticket.identity.startsWith("crew:") }.forEach { tickets.getValue(man) += it.ticket }
            }
            if (helper.tick >= SETTLE + HOLD) {
                // One duty each, held: a crew planned anew every update would make a new ticket each time.
                helper.assertTrue(tickets.values.all { it.size == 1 }, "The crew was planned anew while holding: ${tickets.values.map { it.size }}")
                helper.succeed()
            }
        }
    }

    private const val SETTLE = 100L
    private const val HOLD = 300L

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

        /** Both men stayed on the redeployed [tube], with as many shells between them and it as before the move. */
        fun assertCrewKept(tube: Entity, expected: Int?) {
            helper.assertTrue(crew.all { it.assignedMortarId == tube.uuid }, "Second soldier lost redeployed tube")
            val shells = crew.sumOf { it.mortarShellsLeft } + (if (Ports.mortars.hasShell(tube)) 1 else 0)
            helper.assertTrue(shells == expected, "Packing lost or duplicated shells")
        }

        companion object {
            /** One tube and its two-man crew on a stone field, ordered to shell a point 80 blocks east. */
            fun create(helper: GameTestHelper): Scene {
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
        }
    }
}

private fun report(name: String, content: String) {
    val path = Path.of(System.getProperty("sbwnpc.gameTestReports"), name)
    Files.createDirectories(path.parent)
    Files.writeString(path, content)
}

/** Runs [action] when the test ends, passed or failed, so a scene's entities and claims never leak into the next. */
private fun cleanup(helper: GameTestHelper, action: () -> Unit) {
    helper.testInfo.addListener(object : GameTestListener {
        override fun testStructureLoaded(testInfo: GameTestInfo) = Unit
        override fun testPassed(test: GameTestInfo, runner: GameTestRunner) = action()
        override fun testFailed(test: GameTestInfo, runner: GameTestRunner) = action()
        override fun testAddedForRerun(oldTest: GameTestInfo, newTest: GameTestInfo, runner: GameTestRunner) = action()
    })
}
