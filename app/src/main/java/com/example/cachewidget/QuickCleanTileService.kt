package com.example.cachewidget

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickCleanTileService : TileService() {

    override fun onStartListening() {
        val tile: Tile? = qsTile
        if (tile != null) {
            tile.label = "Очистить кэш"
            tile.contentDescription = "Запустить очистку кэша"
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onClick() {
        // Запускаем MainActivity с командой «очистить всё»
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("auto_start_clear", true)
        }
        startActivity(intent)

        val tile: Tile? = qsTile
        if (tile != null) {
            tile.state = Tile.STATE_ACTIVE
            tile.updateTile()
        }
    }

    override fun onStopListening() {
        // Ничего не делаем
    }
}
