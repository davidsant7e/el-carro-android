package com.davidsant7e.carro.nucleo

import java.net.URLEncoder

/**
 * Varias paradas: el usuario da los lugares (y cuánto se queda en cada uno) y El Carro decide el orden
 * que menos tiempo de viaje suma, saliendo de donde está y terminando donde convenga. Después calcula
 * a qué hora llega y sale de cada parada. La ruta queda guardada; al salir de una parada se recalcula
 * lo que falta desde la ubicación y la hora de ese momento.
 */
data class Parada(
    val id: String,
    val lugar: Lugar,
    /** Cuánto se queda ahí (minutos). */
    val minutos: Int = 15,
    /** Ya se visitó (no entra al recalcular). */
    val visitada: Boolean = false,
)

/** Una parada del itinerario con sus tiempos. Horas en milisegundos (epoch). */
data class Visita(
    val parada: Parada,
    /** Viaje desde el punto anterior. */
    val viajeSeg: Double,
    val metros: Double,
    val llegada: Long,
    val salida: Long,
)

data class Itinerario(
    /** Desde dónde y a qué hora se calculó. */
    val origen: Punto,
    val inicio: Long,
    val visitas: List<Visita>,
    /** Geometría completa para el mapa. */
    val puntos: List<Punto>,
    val vehiculo: Vehiculo,
) {
    val fin: Long get() = visitas.lastOrNull()?.salida ?: inicio
    val segundosViaje: Double get() = visitas.sumOf { it.viajeSeg }
    val minutosEnParadas: Int get() = visitas.sumOf { it.parada.minutos }
    val metros: Double get() = visitas.sumOf { it.metros }
}

/** Un tramo de la ruta entre dos paradas consecutivas. */
data class Tramo(val segundos: Double, val metros: Double, val puntos: List<Punto>)

object Optimizador {
    /** Hasta este número de paradas se busca el orden óptimo exacto; con más, una aproximación muy buena. */
    const val MAX_EXACTO = 12

    /**
     * Mejor orden para recorrer las paradas 1..n saliendo de 0 y terminando en cualquiera (camino abierto).
     * `t[i][j]` = segundos de i a j (Double.POSITIVE_INFINITY si no hay camino). Devuelve índices 1..n.
     */
    fun orden(t: Array<DoubleArray>): List<Int> {
        val n = t.size - 1
        if (n <= 0) return emptyList()
        if (n == 1) return listOf(1)
        val r = if (n <= MAX_EXACTO) exacto(t, n) else mejorar(t, vecinoMasCercano(t, n))
        return r
    }

    fun costo(t: Array<DoubleArray>, orden: List<Int>): Double {
        var c = 0.0
        var antes = 0
        for (i in orden) {
            c += t[antes][i]
            antes = i
        }
        return c
    }

    /** Held-Karp: programación dinámica sobre subconjuntos (2^n · n²). */
    private fun exacto(t: Array<DoubleArray>, n: Int): List<Int> {
        val todos = 1 shl n
        val dp = Array(todos) { DoubleArray(n) { Double.POSITIVE_INFINITY } }
        val previo = Array(todos) { IntArray(n) { -1 } }
        for (j in 0 until n) dp[1 shl j][j] = t[0][j + 1]
        for (mascara in 1 until todos) {
            for (j in 0 until n) {
                if (mascara and (1 shl j) == 0) continue
                val base = dp[mascara][j]
                if (base == Double.POSITIVE_INFINITY) continue
                for (k in 0 until n) {
                    if (mascara and (1 shl k) != 0) continue
                    val nueva = mascara or (1 shl k)
                    val c = base + t[j + 1][k + 1]
                    if (c < dp[nueva][k]) {
                        dp[nueva][k] = c
                        previo[nueva][k] = j
                    }
                }
            }
        }
        val llena = todos - 1
        var ultimo = (0 until n).minByOrNull { dp[llena][it] } ?: 0
        if (dp[llena][ultimo] == Double.POSITIVE_INFINITY) return vecinoMasCercano(t, n)
        val orden = ArrayList<Int>()
        var mascara = llena
        while (ultimo != -1) {
            orden += ultimo + 1
            val p = previo[mascara][ultimo]
            mascara = mascara xor (1 shl ultimo)
            ultimo = p
        }
        return orden.reversed()
    }

    private fun vecinoMasCercano(t: Array<DoubleArray>, n: Int): List<Int> {
        val pendientes = (1..n).toMutableSet()
        val orden = ArrayList<Int>()
        var actual = 0
        while (pendientes.isNotEmpty()) {
            val sig = pendientes.minBy { t[actual][it] }
            orden += sig
            pendientes -= sig
            actual = sig
        }
        return orden
    }

