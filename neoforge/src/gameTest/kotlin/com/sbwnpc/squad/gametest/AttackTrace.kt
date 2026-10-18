package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.combat.FriendlyFireGuard
import com.sbwnpc.squad.combat.tactics.SquadTactics
import com.sbwnpc.squad.domain.port.Ports
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.Squad
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.projectile.Projectile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.util.Locale
import java.util.UUID
import kotlin.math.acos
import kotlin.math.hypot

/** Read actual entities without altering their assignment, navigation or weapons. */
class AttackTrace(private val helper: GameTestHelper, private val squad: Squad, private val members: List<NpcEntity>, private val objective: net.minecraft.world.phys.Vec3) {
    private val directory = Path.of(System.getProperty("sbwnpc.gameTestReports"))
    private val rounds = HashMap<UUID, Int>()

    // Retain the issued stacks: a dead NPC dropping its gun is not ammunition consumption.
    private val issuedWeapons = members.map { it.mainHandItem }
    private val initialAmmo = issuedWeapons.sumOf { Ports.guns.roundsLeft(it) }
    private var lineHold = 0
    var enemyRounds = 0
        private set
    var longestLineHold = 0
        private set
    var maxAligned = 0
        private set

    /** The most members seen at once [FLANK_ANGLE] or more round the objective from the squad's start. */
    var maxFlanking = 0
        private set
    private val headOn = members.map { helper.relativeVec(it.position()) }.let { start ->
        kotlin.math.atan2(start.map { it.z }.average() - objective.z, start.map { it.x }.average() - objective.x)
    }
    var maxShotAngle = 0.0
        private set
    var unsafeRounds = 0
        private set
    var friendlyHits = 0
        private set
    val projectileShooters get() = rounds.size
    val ammoSpent get() = initialAmmo - issuedWeapons.sumOf { Ports.guns.roundsLeft(it) }

