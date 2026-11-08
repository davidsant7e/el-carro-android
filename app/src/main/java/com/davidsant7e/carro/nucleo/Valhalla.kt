package com.davidsant7e.carro.nucleo

import java.net.URLEncoder

/**
 * Valhalla (motor de rutas libre sobre OpenStreetMap). Por defecto usa el servidor público de
 * FOSSGIS, que no pide clave y tiene perfiles de carro (`auto`) y de moto (`motorcycle`).
 * Uso justo: pocas consultas por búsqueda, una tras otra.
 */
object Valhalla {
    const val SERVIDOR = "https://valhalla1.openstreetmap.de"

    /** Una consulta del plan: con qué ajustes y por dónde forzar el paso. */
    data class Consulta(
        val estrategia: String,
        val alternativas: Int = 0,
        val opciones: Map<String, Any> = emptyMap(),
        /** Puntos por los que la ruta debe pasar sin detenerse. */
        val via: List<Punto> = emptyList(),
        /** Zonas que la ruta debe evitar (anillos [lon, lat]). */
        val evitar: List<List<List<Double>>> = emptyList(),
    )

    fun cuerpo(origen: Punto, destino: Punto, vehiculo: Vehiculo, c: Consulta, evitarPeajes: Boolean): String {
        val ubicaciones = buildList {
            add(mapOf("lat" to origen.lat, "lon" to origen.lon))
            c.via.forEach { add(mapOf("lat" to it.lat, "lon" to it.lon, "type" to "through")) }
            add(mapOf("lat" to destino.lat, "lon" to destino.lon))
        }
        val opciones = LinkedHashMap<String, Any>(c.opciones)
        if (evitarPeajes) opciones["use_tolls"] = 0.0
        val json = linkedMapOf<String, Any>(
            "locations" to ubicaciones,
            "costing" to vehiculo.costingValhalla,
            "units" to "kilometers",
            "directions_type" to "none",
        )
        if (opciones.isNotEmpty()) json["costing_options"] = mapOf(vehiculo.costingValhalla to opciones)
        if (c.alternativas > 0) json["alternates"] = c.alternativas
        if (c.evitar.isNotEmpty()) json["exclude_polygons"] = c.evitar
        return Json.escribir(json)
    }

    fun url(servidor: String, cuerpo: String): String =
        servidor.trimEnd('/') + "/route?json=" + URLEncoder.encode(cuerpo, "UTF-8")

    /** Lee la respuesta: la ruta principal y sus alternativas. Lanza si Valhalla devolvió un error. */
    fun leer(respuesta: String, estrategia: String): List<Ruta> {
        val raiz = Json.parse(respuesta)
        raiz.texto("error")?.let { throw ErrorRuta(it) }
        val viajes = buildList {
            raiz.obj("trip")?.let { add(it) }
            raiz.lista("alternates")?.forEach { alt -> alt.obj("trip")?.let { add(it) } }
        }
        return viajes.mapIndexed { i, viaje ->
            val puntos = viaje.lista("legs").orEmpty().flatMap { leg ->
                Geo.decodificar(leg.texto("shape").orEmpty(), precision = 6)
            }
            val resumen = viaje.obj("summary")
            Ruta(
                puntos = puntos,
                metros = (resumen.num("length") ?: 0.0) * 1000,
                segundos = resumen.num("time") ?: 0.0,
                estrategia = if (i == 0) estrategia else "$estrategia·alt$i",
                tienePeaje = (resumen as? Map<*, *>)?.get("has_toll") == true,
                tieneAutopista = (resumen as? Map<*, *>)?.get("has_highway") == true,
            )
        }
    }
}

class ErrorRuta(mensaje: String) : Exception(mensaje)
