package com.davidsant7e.carro.nucleo

/**
 * Cómo sacar más de 5 rutas distintas cuando los motores dan 2 o 3:
 *  1. La consulta base con alternativas del motor.
 *  2. Perfiles distintos: la más corta, sin vías principales, sin peajes.
 *  3. Desvíos forzados: pasar por un punto a la izquierda o a la derecha de la ruta base.
 *  4. Bloqueos: prohibir un pedazo de la ruta base para que el motor busque otro camino.
 * Después [Seleccion.elegir] quita las repetidas y las que tardan demasiado.
 */
object Plan {

    /** La primera consulta: con ella se conoce la ruta base sobre la que se planean los desvíos. */
    fun base(): Valhalla.Consulta = Valhalla.Consulta("base", alternativas = 2)

    fun perfiles(vehiculo: Vehiculo): List<Valhalla.Consulta> = buildList {
        add(Valhalla.Consulta("corta", opciones = mapOf("shortest" to true)))
        add(
            Valhalla.Consulta(
                "secundarias",
                opciones = when (vehiculo) {
                    Vehiculo.CARRO -> mapOf("use_highways" to 0.0, "use_living_streets" to 0.6)
                    Vehiculo.MOTO -> mapOf("use_highways" to 0.0, "use_primary" to 0.1, "use_trunk" to 0.0)
                },
            ),
        )
        add(Valhalla.Consulta("sin_peajes", opciones = mapOf("use_tolls" to 0.0, "use_highways" to 0.3)))
    }

    /** Distancia del desvío: proporcional al viaje, entre 400 m y 4 km. */
    fun separacion(largoBase: Double): Double = (largoBase * 0.15).coerceIn(400.0, 4000.0)

    fun desvios(base: List<Punto>): List<Valhalla.Consulta> {
        if (base.size < 2) return emptyList()
        val largo = Geo.longitud(base)
        if (largo < 1500) return emptyList() // trayecto muy corto: no hay espacio para desvíos
        val d = separacion(largo)
        val salida = ArrayList<Valhalla.Consulta>()
        for ((f, donde) in listOf(0.5 to "a mitad de camino", 0.3 to "al comienzo", 0.7 to "al final")) {
            val (p, rumbo) = Geo.alongar(base, f)
            for ((lado, giro) in listOf("izquierda" to -90.0, "derecha" to 90.0)) {
                val via = Geo.desplazar(p, rumbo + giro, d)
                salida += Valhalla.Consulta("desvio_${lado}_${(f * 100).toInt()}|Por la $lado, $donde", via = listOf(via))
            }
        }
        return salida
    }

    fun bloqueos(base: List<Punto>): List<Valhalla.Consulta> {
        if (base.size < 2) return emptyList()
        val largo = Geo.longitud(base)
        if (largo < 1500) return emptyList()
        val mitad = (largo * 0.04).coerceIn(150.0, 800.0)
        val origen = base.first()
        val destino = base.last()
        return listOf(0.25, 0.5, 0.75).mapNotNull { f ->
            val (c, _) = Geo.alongar(base, f)
            // Nunca bloquear la salida ni la llegada.
            if (Geo.distancia(c, origen) < mitad * 3 || Geo.distancia(c, destino) < mitad * 3) return@mapNotNull null
            Valhalla.Consulta("bloqueo_${(f * 100).toInt()}", evitar = listOf(Geo.cuadrado(c, mitad)))
        }
    }

    /** Nombre para mostrar según la estrategia que encontró la ruta. */
    fun nombre(estrategia: String): String {
        estrategia.substringAfter('|', "").takeIf { it.isNotEmpty() }?.let { return it }
        val clave = estrategia.substringBefore('|').substringBefore('·')
        return when {
            clave == "base" && "·alt" in estrategia -> "Alternativa del mapa"
            clave == "base" -> "Ruta principal"
            clave == "corta" -> "La más corta en distancia"
            clave == "secundarias" -> "Por vías secundarias"
            clave == "sin_peajes" -> "Sin peajes"
            clave.startsWith("bloqueo") -> "Esquivando un tramo de la principal"
            clave == "google" -> "Alternativa de Google"
            else -> "Otra ruta"
        }
    }
}
