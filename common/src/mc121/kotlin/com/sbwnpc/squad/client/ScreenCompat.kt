package com.sbwnpc.squad.client

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/*
 * Screen calls that differ between Minecraft versions. This is the 1.21 file (NeoForge and Fabric);
 * Forge on 1.20.1 has its own with the same names.
 */

/**
 * The base of every screen of the mod. Minecraft 1.21 draws the background (dim and blur) in
 * `Screen.render`, so nothing is added here; the 1.20.1 version draws it, so a screen calls
 * `super.render` first and never [renderBackground] itself.
 */
abstract class SquadScreen(title: Component) : Screen(title)

/** A [SquadScreen] that is told of mouse-wheel scrolling through [onScroll]. */
abstract class ScrollScreen(title: Component) : SquadScreen(title) {
    /** Handles a scroll of [amount] (positive is up); null leaves it to the screen's widgets. */
    protected open fun onScroll(mouseX: Double, mouseY: Double, amount: Double): Boolean? = null

    final override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean =
        onScroll(mouseX, mouseY, scrollY) ?: super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
}

object WidgetCompat {
    /** A check box at [x], [y] that calls [onChange] with its new state when clicked. */
    fun checkbox(font: Font, x: Int, y: Int, label: Component, selected: Boolean, onChange: (Boolean) -> Unit): AbstractWidget =
        Checkbox.builder(label, font)
            .pos(x, y)
            .selected(selected)
            .onValueChange { _, value -> onChange(value) }
            .build()

    /** Puts [widget] at [x], [y] with the given [width]. */
    fun place(widget: AbstractWidget, x: Int, y: Int, width: Int) {
        widget.x = x
        widget.y = y
        widget.width = width
    }
}
