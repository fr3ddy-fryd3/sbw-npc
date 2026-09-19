package com.sbwnpc.squad.npc

/** Support vehicle spawned alongside a [SquadPreset.FIVE]/[SquadPreset.SEVEN] squad when the
 *  "spawn with vehicle" toggle is on — unmanned, faction-assigned, left for the squad's own
 *  VehicleTransportBehaviour/VehicleCombatSupportBehaviour to claim like any vehicle found in the
 *  world. SEVEN has no choice — [BMP_2] is the only option the GUI ever assigns it. */
enum class TransportVehicle(val label: String) {
    LAV_25("LAV-25"),
    LAV_150("LAV-150"),
    BMP_2("BMP-2");

    /** Cycles only within the choices FIVE actually offers (LAV_25/LAV_150) — BMP_2 is assigned
     *  directly for SEVEN, never reached by clicking through. */
    fun nextTransport(): TransportVehicle = if (this == LAV_25) LAV_150 else LAV_25

    companion object {
        val DEFAULT = LAV_25
        fun byOrdinal(i: Int): TransportVehicle = entries.getOrElse(i) { DEFAULT }
    }
}

/** Model spawned by [SquadPreset.T90_CREW] ("Tank Crew") — the crew rides in whichever one is
 *  picked, same as the old T-90-only spawn. */
enum class TankModel(val label: String) {
    ZTZ_99A("ZTZ-99A"),
    T_90A("T90-A"),
    M1A2("M1A2");

    fun next(): TankModel = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = T_90A
        fun byOrdinal(i: Int): TankModel = entries.getOrElse(i) { DEFAULT }
    }
}
