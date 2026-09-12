package com.seungmin.multiview.model

import kotlin.math.ceil
import kotlin.math.sqrt

object LayoutPresets {
    private const val GAP = .004f

    fun apply(project: ProjectState, slots: Int): ProjectState {
        val count = slots.coerceIn(1, 12)
        val cells = cells(count)
        val updated = project.tiles.mapIndexed { index, tile ->
            if (index >= count) tile else {
                val c = cells[index]
                tile.copy(
                    tileX = c.x + GAP,
                    tileY = c.y + GAP,
                    tileWidth = (c.w - GAP * 2).coerceAtLeast(.08f),
                    tileHeight = (c.h - GAP * 2).coerceAtLeast(.08f),
                    zIndex = index,
                    locked = false
                )
            }
        }
        return project.copy(slotCount = count, tiles = updated, updatedAt = System.currentTimeMillis())
    }

    fun defaultCell(index: Int, slots: Int): Cell = cells(slots.coerceIn(1, 12))[index.coerceIn(0, slots - 1)]

    private fun cells(count: Int): List<Cell> {
        if (count == 2) return listOf(Cell(0f, 0f, .5f, 1f), Cell(.5f, 0f, .5f, 1f))
        if (count == 6) return grid(3, 2, count)
        if (count == 12) return grid(4, 3, count)
        val cols = ceil(sqrt(count.toDouble())).toInt()
        val rows = ceil(count.toDouble() / cols).toInt()
        return grid(cols, rows, count)
    }

    private fun grid(cols: Int, rows: Int, count: Int): List<Cell> = List(count) { i ->
        val w = 1f / cols
        val h = 1f / rows
        Cell((i % cols) * w, (i / cols) * h, w, h)
    }

    data class Cell(val x: Float, val y: Float, val w: Float, val h: Float)
}
