package com.sbwnpc.squad.ai.tactics

import com.sbwnpc.squad.ai.coordination.ObjectiveKey
import com.sbwnpc.squad.ai.coordination.TacticalOrder
import java.util.UUID

enum class CommandSource { PLAYER, AUTONOMOUS }
enum class CommandOutcome { ACTIVE, COMPLETED, FAILED }

/** The requested intent survives internal execution-mode changes, including ammunition retreat. */
data class IssuedCommand(val id: UUID, val principal: UUID, val order: TacticalOrder, val goal: ObjectiveKey?, val source: CommandSource = CommandSource.PLAYER)
