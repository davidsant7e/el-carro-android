package com.davidsant7e.carro

import android.app.Application
import com.davidsant7e.carro.red.Http
import java.io.File
import org.osmdroid.config.Configuration

class CarroApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Mapa de OpenStreetMap: identificar la app y guardar los mosaicos en la caché propia
        // (no hace falta permiso de almacenamiento).
        Configuration.getInstance().apply {
            userAgentValue = Http.AGENTE
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid-tiles")
        }
    }
}
