package com.sbwnpc.squad.squad

import com.sbwnpc.squad.compat.SavedDataAccess
import com.sbwnpc.squad.compat.VersionedSavedData
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.StringTag
import net.minecraft.nbt.Tag
import net.minecraft.server.MinecraftServer
import java.util.UUID

/** Players who have switched their squads' radio calls ([SquadReports]) off — all squads at once. */
class ReportMutes : VersionedSavedData() {

    private val muted = HashSet<UUID>()

    fun isMuted(player: UUID): Boolean = player in muted

    /** Flips [player]'s setting; true when reports are on afterwards. */
    fun toggle(player: UUID): Boolean {
        if (!muted.remove(player)) muted += player
        setDirty()
        return player !in muted
    }

    override fun write(tag: CompoundTag): CompoundTag {
        tag.put("Muted", net.minecraft.nbt.ListTag().apply { muted.forEach { add(StringTag.valueOf(it.toString())) } })
        return tag
    }

    companion object {
        private const val FILE = "sbwnpc_report_mutes"

        private fun load(tag: CompoundTag): ReportMutes {
            val data = ReportMutes()
            tag.getList("Muted", Tag.TAG_STRING.toInt()).forEach { entry ->
                runCatching { UUID.fromString(entry.asString) }.getOrNull()?.let { data.muted += it }
            }
            return data
        }

        fun get(server: MinecraftServer): ReportMutes = SavedDataAccess.fetch(server.overworld().dataStorage, FILE, ::ReportMutes, ::load)
    }
}
