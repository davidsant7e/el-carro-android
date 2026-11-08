package com.davidsant7e.carro.nucleo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class Punto(val lat: Double, val lon: Double) {
    /** "4.648600,-74.062800": el formato que aceptan Google Maps y Waze. */
    fun texto(): String = "%.6f,%.6f".format(java.util.Locale.ROOT, lat, lon)

    fun valido(): Boolean = lat in -90.0..90.0 && lon in -180.0..180.0 && !(lat == 0.0 && lon == 0.0)
}

object Geo {
    private const val RADIO_TIERRA = 6_371_000.0

    private fun rad(g: Double) = g * PI / 180
    private fun grad(r: Double) = r * 180 / PI

    /** Distancia en metros (haversine). */
    fun distancia(a: Punto, b: Punto): Double {
        val dLat = rad(b.lat - a.lat)
        val dLon = rad(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(rad(a.lat)) * cos(rad(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * RADIO_TIERRA * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Rumbo de a hacia b en grados (0 = norte, 90 = este). */
    fun rumbo(a: Punto, b: Punto): Double {
        val y = sin(rad(b.lon - a.lon)) * cos(rad(b.lat))
        val x = cos(rad(a.lat)) * sin(rad(b.lat)) - sin(rad(a.lat)) * cos(rad(b.lat)) * cos(rad(b.lon - a.lon))
        return (grad(atan2(y, x)) + 360) % 360
    }

    /** El punto a `metros` de `p` en dirección `rumboGrados`. */
    fun desplazar(p: Punto, rumboGrados: Double, metros: Double): Punto {
        val d = metros / RADIO_TIERRA
        val r = rad(rumboGrados)
        val lat1 = rad(p.lat)
        val lon1 = rad(p.lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(r))
        val lon2 = lon1 + atan2(sin(r) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Punto(grad(lat2), ((grad(lon2) + 540) % 360) - 180)
    }

    fun longitud(puntos: List<Punto>): Double = puntos.zipWithNext { a, b -> distancia(a, b) }.sum()

    /** El punto a la fracción `f` (0..1) del recorrido y el rumbo del tramo en que cae. */
    fun alongar(puntos: List<Punto>, f: Double): Pair<Punto, Double> {
        require(puntos.size >= 2) { "La ruta necesita al menos dos puntos" }
        val meta = longitud(puntos) * f.coerceIn(0.0, 1.0)
        var acumulado = 0.0
        for ((a, b) in puntos.zipWithNext()) {
            val tramo = distancia(a, b)
            if (tramo > 0 && acumulado + tramo >= meta) {
                val t = (meta - acumulado) / tramo
                return Punto(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t) to rumbo(a, b)
            }
            acumulado += tramo
        }
        val (a, b) = puntos[puntos.size - 2] to puntos.last()
        return b to rumbo(a, b)
    }

    /** Remuestrea la ruta con un punto cada `paso` metros (para comparar rutas). */
    fun remuestrear(puntos: List<Punto>, paso: Double): List<Punto> {
        if (puntos.size < 2) return puntos
        val salida = ArrayList<Punto>()
        salida += puntos.first()
        var resto = paso
        for ((a, b) in puntos.zipWithNext()) {
            val tramo = distancia(a, b)
            var recorrido = 0.0
            while (tramo - recorrido >= resto) {
                recorrido += resto
                val t = recorrido / tramo
                salida += Punto(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                resto = paso
            }
            resto -= tramo - recorrido
        }
        if (salida.last() != puntos.last()) salida += puntos.last()
        return salida
    }

    /** Decodifica una polilínea codificada (Google: precisión 5; Valhalla: 6). */
    fun decodificar(codigo: String, precision: Int = 5): List<Punto> {
        val factor = Math.pow(10.0, precision.toDouble())
        val puntos = ArrayList<Punto>()
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < codigo.length) {
            var resultado = 0L
            var shift = 0
            var b: Int
            do {
                b = codigo[i++].code - 63
                resultado = resultado or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (resultado and 1L != 0L) (resultado shr 1).inv() else resultado shr 1
            resultado = 0
            shift = 0
            do {
                b = codigo[i++].code - 63
                resultado = resultado or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (resultado and 1L != 0L) (resultado shr 1).inv() else resultado shr 1
            puntos += Punto(lat / factor, lon / factor)
        }
        return puntos
    }

    fun codificar(puntos: List<Punto>, precision: Int = 5): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var latPrev = 0L
        var lonPrev = 0L
        for (p in puntos) {
            val lat = round(p.lat * factor).toLong()
            val lon = round(p.lon * factor).toLong()
            valor(lat - latPrev, sb)
            valor(lon - lonPrev, sb)
            latPrev = lat
            lonPrev = lon
        }
        return sb.toString()
    }

    private fun valor(v: Long, sb: StringBuilder) {
        var n = if (v < 0) (v shl 1).inv() else v shl 1
        while (n >= 0x20) {
            sb.append((((n and 0x1f) or 0x20) + 63).toInt().toChar())
            n = n shr 5
        }
        sb.append((n + 63).toInt().toChar())
    }

    /** Cuadrado de `lado` metros alrededor de `c`, como anillo [lon, lat] cerrado (formato Valhalla). */
    fun cuadrado(c: Punto, mitad: Double): List<List<Double>> {
        val n = desplazar(c, 0.0, mitad).lat
        val s = desplazar(c, 180.0, mitad).lat
        val e = desplazar(c, 90.0, mitad).lon
        val o = desplazar(c, 270.0, mitad).lon
        return listOf(listOf(o, s), listOf(e, s), listOf(e, n), listOf(o, n), listOf(o, s))
    }

    fun dentroDe(p: Punto, centro: Punto, mitad: Double): Boolean =
        abs(Geo.distancia(Punto(p.lat, centro.lon), centro)) <= mitad &&
            abs(Geo.distancia(Punto(centro.lat, p.lon), centro)) <= mitad
}
