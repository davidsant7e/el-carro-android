package com.davidsant7e.carro.datos

import android.content.Context
import com.davidsant7e.carro.nucleo.Json
import com.davidsant7e.carro.nucleo.Lugar
import com.davidsant7e.carro.nucleo.Preferencias
import com.davidsant7e.carro.nucleo.Punto
import com.davidsant7e.carro.nucleo.Valhalla
import com.davidsant7e.carro.nucleo.Vehiculo
import com.davidsant7e.carro.nucleo.num
import com.davidsant7e.carro.nucleo.texto

/** Ajustes y destinos recientes, en SharedPreferences. */
class Ajustes(ctx: Context) {
    private val p = ctx.getSharedPreferences("ajustes", Context.MODE_PRIVATE)

    var preferencias: Preferencias
        get() = Preferencias(
            vehiculo = runCatching { Vehiculo.valueOf(p.getString("vehiculo", null) ?: "CARRO") }.getOrDefault(Vehiculo.CARRO),
            maxRutas = p.getInt("maxRutas", 8),
            maxExtraPorcentaje = p.getInt("maxExtra", 60),
            anclasGoogleMaps = p.getInt("anclas", 3),
            evitarPeajes = p.getBoolean("sinPeajes", false),
        )
        set(v) {
            p.edit()
                .putString("vehiculo", v.vehiculo.name)
                .putInt("maxRutas", v.maxRutas)
                .putInt("maxExtra", v.maxExtraPorcentaje)
                .putInt("anclas", v.anclasGoogleMaps)
                .putBoolean("sinPeajes", v.evitarPeajes)
                .apply()
        }

    var servidorValhalla: String
        get() = p.getString("valhalla", null)?.takeIf { it.isNotBlank() } ?: Valhalla.SERVIDOR
        set(v) = p.edit().putString("valhalla", v.trim()).apply()

    /** Últimos destinos usados (el más reciente primero). */
    var recientes: List<Lugar>
        get() = runCatching {
            (Json.parse(p.getString("recientes", "[]")!!) as List<*>).mapNotNull { r ->
                Lugar(Punto(r.num("lat") ?: return@mapNotNull null, r.num("lon") ?: return@mapNotNull null), r.texto("nombre"))
            }
        }.getOrDefault(emptyList())
        set(v) {
            val lista = v.take(8).map { mapOf("lat" to it.punto.lat, "lon" to it.punto.lon, "nombre" to it.nombre) }
            p.edit().putString("recientes", Json.escribir(lista)).apply()
        }

    fun recordar(l: Lugar) {
        recientes = listOf(l) + recientes.filter { com.davidsant7e.carro.nucleo.Geo.distancia(it.punto, l.punto) > 30 }
    }
}