    /** 2-opt y reubicación de una parada, hasta que ya no mejore. */
    private fun mejorar(t: Array<DoubleArray>, inicial: List<Int>): List<Int> {
        var mejor = inicial.toMutableList()
        var mejorCosto = costo(t, mejor)
        var cambio = true
        var vueltas = 0
        while (cambio && vueltas++ < 200) {
            cambio = false
            for (i in 0 until mejor.size - 1) {
                for (k in i + 1 until mejor.size) {
                    val prueba = (mejor.subList(0, i) + mejor.subList(i, k + 1).reversed() + mejor.subList(k + 1, mejor.size)).toMutableList()
                    val c = costo(t, prueba)
                    if (c + 1e-9 < mejorCosto) { mejor = prueba; mejorCosto = c; cambio = true }
                }
            }
            for (i in mejor.indices) {
                for (k in mejor.indices) {
                    if (i == k) continue
                    val prueba = mejor.toMutableList()
                    val x = prueba.removeAt(i)
                    prueba.add(k, x)
                    val c = costo(t, prueba)
                    if (c + 1e-9 < mejorCosto) { mejor = prueba; mejorCosto = c; cambio = true }
                }
            }
        }
        return mejor
    }
}

object Recorrido {
    /** Radio para decir «estás en esta parada». */
    const val RADIO_LLEGADA_M = 200.0

    /** Arma el horario: llegada = salida anterior + viaje; salida = llegada + estancia. */
    fun horario(origen: Punto, inicio: Long, enOrden: List<Parada>, tramos: List<Tramo>, vehiculo: Vehiculo): Itinerario {
        require(tramos.size == enOrden.size) { "Faltan tramos de la ruta" }
        var reloj = inicio
        val visitas = enOrden.mapIndexed { i, p ->
            val llegada = reloj + (tramos[i].segundos * 1000).toLong()
            val salida = llegada + p.minutos * 60_000L
            reloj = salida
            Visita(p, tramos[i].segundos, tramos[i].metros, llegada, salida)
        }
        return Itinerario(origen, inicio, visitas, tramos.flatMap { it.puntos }, vehiculo)
    }

    /** La parada pendiente donde está el usuario ahora (a menos de 200 m), si hay. */
    fun paradaActual(aqui: Punto, paradas: List<Parada>): Parada? =
        paradas.filter { !it.visitada }
            .map { it to Geo.distancia(aqui, it.lugar.punto) }
            .filter { it.second <= RADIO_LLEGADA_M }
            .minByOrNull { it.second }?.first

    /** Google Maps abre hasta 9 paradas intermedias: si hay más, se parte en tramos que empalman. */
    fun grupos(puntos: List<Punto>, maxPorEnlace: Int = 10): List<List<Punto>> {
        if (puntos.size <= maxPorEnlace) return listOf(puntos)
        val grupos = ArrayList<List<Punto>>()
        var i = 0
        while (i < puntos.size) {
            val fin = minOf(i + maxPorEnlace, puntos.size)
            grupos += puntos.subList(i, fin)
            i = fin
        }
        return grupos
    }

    // ---------- guardar la ruta ----------

    fun aJson(paradas: List<Parada>, it: Itinerario?): String = Json.escribir(
        linkedMapOf(
            "paradas" to paradas.map { p ->
                linkedMapOf("id" to p.id, "lat" to p.lugar.punto.lat, "lon" to p.lugar.punto.lon, "nombre" to p.lugar.nombre, "minutos" to p.minutos, "visitada" to p.visitada)
            },
            "itinerario" to it?.let { x ->
                linkedMapOf(
                    "origen" to listOf(x.origen.lat, x.origen.lon),
                    "inicio" to x.inicio,
                    "vehiculo" to x.vehiculo.name,
                    "visitas" to x.visitas.map { v ->
                        linkedMapOf("id" to v.parada.id, "viaje" to v.viajeSeg, "metros" to v.metros, "llegada" to v.llegada, "salida" to v.salida)
                    },
                    "forma" to Geo.codificar(x.puntos, 6),
                )
            },
        ),
    )

