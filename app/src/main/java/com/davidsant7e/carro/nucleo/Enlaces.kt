package com.davidsant7e.carro.nucleo

import java.net.URLDecoder

/**
 * Entiende lo que llega al compartir un lugar desde Google Maps o Waze, un enlace `geo:`
 * o coordenadas escritas a mano.
 */
object Enlaces {

    sealed interface Interpretacion {
        /** Ya hay coordenadas. */
        data class Coordenadas(val lugar: Lugar) : Interpretacion
        /** Enlace corto (maps.app.goo.gl…): hay que seguir la redirección y volver a interpretar. */
        data class Redirigir(val url: String, val nombre: String?) : Interpretacion
        /** Solo hay texto: buscarlo como dirección o nombre de lugar. */
        data class Buscar(val texto: String) : Interpretacion
        data object Nada : Interpretacion
    }

    private val NUM = """(-?\d{1,3}(?:\.\d+)?)"""
    private val COORDS_SOLAS = Regex("""^\s*$NUM\s*[,; ]\s*$NUM\s*$""")
    private val URL = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)

    private val PATRONES = listOf(
        Regex("""!3d$NUM!4d$NUM"""), // marcador del lugar en Google Maps
        Regex("""[?&](?:ll|q|query|destination|daddr)=$NUM\s*,\s*\+?$NUM"""),
        Regex("""/place/$NUM,\s*\+?$NUM"""),
        Regex("""/search/$NUM,\s*\+?$NUM"""),
        Regex("""@$NUM,$NUM"""), // centro del mapa: el último recurso
    )
    private val DIR_DESTINO = Regex("""/dir/(?:[^/]*/)*?$NUM,\s*\+?$NUM/?(?:@|$|\?|data=)""")
    private val WAZE_GEOHASH = Regex("""waze\.com/ul/h([0-9b-hjkmnp-z]{5,})""", RegexOption.IGNORE_CASE)
    private val NOMBRE_PLACE = Regex("""/place/([^/@?]+)""")
    private val CONSULTA_TEXTO = Regex("""[?&](?:q|query|destination|daddr)=([^&#]+)""")
    private val CORTOS = setOf("maps.app.goo.gl", "goo.gl", "g.co", "maps.google.com", "waze.com", "www.waze.com", "ul.waze.com")

    fun interpretar(entrada: String?): Interpretacion {
        val texto = entrada?.trim().orEmpty()
        if (texto.isEmpty()) return Interpretacion.Nada

        if (texto.startsWith("geo:", ignoreCase = true)) return geo(texto)

        COORDS_SOLAS.matchEntire(texto)?.let { m ->
            punto(m.groupValues[1], m.groupValues[2])?.let { return Interpretacion.Coordenadas(Lugar(it)) }
        }

        val urls = URL.findAll(texto).map { it.value.trimEnd('.', ',', ')') }.toList()
        val nombreSuelto = URL.replace(texto, " ").lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }

        for (url in urls) {
            coordenadasDeUrl(url)?.let { p ->
                return Interpretacion.Coordenadas(Lugar(p, nombreSuelto ?: nombreDeUrl(url)))
            }
        }
        for (url in urls) {
            val host = host(url)
            if (host in CORTOS || host.endsWith(".app.goo.gl")) return Interpretacion.Redirigir(url, nombreSuelto)
        }
        for (url in urls) {
            consultaDeUrl(url)?.let { return Interpretacion.Buscar(it) }
        }
        return when {
            nombreSuelto != null -> Interpretacion.Buscar(nombreSuelto)
            urls.isNotEmpty() -> Interpretacion.Redirigir(urls.first(), null)
            else -> Interpretacion.Nada
        }
    }

    /** Coordenadas dentro de un enlace largo de Google Maps o Waze, o null. */
    fun coordenadasDeUrl(url: String): Punto? {
        val u = decodificar(url)
        WAZE_GEOHASH.find(u)?.let { return geohash(it.groupValues[1]) }
        DIR_DESTINO.find(u)?.let { m -> punto(m.groupValues[1], m.groupValues[2])?.let { return it } }
        for (patron in PATRONES) {
            patron.find(u)?.let { m -> punto(m.groupValues[1], m.groupValues[2])?.let { return it } }
        }
        return null
    }

    fun nombreDeUrl(url: String): String? =
        NOMBRE_PLACE.find(decodificar(url))?.groupValues?.get(1)?.replace('+', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    private fun consultaDeUrl(url: String): String? =
        CONSULTA_TEXTO.find(url)?.groupValues?.get(1)?.let { decodificar(it) }?.trim()?.takeIf { it.isNotEmpty() }
            ?: nombreDeUrl(url)

    private fun geo(texto: String): Interpretacion {
        // geo:lat,lon  ·  geo:0,0?q=lat,lon(Nombre)  ·  geo:0,0?q=Calle 72 Bogotá
        val sinEsquema = decodificar(texto.substring(4))
        val (base, consulta) = sinEsquema.split('?', limit = 2).let { it[0] to it.getOrNull(1) }
        val q = consulta?.split('&')?.firstOrNull { it.startsWith("q=") }?.removePrefix("q=")?.trim()
        if (q != null) {
            val m = Regex("""^$NUM\s*,\s*$NUM\s*(?:\((.*)\))?$""").find(q)
            if (m != null) {
                punto(m.groupValues[1], m.groupValues[2])?.let {
                    return Interpretacion.Coordenadas(Lugar(it, m.groupValues[3].ifBlank { null }))
                }
            }
            if (q.isNotBlank()) return Interpretacion.Buscar(q)
        }
        val partes = base.substringBefore(';').split(',')
        if (partes.size >= 2) punto(partes[0], partes[1])?.let { return Interpretacion.Coordenadas(Lugar(it)) }
        return Interpretacion.Nada
    }

    private fun punto(lat: String, lon: String): Punto? {
        val p = Punto(lat.toDoubleOrNull() ?: return null, lon.toDoubleOrNull() ?: return null)
        return p.takeIf { it.valido() }
    }

    private fun host(url: String): String =
        url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    private fun decodificar(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        s
    }

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"

    /** Centro de una celda geohash (Waze comparte enlaces waze.com/ul/h<geohash>). */
    fun geohash(hash: String): Punto? {
        var latMin = -90.0
        var latMax = 90.0
        var lonMin = -180.0
        var lonMax = 180.0
        var esLon = true
        for (c in hash.lowercase()) {
            val v = BASE32.indexOf(c)
            if (v < 0) return null
            for (bit in 4 downTo 0) {
                val uno = (v shr bit) and 1 == 1
                if (esLon) {
                    val mitad = (lonMin + lonMax) / 2
                    if (uno) lonMin = mitad else lonMax = mitad
                } else {
                    val mitad = (latMin + latMax) / 2
                    if (uno) latMin = mitad else latMax = mitad
                }
                esLon = !esLon
            }
        }
        return Punto((latMin + latMax) / 2, (lonMin + lonMax) / 2).takeIf { it.valido() }
    }
}
