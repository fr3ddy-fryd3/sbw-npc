package com.sbwnpc.squad.gametest

import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.squad.SquadManager
import com.sbwnpc.squad.team.SquadTeams
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.Tag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.chunk.PalettedContainer
import net.minecraft.world.level.chunk.storage.RegionFile
import net.minecraft.world.level.chunk.storage.RegionStorageInfo
import net.minecraft.world.level.levelgen.Heightmap
import java.nio.file.Files
import java.nio.file.Path
import java.util.EnumSet

/** Imports native saved blocks, equipment and orders into the isolated server, at original coordinates. */
internal class SavedBattleReplay(private val level: ServerLevel, private val root: Path) : AutoCloseable {
    val entities = mutableListOf<Entity>()
    val npcs get() = entities.filterIsInstance<NpcEntity>()
    private val forced = mutableListOf<ChunkPos>()
    private val previousManager = SquadManager.get(level)
    lateinit var manager: SquadManager
        private set
    var chunks = 0
        private set

    fun restore() {
        require(Files.isRegularFile(root.resolve("manifest.json"))) { "Replay requires a copied saved-world fixture with a manifest" }
        val squads = NbtIo.readCompressed(root.resolve("data/sbwnpc_squads.dat"), NbtAccounter.unlimitedHeap()).getCompound("data")
        manager = SquadManager.load(squads)
        val tags = readEntityTags()
        val combatants = tags.filter { it.getString("id") == "sbwnpc:npc" || (it.getString("id").startsWith("superbwarfare:") && !it.getString("id").contains("rocket")) }
        require(combatants.isNotEmpty()) { "Fixture has no combatants" }
        val positions = combatants.map { it.getList("Pos", Tag.TAG_DOUBLE.toInt()) }
        val minX = kotlin.math.floor(positions.minOf { it.getDouble(0) } - 32).toInt() shr 4
        val maxX = kotlin.math.floor(positions.maxOf { it.getDouble(0) } + 32).toInt() shr 4
        val minZ = kotlin.math.floor(positions.minOf { it.getDouble(2) } - 32).toInt() shr 4
        val maxZ = kotlin.math.floor(positions.maxOf { it.getDouble(2) } + 32).toInt() shr 4
        readRegions("region") { pos, chunk ->
            if (pos.x in minX..maxX && pos.z in minZ..maxZ) restoreChunk(pos, chunk)
        }
        level.dataStorage.set("sbwnpc_squads", manager)
        SquadManager.clearCache()
        restoreEntities(combatants)
        require(manager.all().all { it.barracks == null && it.routeId == null }) { "This replay requires saved barracks/routes as additional fixtures" }
    }

    /**
     * Imports only the saved blocks of the chunks within [radius] blocks of [center]: no entities,
     * squads or factions, for a battle the scene deploys itself on real terrain.
     */
    fun restoreTerrain(center: BlockPos, radius: Int) {
        val min = ChunkPos(center.offset(-radius, 0, -radius))
        val max = ChunkPos(center.offset(radius, 0, radius))
        readRegions("region") { pos, chunk ->
            if (pos.x in min.x..max.x && pos.z in min.z..max.z) restoreChunk(pos, chunk)
        }
    }

    private fun readEntityTags(): List<CompoundTag> =
        buildList {
            readRegions("entities") { _, chunk ->
                chunk.getList("Entities", Tag.TAG_COMPOUND.toInt()).forEach { add(it as CompoundTag) }
            }
        }

