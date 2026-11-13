package com.davidsant7e.carro.nucleo

import java.net.URLEncoder

/** Abrir una ruta en Google Maps o Waze, y la API de rutas de Google (opcional, con tráfico). */
object Google {

    /**
     * Enlace universal de Google Maps con la ruta: si hay anclas, Google pasa por ellas en orden
     * (aparecen como paradas). Sin origen, Google usa la ubicación actual del celular.
     */
    fun urlMaps(origen: Punto?, destino: Punto, anclas: List<Punto>, vehiculo: Vehiculo): String {
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1")
        if (origen != null) sb.append("&origin=").append(origen.texto())
        sb.append("&destination=").append(destino.texto())
        sb.append("&travelmode=").append(vehiculo.modoGoogleMaps)
        if (anclas.isNotEmpty()) {
            sb.append("&waypoints=").append(URLEncoder.encode(anclas.joinToString("|") { it.texto() }, "UTF-8"))
        }
        sb.append("&dir_action=navigate")
        return sb.toString()
    }

    /** Waze no acepta puntos intermedios en sus enlaces: solo lleva al destino por su propia ruta. */
    fun urlWaze(destino: Punto): String = "https://waze.com/ul?ll=${destino.texto()}&navigate=yes"

    const val ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    const val ROUTES_CAMPOS = "routes.duration,routes.staticDuration,routes.distanceMeters,routes.polyline.encodedPolyline"

    /** Cuerpo de computeRoutes. Con `anclas` no se pueden pedir alternativas (regla de la API). */
    fun cuerpoRoutes(
        origen: Punto,
        destino: Punto,
        vehiculo: Vehiculo,
        anclas: List<Punto> = emptyList(),
        alternativas: Boolean = false,
        evitarPeajes: Boolean = false,
    ): String {
        fun ubic(p: Punto) = mapOf("location" to mapOf("latLng" to mapOf("latitude" to p.lat, "longitude" to p.lon)))
        val json = linkedMapOf<String, Any>(
            "origin" to ubic(origen),
            "destination" to ubic(destino),
            "travelMode" to vehiculo.modoRoutesApi,
            "routingPreference" to "TRAFFIC_AWARE",
            "languageCode" to "es-419",
            "units" to "METRIC",
        )
        if (anclas.isNotEmpty()) json["intermediates"] = anclas.take(25).map { ubic(it) + ("via" to true) }
        else if (alternativas) json["computeAlternativeRoutes"] = true
        if (evitarPeajes) json["routeModifiers"] = mapOf("avoidTolls" to true)
        return Json.escribir(json)
    }

    data class RutaGoogle(val segundos: Double, val segundosSinTrafico: Double?, val metros: Double, val puntos: List<Punto>)

    fun leerRoutes(respuesta: String): List<RutaGoogle> {
        val raiz = Json.parse(respuesta)
        raiz.obj("error")?.let { throw ErrorRuta(it.texto("message") ?: "Error de la API de Google") }
        return raiz.lista("routes").orEmpty().map { r ->
            RutaGoogle(
                segundos = duracion(r.texto("duration")) ?: 0.0,
                segundosSinTrafico = duracion(r.texto("staticDuration")),
                metros = r.num("distanceMeters") ?: 0.0,
                puntos = Geo.decodificar(r.obj("polyline").texto("encodedPolyline").orEmpty(), precision = 5),
            )
        }
    }

    /** "1234s" → 1234.0 */
    fun duracion(texto: String?): Double? = texto?.trim()?.removeSuffix("s")?.toDoubleOrNull()

    /** Para comparar con las de OpenStreetMap (que no tienen tráfico) se usa la duración sin tráfico. */
    fun aRuta(r: RutaGoogle, i: Int) = Ruta(r.puntos, r.metros, r.segundosSinTrafico ?: r.segundos, "google·$i", Ruta.Fuente.GOOGLE)
}

/** Búsqueda de direcciones con Nominatim (OpenStreetMap). */
object Nominatim {
    const val SERVIDOR = "https://nominatim.openstreetmap.org"

    /** Prioriza resultados cerca de `cerca` (caja de ~1° alrededor), sin excluir los demás. */
    fun url(texto: String, cerca: Punto?, limite: Int = 6): String {
        val sb = StringBuilder("$SERVIDOR/search?format=jsonv2&addressdetails=0&accept-language=es&limit=$limite&q=")
        sb.append(URLEncoder.encode(texto, "UTF-8"))
        if (cerca != null) {
            sb.append("&viewbox=")
                .append("%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, cerca.lon - 0.5, cerca.lat + 0.5, cerca.lon + 0.5, cerca.lat - 0.5))
        }
        return sb.toString()
    }

    /** Qué buscar, en orden, si el texto completo no da resultados (máximo 3 consultas). */
    fun variantes(texto: String): List<String> {
        val partes = texto.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (partes.size < 2) return listOf(texto.trim())
        return listOf(
            partes.joinToString(", "),
            partes.drop(1).joinToString(", "),
            "${partes.first()}, ${partes.last()}",
        ).distinct()
    }

    /** Nombre del lugar en un punto (para las paradas marcadas en el mapa). */
    fun urlReverso(p: Punto): String =
        "$SERVIDOR/reverse?format=jsonv2&accept-language=es&zoom=18&lat=%.6f&lon=%.6f".format(java.util.Locale.ROOT, p.lat, p.lon)

    /** Nombre corto: lo primero de la dirección (lugar o calle y número). */
    fun leerReverso(respuesta: String): String? {
        val r = Json.parse(respuesta)
        r.texto("name")?.takeIf { it.isNotBlank() }?.let { return it }
        return r.texto("display_name")?.split(',')?.take(2)?.joinToString(",")?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun leer(respuesta: String): List<Lugar> =
        (Json.parse(respuesta) as? List<*>).orEmpty().mapNotNull { r ->
            val lat = r.num("lat") ?: return@mapNotNull null
            val lon = r.num("lon") ?: return@mapNotNull null
            Lugar(Punto(lat, lon), r.texto("display_name"))
        }
}