    fun deJson(texto: String?): Pair<List<Parada>, Itinerario?> {
        if (texto.isNullOrBlank()) return emptyList<Parada>() to null
        return runCatching {
            val raiz = Json.parse(texto)
            val paradas = raiz.lista("paradas").orEmpty().mapNotNull { m ->
                val lat = m.num("lat") ?: return@mapNotNull null
                val lon = m.num("lon") ?: return@mapNotNull null
                Parada(
                    id = m.texto("id") ?: return@mapNotNull null,
                    lugar = Lugar(Punto(lat, lon), m.texto("nombre")),
                    minutos = (m.num("minutos") ?: 15.0).toInt().coerceIn(0, 600),
                    visitada = (m as? Map<*, *>)?.get("visitada") == true,
                )
            }
            val porId = paradas.associateBy { it.id }
            val itin = raiz.obj("itinerario")?.let { x ->
                val o = x.lista("origen").orEmpty().mapNotNull { (it as? Number)?.toDouble() }
                val visitas = x.lista("visitas").orEmpty().mapNotNull { v ->
                    val p = porId[v.texto("id")] ?: return@mapNotNull null
                    Visita(p, v.num("viaje") ?: 0.0, v.num("metros") ?: 0.0, (v.num("llegada") ?: 0.0).toLong(), (v.num("salida") ?: 0.0).toLong())
                }
                if (o.size != 2 || visitas.isEmpty()) null else Itinerario(
                    Punto(o[0], o[1]), (x.num("inicio") ?: 0.0).toLong(), visitas,
                    Geo.decodificar(x.texto("forma").orEmpty(), 6),
                    runCatching { Vehiculo.valueOf(x.texto("vehiculo") ?: "CARRO") }.getOrDefault(Vehiculo.CARRO),
                )
            }
            paradas to itin
        }.getOrDefault(emptyList<Parada>() to null)
    }
}

/** Consultas a Valhalla para varias paradas: la matriz de tiempos y la ruta en el orden elegido. */
object ValhallaParadas {
    /** El servidor público acepta matrices medianas; se limita para no abusar. */
    const val MAX_PARADAS = 20

    private fun ubicacion(p: Punto): Map<String, Any> = mapOf("lat" to p.lat, "lon" to p.lon)

    private fun opciones(vehiculo: Vehiculo, evitarPeajes: Boolean): Map<String, Any> =
        if (evitarPeajes) mapOf("costing_options" to mapOf(vehiculo.costingValhalla to mapOf("use_tolls" to 0.0))) else emptyMap()

    /** Matriz de tiempos entre todos los puntos (el 0 es la salida). */
    fun cuerpoMatriz(puntos: List<Punto>, vehiculo: Vehiculo, evitarPeajes: Boolean): String = Json.escribir(
        linkedMapOf<String, Any>(
            "sources" to puntos.map(::ubicacion),
            "targets" to puntos.map(::ubicacion),
            "costing" to vehiculo.costingValhalla,
            "units" to "kilometers",
            "verbose" to true,
        ) + opciones(vehiculo, evitarPeajes),
    )

    fun urlMatriz(servidor: String, cuerpo: String) =
        servidor.trimEnd('/') + "/sources_to_targets?json=" + URLEncoder.encode(cuerpo, "UTF-8")

    /** Devuelve segundos [i][j]; sin camino = infinito. */
    fun leerMatriz(respuesta: String, n: Int): Array<DoubleArray> {
        val raiz = Json.parse(respuesta)
        raiz.texto("error")?.let { throw ErrorRuta(it) }
        val t = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 0.0 else Double.POSITIVE_INFINITY } }
        raiz.lista("sources_to_targets").orEmpty().forEachIndexed { i, fila ->
            (fila as? List<*>).orEmpty().forEachIndexed { j, celda ->
                val de = celda.num("from_index")?.toInt() ?: i
                val a = celda.num("to_index")?.toInt() ?: j
                val s = celda.num("time")
                if (de in 0 until n && a in 0 until n && s != null && de != a) t[de][a] = s
            }
        }
        return t
    }

    /** Ruta pasando por las paradas en orden (cada una es una parada de verdad: un tramo por parada). */
    fun cuerpoRuta(puntos: List<Punto>, vehiculo: Vehiculo, evitarPeajes: Boolean): String = Json.escribir(
        linkedMapOf<String, Any>(
            "locations" to puntos.map { ubicacion(it) + ("type" to "break") },
            "costing" to vehiculo.costingValhalla,
            "units" to "kilometers",
            "directions_type" to "none",
        ) + opciones(vehiculo, evitarPeajes),
    )

    fun urlRuta(servidor: String, cuerpo: String) = Valhalla.url(servidor, cuerpo)

    fun leerTramos(respuesta: String): List<Tramo> {
        val raiz = Json.parse(respuesta)
        raiz.texto("error")?.let { throw ErrorRuta(it) }
        return raiz.obj("trip").lista("legs").orEmpty().map { leg ->
            val r = leg.obj("summary")
            Tramo(r.num("time") ?: 0.0, (r.num("length") ?: 0.0) * 1000, Geo.decodificar(leg.texto("shape").orEmpty(), 6))
        }
    }
}