    private fun readRegions(folder: String, accept: (ChunkPos, CompoundTag) -> Unit) {
        val directory = root.resolve(folder)
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".mca") }.sorted().forEach { path ->
                val coordinates = path.fileName.toString().split('.').let { it[1].toInt() to it[2].toInt() }
                RegionFile(RegionStorageInfo("copied-battle", Level.OVERWORLD, folder), path, directory, false).use { region ->
                    for (x in 0..31) {
                        for (z in 0..31) {
                            val pos = ChunkPos(coordinates.first * 32 + x, coordinates.second * 32 + z)
                            region.getChunkDataInputStream(pos)?.use { input -> accept(pos, NbtIo.read(input)) }
                        }
                    }
                }
            }
        }
    }

    private fun restoreChunk(pos: ChunkPos, tag: CompoundTag) {
        val chunk = level.getChunk(pos.x, pos.z)
        val sections = tag.getList("sections", Tag.TAG_COMPOUND.toInt())
        for (entry in sections) {
            val section = entry as CompoundTag
            if (!section.contains("block_states")) continue
            val states = section.getCompound("block_states")
            // Vanilla's codec can substitute air for missing mod blocks; that would invalidate rays.
            states.getList("palette", Tag.TAG_COMPOUND.toInt()).forEach {
                val name = ResourceLocation.parse((it as CompoundTag).getString("Name"))
                require(BuiltInRegistries.BLOCK.containsKey(name)) { "Missing replay block $name; load its mod before testing" }
            }
            val blocks = BLOCK_STATE_CODEC.parse(NbtOps.INSTANCE, states).getOrThrow()
            val index = chunk.getSectionIndexFromSectionY(section.getByte("Y").toInt())
            chunk.sections[index] = LevelChunkSection(blocks, chunk.sections[index].biomes)
        }
        Heightmap.primeHeightmaps(chunk, EnumSet.allOf(Heightmap.Types::class.java))
        chunk.isUnsaved = true
        level.setChunkForced(pos.x, pos.z, true)
        forced += pos
        chunks++
        for (entry in tag.getList("block_entities", Tag.TAG_COMPOUND.toInt())) {
            val saved = entry as CompoundTag
            val at = BlockPos(saved.getInt("x"), saved.getInt("y"), saved.getInt("z"))
            val blockEntity = BlockEntity.loadStatic(at, level.getBlockState(at), saved, level.registryAccess())
                ?: error("Cannot restore block entity ${saved.getString("id")} at $at")
            chunk.setBlockEntity(blockEntity)
            blockEntity.onLoad()
        }
    }

    private fun restoreEntities(tags: List<CompoundTag>) {
        val scoreboard = NbtIo.readCompressed(root.resolve("data/scoreboard.dat"), NbtAccounter.unlimitedHeap()).getCompound("data")
        val teamMembers = scoreboard.getList("Teams", Tag.TAG_COMPOUND.toInt()).associate { entry ->
            val team = entry as CompoundTag
            team.getString("Name") to team.getList("Players", Tag.TAG_STRING.toInt()).map { it.asString }.toSet()
        }
        for (tag in tags) {
            val entity = EntityType.loadEntityRecursive(tag.copy(), level) { it } ?: error("Cannot load ${tag.getString("id")}")
            entity.random.setSeed(entity.uuid.mostSignificantBits xor entity.uuid.leastSignificantBits)
            require(level.tryAddFreshEntityWithPassengers(entity)) { "Failed to register ${entity.uuid}" }
            entities += entity
            if (entity is NpcEntity) {
                entity.currentSquad()?.let { SquadTeams.assign(entity, it.faction) }
                // Saved membership can be newer than the copied entity region; retain a diagnostic
                // actor as an individual rather than inventing a new squad or replenishing ammo.
            }
            for ((team, members) in teamMembers) {
                if (entity.scoreboardName in members && team.startsWith("sbwnpc_")) {
                    val faction = com.sbwnpc.squad.npc.SquadFaction.valueOf(team.removePrefix("sbwnpc_").uppercase())
                    SquadTeams.assign(entity, faction)
                }
            }
        }
    }

    override fun close() {
        entities.forEach { it.discard() }
        forced.forEach { level.setChunkForced(it.x, it.z, false) }
        level.dataStorage.set("sbwnpc_squads", previousManager)
        SquadManager.clearCache()
    }

    companion object {
        private val BLOCK_STATE_CODEC = PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState())
    }
}
