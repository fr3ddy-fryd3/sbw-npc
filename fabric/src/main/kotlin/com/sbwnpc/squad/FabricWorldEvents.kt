package com.sbwnpc.squad

import com.sbwnpc.squad.combat.Hearing
import com.sbwnpc.squad.entity.NpcEntity
import com.sbwnpc.squad.entity.NpcRegistry
import com.sbwnpc.squad.integration.sbw.SbwHearing
import net.minecraft.core.Holder
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.gameevent.GameEvent

/** The vanilla game events and explosions Fabric has no events for; this module's mixins call these. */
object FabricWorldEvents {
    private const val SUPPRESSION_RADIUS = 6.0

    /**
     * Footsteps and landings of players and NPCs, off the same vanilla game events a sculk sensor
     * listens to, handed to [Hearing] — which passes them only to the other side. Called by the mixin.
     */
    @JvmStatic
    fun onGameEvent(level: ServerLevel, event: Holder<GameEvent>, cause: Entity?) {
        val walker = when (cause) {
            is ServerPlayer -> cause.takeUnless { it.isCreative || it.isSpectator }
            is NpcEntity -> cause
            else -> null
        } ?: return
        val kind = event.value()
        val landing = kind == GameEvent.HIT_GROUND.value()
        if (kind != GameEvent.STEP.value() && !landing) return
        Hearing.footstep(level, walker, landing)
    }

    /** Called by the mixin once an explosion has run: every NPC near it is suppressed, hit or not, and it is heard. */
    @JvmStatic
    fun onExplosion(level: ServerLevel, explosion: Explosion) {
        val pos = explosion.center()
        NpcRegistry.forEachWithin(level, pos, SUPPRESSION_RADIUS) { it.suppress(pos) }
        SbwHearing.onExplosion(level, explosion)
    }
}
