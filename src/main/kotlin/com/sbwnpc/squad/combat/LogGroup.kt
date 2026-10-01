package com.sbwnpc.squad.combat

/**
 * One kind of trace line, switched on by naming its [key] in `config/sbwnpc-debug.toml` (see
 * [com.sbwnpc.squad.config.DebugConfig]). Each line is printed as `[key-debug] ...`.
 */
enum class LogGroup(val key: String) {
    AMMO("ammo"),
    BOAT("boat"),
    CHUNK("chunk"),
    DIG("dig"),
    DRONE("drone"),
    EQUIP("equip"),
    FIRE("fire"),
    FIRE_SUPPORT("fire-support"),
    GRENADE("grenade"),
    HEARING("hearing"),
    HELI("heli"),
    LOOT("loot"),
    MAP("map"),
    MARCH("march"),
    MEDIC("medic"),
    ORDER("order"),
    PATH("path"),
    PERF("perf"),
    RETREAT("retreat"),
    STUCK("stuck"),
    SUPPLY("supply"),
    VEHICLE("vehicle");

    val tag = "[$key-debug] "

    companion object {
        fun byKey(key: String): LogGroup? = entries.firstOrNull { it.key == key }
    }
}
