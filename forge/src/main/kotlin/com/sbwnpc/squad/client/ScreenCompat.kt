package com.sbwnpc.squad.client

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/*
 * Screen calls that differ between Minecraft versions. This is the 1.20.1 file (Forge);
 * NeoForge and Fabric on 1.21 have their own with the same names.
 */

/**
 * The base of every screen of the mod. Minecraft 1.20.1 draws no background behind a screen's
 * widgets, so this one draws it before them: a screen calls `super.render` first and must not call
 * [renderBackground] itself, or the dim is drawn twice. 1.20.1 has no blur, only the dim.
 */
abstract class SquadScreen(title: Component) : Screen(title) {
    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        super.render(graphics, mouseX, mouseY, partialTick)
    }
}

/** A [SquadScreen] that is told of mouse-wheel scrolling through [onScroll]. */
abstract class ScrollScreen(title: Component) : SquadScreen(title) {
    /** Handles a scroll of [amount] (positive is up); null leaves it to the screen's widgets. */
    protected open fun onScroll(mouseX: Double, mouseY: Double, amount: Double): Boolean? = null

    final override fun mouseScrolled(mouseX: Double, mouseY: Double, delta: Double): Boolean =
        onScroll(mouseX, mouseY, delta) ?: super.mouseScrolled(mouseX, mouseY, delta)
}

object WidgetCompat {
    /** A check box at [x], [y] that calls [onChange] with its new state when clicked. */
    fun checkbox(font: Font, x: Int, y: Int, label: Component, selected: Boolean, onChange: (Boolean) -> Unit): AbstractWidget =
        object : Checkbox(x, y, CHECKBOX_WIDTH, CHECKBOX_HEIGHT, label, selected) {
            override fun onPress() {
                super.onPress()
                onChange(selected())
            }
        }

    /** Puts [widget] at [x], [y] with the given [width]. */
    fun place(widget: AbstractWidget, x: Int, y: Int, width: Int) {
        widget.x = x
        widget.y = y
        widget.width = width
    }

    private const val CHECKBOX_WIDTH = 200
    private const val CHECKBOX_HEIGHT = 20
}
