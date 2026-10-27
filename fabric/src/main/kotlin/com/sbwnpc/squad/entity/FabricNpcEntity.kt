package com.sbwnpc.squad.entity

import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/**
 * [NpcEntity] on Fabric. [NpcRegistry] bookkeeping is done from the server entity load and unload
 * events (see FabricEvents), which cover every add and remove path, as NeoForge's level hooks do.
 *
 * Unlike NeoForge there is no swim speed attribute here, so NPCs wade at the vanilla pace.
 */
class FabricNpcEntity(type: EntityType<out NpcEntity>, level: Level) : NpcEntity(type, level)
