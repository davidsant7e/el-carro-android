package com.davidsant7e.carro.nucleo

import kotlin.math.cos
import kotlin.math.floor

/**
 * Compara rutas por las calles que recorren y elige un conjunto variado:
 * se queda con las más rápidas y descarta las que repiten casi el mismo camino.
 */
object Seleccion {

    /** Tamaño de celda para decidir si dos rutas pasan "por el mismo lugar". */
    const val CELDA_M = 35.0

    /** Celdas (cuadrícula de ~35 m) por las que pasa la ruta. */
    fun celdas(puntos: List<Punto>, celda: Double = CELDA_M): Set<Long> {
        if (puntos.isEmpty()) return emptySet()
        return Geo.remuestrear(puntos, celda / 2).mapTo(HashSet()) { celdaDe(it, celda) }
    }

    /** Fracción (0..1) de la ruta `b` que pasa por donde pasa `a`. */
    fun compartido(a: Set<Long>, b: Set<Long>): Double =
        if (b.isEmpty()) 0.0 else b.count { it in a }.toDouble() / b.size

    /** Fracción del recorrido común, mirada desde la más corta de las dos. */
    fun parecido(a: Set<Long>, b: Set<Long>): Double = maxOf(compartido(a, b), compartido(b, a))

    /**
     * Elige hasta `prefs.maxRutas` rutas: la más rápida y, en orden de tiempo, las que no
     * se parezcan más de `prefs.maxCompartido` a ninguna ya elegida ni tarden demasiado.
     */
    fun elegir(candidatas: List<Ruta>, prefs: Preferencias): List<Ruta> {
        val validas = candidatas.filter { it.puntos.size >= 2 && it.segundos > 0 }
            .filter { !prefs.evitarPeajes || !it.tienePeaje }
            .sortedBy { it.segundos }
        if (validas.isEmpty()) return emptyList()
        val limite = validas.first().segundos * (1 + prefs.maxExtraPorcentaje / 100.0)
        val elegidas = ArrayList<Pair<Ruta, Set<Long>>>()
        for (r in validas) {
            if (elegidas.size >= prefs.maxRutas) break
            if (r.segundos > limite) break
            val c = celdas(r.puntos)
            if (elegidas.any { (_, otra) -> parecido(otra, c) > prefs.maxCompartido }) continue
            elegidas += r to c
        }
        return elegidas.map { it.first }
    }

    /**
     * Tramos de `ruta` que no están en `referencia`, como (índice inicial, índice final) sobre
     * la ruta remuestreada, ordenados por largo (los más largos primero).
     */
    fun tramosPropios(ruta: List<Punto>, referencia: Set<Long>, paso: Double = CELDA_M / 2): List<List<Punto>> {
        val muestras = Geo.remuestrear(ruta, paso)
        val tramos = ArrayList<List<Punto>>()
        var actual = ArrayList<Punto>()
        for (p in muestras) {
            val propio = celdaDe(p) !in referencia
            if (propio) actual += p
            else if (actual.isNotEmpty()) { tramos += actual; actual = ArrayList() }
        }
        if (actual.isNotEmpty()) tramos += actual
        // Tramos de una o dos celdas son ruido (esquinas, redondeos de la geometría).
        return tramos.filter { it.size >= 6 }.sortedByDescending { it.size }
    }

    /** Celda de la cuadrícula; el ancho en longitud usa la latitud redondeada al grado, igual para todas las rutas. */
    private fun celdaDe(p: Punto, celda: Double = CELDA_M): Long {
        val gradLat = celda / 111_320.0
        val gradLon = celda / (111_320.0 * cos(Math.toRadians(Math.rint(p.lat))).coerceAtLeast(0.01))
        return (floor(p.lat / gradLat).toLong() shl 32) xor (floor(p.lon / gradLon).toLong() and 0xffffffffL)
    }

    /**
     * Puntos que obligan a Google Maps (o a la API de Google) a seguir `ruta` y no su propia
     * ruta más rápida: el medio de cada tramo propio, y si sobran, más puntos en los tramos largos.
     * Se devuelven en el orden del recorrido.
     */
    fun anclas(ruta: List<Punto>, rapida: List<Punto>, maximo: Int): List<Punto> {
        if (maximo <= 0 || ruta.size < 2) return emptyList()
        val tramos = tramosPropios(ruta, celdas(rapida))
        if (tramos.isEmpty()) return emptyList()
        val cupos = IntArray(tramos.size)
        var restantes = maximo
        // Un punto por tramo (los más largos primero); los que sobren, al tramo más largo.
        for (i in tramos.indices) if (restantes > 0) { cupos[i] = 1; restantes-- }
        while (restantes > 0) {
            var asignado = false
            for (k in tramos.indices) {
                if (restantes == 0) break
                if (tramos[k].size >= 12 * (cupos[k] + 1)) { cupos[k]++; restantes--; asignado = true }
            }
            if (!asignado) break
        }
        val elegidos = ArrayList<Punto>()
        for ((k, tramo) in tramos.withIndex()) {
            val n = cupos[k]
            for (j in 1..n) elegidos += tramo[(tramo.size * j / (n + 1)).coerceIn(0, tramo.size - 1)]
        }
        // Ordenar según la posición en la ruta original.
        val muestras = Geo.remuestrear(ruta, CELDA_M / 2)
        return elegidos.sortedBy { p -> muestras.indexOfFirst { it == p }.let { if (it < 0) Int.MAX_VALUE else it } }
    }
}