    init {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("attack-squad.csv"), "tick,elapsedMs,alive,targets,aligned,lineWidth,minSpacing,lineHold,projectileShooters,rounds,ammoSpent,plan,pattern,phase,status\n")
        Files.writeString(directory.resolve("attack-members.csv"), "tick,npc,x,y,z,health,target,lastShotTick,ammo,pathDone,nextNode,nodeCount,job,position,frontPost,failures,coverLocked,medicLocked\n")
        Files.writeString(directory.resolve("attack-damage.csv"), "tick,npc,health,damage,death,type,causer,directEntity,causerFaction,friendly\n")
        Files.writeString(directory.resolve("attack-rounds.csv"), "tick,round,shooter,target,freshGuardClear,x,y,z,vx,vy,vz,eyeHeight,angleToTarget\n")
        Files.writeString(directory.resolve("attack-shot-allies.csv"), "tick,round,ally,minX,minY,minZ,maxX,maxY,maxZ\n")
        com.mojang.logging.LogUtils.getLogger().info("Headless attack squad={}, members={}, reports={}", squad.id, members.size, directory)
    }

    private val started = System.nanoTime()

    fun round(shooter: NpcEntity, projectile: Projectile) {
        rounds[shooter.uuid] = (rounds[shooter.uuid] ?: 0) + 1
        val target = shooter.target
        val freshGuard = target?.let {
            FriendlyFireGuard.assess(shooter, SquadTactics.firingLaneEnd(shooter, it), shooter.spread, it.position(), 0.0).lineClear
        }
        val p = helper.relativeVec(projectile.position())
        val v = projectile.deltaMovement
        val angle = target?.let { Math.toDegrees(acos(v.normalize().dot(it.eyePosition.subtract(shooter.eyePosition).normalize()).coerceIn(-1.0, 1.0))) } ?: 180.0
        maxShotAngle = maxOf(maxShotAngle, angle)
        if (freshGuard != true) unsafeRounds++
        Files.writeString(
            directory.resolve("attack-rounds.csv"),
            "${helper.tick},${projectile.uuid},${shooter.uuid},${target?.uuid},$freshGuard,${p.x},${p.y},${p.z},${v.x},${v.y},${v.z},${shooter.eyeHeight},$angle\n",
            APPEND,
        )
        for (ally in members.filter { it !== shooter && it.isAlive }) {
            val box = ally.boundingBox
            val min = helper.relativeVec(net.minecraft.world.phys.Vec3(box.minX, box.minY, box.minZ))
            val max = helper.relativeVec(net.minecraft.world.phys.Vec3(box.maxX, box.maxY, box.maxZ))
            Files.writeString(
                directory.resolve("attack-shot-allies.csv"),
                "${helper.tick},${projectile.uuid},${ally.uuid},${min.x},${min.y},${min.z},${max.x},${max.y},${max.z}\n",
                APPEND,
            )
        }
    }

    fun enemyRound() {
        enemyRounds++
    }

    fun damage(victim: LivingEntity, source: DamageSource, damage: Float, death: Boolean) {
        val causer = source.entity
        val friendly = causer != null && SquadTeams.factionOf(causer) == SquadTeams.factionOf(victim)
        if (friendly && damage > 0.0f) friendlyHits++
        Files.writeString(
            directory.resolve("attack-damage.csv"),
            "${helper.tick},${victim.uuid},${victim.health},$damage,$death,${source.msgId},${causer?.uuid},${source.directEntity?.uuid},${causer?.let { SquadTeams.factionOf(it) }},$friendly\n",
            APPEND,
        )
    }

    fun sample() {
        val alive = members.filter { it.isAlive }
        val points = alive.map { helper.relativeVec(it.position()) }.sortedBy { it.x }
        // Physical line perpendicular to the scene's attack direction (+X), independent of task flags.
        val aligned = points.indices.map { start -> points.drop(start).takeWhile { it.x - points[start].x <= 6.0 } }
            .maxWithOrNull(compareBy<List<net.minecraft.world.phys.Vec3>> { it.size }.thenBy { row -> row.maxOf { it.z } - row.minOf { it.z } }).orEmpty()
        val width = if (aligned.isEmpty()) 0.0 else aligned.maxOf { it.z } - aligned.minOf { it.z }
        val spacing = points.indices.flatMap { a -> (a + 1 until points.size).map { b -> hypot(points[a].x - points[b].x, points[a].z - points[b].z) } }.minOrNull() ?: 0.0
        maxAligned = maxOf(maxAligned, aligned.size)
        // The head-on group of a lone squad holds its line while the other half takes a flank (#58).
        lineHold = if (aligned.size >= LINE && width >= (LINE - 1) * 3.0 && spacing >= 3.0) lineHold + 1 else 0
        maxFlanking = maxOf(
            maxFlanking,
            points.count { point ->
                val off = Math.toDegrees(kotlin.math.atan2(point.z - objective.z, point.x - objective.x) - headOn).let { ((it % 360) + 540) % 360 - 180 }
                kotlin.math.abs(off) >= FLANK_ANGLE
            },
        )
        longestLineHold = maxOf(longestLineHold, lineHold)
        if (helper.tick % 10 != 0L) return
        val lead = members.firstOrNull { it.isAlive }
        val operation = lead?.let(com.sbwnpc.squad.ai.integration.NativeTactics::operation)
        val line = String.format(
            Locale.ROOT, "%d,%d,%d,%d,%d,%.4f,%.4f,%d,%d,%d,%d,%s,%s,%s,%s%n",
            helper.tick, (System.nanoTime() - started) / 1_000_000, alive.size, alive.count { it.target != null }, aligned.size, width, spacing,
            lineHold, projectileShooters, rounds.values.sum(), ammoSpent, operation?.id, operation?.order, lead?.let(com.sbwnpc.squad.ai.integration.NativeTactics::attackPhase), operation?.status,
        )
        Files.writeString(directory.resolve("attack-squad.csv"), line, APPEND)
        for (npc in members) {
            val p = helper.relativeVec(npc.position())
            val task = com.sbwnpc.squad.ai.integration.NativeTactics.assignment(npc)
            val path = npc.navigation.path
            val record = String.format(
                Locale.ROOT, "%d,%s,%.4f,%.4f,%.4f,%.4f,%s,%d,%d,%s,%d,%d,%s,\"%s\",\"%s\",%d,%s,%s%n",
                helper.tick, npc.uuid, p.x, p.y, p.z, npc.health, npc.target?.uuid, npc.lastShotTick,
                Ports.guns.roundsLeft(npc.mainHandItem), npc.navigation.isDone, path?.nextNodeIndex ?: -1, path?.nodeCount ?: 0,
                task?.job, task?.position, task?.anchor, 0, npc.combatLockedByCover(), npc.combatLockedByMedic(),
            )
            Files.writeString(directory.resolve("attack-members.csv"), record, APPEND)
        }
    }

    fun finish() {
        Files.writeString(
            directory.resolve("attack-summary.txt"),
            "squad=${squad.id}\nmaxAligned=$maxAligned\nmaxFlanking=$maxFlanking\nlongestLineHold=$longestLineHold\nprojectileShooters=$projectileShooters\nrounds=${rounds.values.sum()}\nenemyRounds=$enemyRounds\nalive=${members.count { it.isAlive }}\nammoSpent=$ammoSpent\nmaxShotAngle=$maxShotAngle\nunsafeRounds=$unsafeRounds\nfriendlyHits=$friendlyHits\n" +
                rounds.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n",
        )
    }

    private companion object {
        /** Men a held line needs: the fire group's riflemen, half of sixteen less the stragglers. */
        const val LINE = 6

        /** Degrees round the objective from the start that count as on a flank. */
        const val FLANK_ANGLE = 40.0
    }
}
