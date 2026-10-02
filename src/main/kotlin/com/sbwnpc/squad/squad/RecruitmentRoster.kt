package com.sbwnpc.squad.squad

import com.sbwnpc.squad.npc.NpcClass
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import java.util.UUID

/** Persistent places in a squad. An unloaded occupant still owns its place. */
class RecruitmentRoster(val slots: MutableList<Slot>) {
    class Slot(
        val id: UUID,
        val role: NpcClass,
        var occupant: UUID? = null,
        var recruited: Boolean = false,
        var active: Boolean = true
    )

    fun vacancies(): List<Slot> = slots.filter { it.active && it.occupant == null }

    fun release(member: UUID) {
        slots.filter { it.occupant == member }.forEach { it.occupant = null }
        slots.removeIf { !it.active && it.occupant == null }
    }

    /** Existing soldiers stay; surplus occupied places retire only when their soldier dies. */
    fun reconfigure(composition: List<NpcClass>) {
        val remaining = composition.toMutableList()
        slots.filter { it.occupant != null }.forEach { it.active = remaining.remove(it.role) }
        slots.filter { it.occupant == null }.forEach { it.active = remaining.remove(it.role) }
        slots.removeIf { !it.active && it.occupant == null }
        remaining.forEach { slots += Slot(UUID.randomUUID(), it) }
    }

    fun save(): ListTag = ListTag().also { list ->
        slots.forEach { slot ->
            list.add(CompoundTag().apply {
                putUUID("Id", slot.id)
                putString("Role", slot.role.name)
                slot.occupant?.let { putUUID("Occupant", it) }
                putBoolean("Recruited", slot.recruited)
                putBoolean("Active", slot.active)
            })
        }
    }

    companion object {
        fun empty(composition: List<NpcClass>) = RecruitmentRoster(
            composition.map { Slot(UUID.randomUUID(), it) }.toMutableList()
        )

        /** Legacy saves must resolve every member before assigning places, never guess an unloaded role. */
        fun migrate(composition: List<NpcClass>, members: Map<UUID, NpcClass?>): RecruitmentRoster? {
            if (members.values.any { it == null }) return null
            val roster = empty(composition)
            roster.slots.forEach { it.recruited = true }
            members.forEach { (id, role) ->
                val slot = roster.slots.firstOrNull { it.role == role && it.occupant == null }
                    ?: Slot(UUID.randomUUID(), role!!, recruited = true, active = false).also { roster.slots += it }
                slot.occupant = id
            }
            return roster
        }

        fun load(list: ListTag): RecruitmentRoster = RecruitmentRoster(list.map { raw ->
            val tag = raw as CompoundTag
            Slot(
                tag.getUUID("Id"), NpcClass.valueOf(tag.getString("Role")),
                if (tag.hasUUID("Occupant")) tag.getUUID("Occupant") else null,
                tag.getBoolean("Recruited"), tag.getBoolean("Active")
            )
        }.toMutableList())
    }
}
