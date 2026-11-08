package com.davidsant7e.carro.nucleo

/**
 * Arma el abanico de rutas. No sabe de Android: recibe funciones para hablar con la red,
 * así se prueba en la JVM con respuestas simuladas. Es bloqueante: llamarlo fuera del hilo principal.
 */
class Buscador(
    /** GET que devuelve el cuerpo de la respuesta (o lanza). */
    private val get: (url: String) -> String,
    /** POST JSON con cabeceras extra; devuelve el cuerpo (o lanza). */
    private val post: (url: String, cuerpo: String, cabeceras: Map<String, String>) -> String,
    private val servidorValhalla: String = Valhalla.SERVIDOR,
    /** Pausa entre consultas a Valhalla (servidor público: uso justo). */
    private val pausaMs: Long = 250,
) {
    data class Resultado(val rutas: List<RutaMostrada>, val avisos: List<String>, val consultas: Int, val candidatas: Int)

    fun buscar(
        origen: Punto,
        destino: Punto,
        prefs: Preferencias,
        claveGoogle: String? = null,
        progreso: (hechas: Int, total: Int) -> Unit = { _, _ -> },
    ): Resultado {
        val avisos = ArrayList<String>()
        var consultas = 0

        fun valhalla(c: Valhalla.Consulta): List<Ruta> {
            consultas++
            val cuerpo = Valhalla.cuerpo(origen, destino, prefs.vehiculo, c, prefs.evitarPeajes)
            return Valhalla.leer(get(Valhalla.url(servidorValhalla, cuerpo)), c.estrategia)
        }

        // 1. Ruta base (si falla, no hay nada que hacer: se informa el error).
        val base = valhalla(Plan.base())
        if (base.isEmpty()) throw ErrorRuta("No se encontró ninguna ruta hasta el destino.")
        val candidatas = ArrayList(base)

        // 2–4. Perfiles, desvíos y bloqueos sobre la ruta base.
        val plan = Plan.perfiles(prefs.vehiculo) + Plan.desvios(base.first().puntos) + Plan.bloqueos(base.first().puntos)
        val usaGoogle = !claveGoogle.isNullOrBlank()
        val total = 1 + plan.size + (if (usaGoogle) 1 else 0)
        progreso(1, total)
        var fallidas = 0
        for ((i, c) in plan.withIndex()) {
            if (pausaMs > 0) Thread.sleep(pausaMs)
            try {
                candidatas += valhalla(c)
            } catch (e: ErrorRuta) {
                fallidas++ // p. ej. el punto de desvío cayó donde no hay calles
            } catch (e: Exception) {
                fallidas++
            }
            progreso(2 + i, total)
        }
        if (fallidas > 0) avisos += "$fallidas de ${plan.size} variantes no dieron ruta (normal si el desvío cae en un río o un cerro)."

        // Alternativas de Google (con tráfico), si hay clave.
        if (usaGoogle) {
            try {
                val cuerpo = Google.cuerpoRoutes(origen, destino, prefs.vehiculo, alternativas = true, evitarPeajes = prefs.evitarPeajes)
                Google.leerRoutes(post(Google.ROUTES_URL, cuerpo, cabecerasGoogle(claveGoogle!!)))
                    .forEachIndexed { i, r -> candidatas += Google.aRuta(r, i) }
            } catch (e: Exception) {
                avisos += "Google no respondió: ${e.message}"
            }
            progreso(total, total)
        }

        // Selección y presentación.
        val elegidas = Seleccion.elegir(candidatas, prefs)
        val rapida = elegidas.first()
        val celdasRapida = Seleccion.celdas(rapida.puntos)
        val usados = HashMap<String, Int>()
        var mostradas = elegidas.mapIndexed { i, r ->
            val base = if (i == 0) "La más rápida" else Plan.nombre(r.estrategia)
            val n = (usados[base] ?: 0) + 1
            usados[base] = n
            RutaMostrada(
                ruta = r,
                nombre = if (n > 1) "$base ($n)" else base,
                compartidoConRapida = if (i == 0) 1.0 else Seleccion.compartido(celdasRapida, Seleccion.celdas(r.puntos)),
                minutosExtra = (r.segundos - rapida.segundos) / 60,
                anclas = if (i == 0) emptyList() else Seleccion.anclas(r.puntos, rapida.puntos, prefs.anclasGoogleMaps),
            )
        }
        // Marcar la más corta en distancia.
        mostradas.minByOrNull { it.ruta.metros }?.let { corta ->
            if (corta !== mostradas.first()) {
                mostradas = mostradas.map { if (it === corta && "corta" !in it.nombre) it.copy(nombre = "${it.nombre} · la más corta") else it }
            }
        }

        // Tiempo con tráfico de cada ruta según Google (siguiendo sus mismas anclas).
        if (usaGoogle) {
            mostradas = mostradas.map { m ->
                try {
                    val anclas = if (m === mostradas.first()) emptyList() else Seleccion.anclas(m.ruta.puntos, rapida.puntos, 8)
                    val cuerpo = Google.cuerpoRoutes(origen, destino, prefs.vehiculo, anclas = anclas, evitarPeajes = prefs.evitarPeajes)
                    val g = Google.leerRoutes(post(Google.ROUTES_URL, cuerpo, cabecerasGoogle(claveGoogle!!))).firstOrNull()
                    m.copy(segundosConTrafico = g?.segundos)
                } catch (e: Exception) {
                    m
                }
            }
        }

        return Resultado(mostradas, avisos, consultas, candidatas.size)
    }

    private fun cabecerasGoogle(clave: String) = mapOf(
        "X-Goog-Api-Key" to clave,
        "X-Goog-FieldMask" to Google.ROUTES_CAMPOS,
    )
}
