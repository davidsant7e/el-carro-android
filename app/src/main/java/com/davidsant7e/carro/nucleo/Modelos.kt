package com.davidsant7e.carro.nucleo

enum class Vehiculo(val etiqueta: String, val costingValhalla: String, val modoGoogleMaps: String, val modoRoutesApi: String) {
    CARRO("Carro", "auto", "driving", "DRIVE"),
    MOTO("Moto", "motorcycle", "two-wheeler", "TWO_WHEELER"),
}

/** Un destino ya resuelto. */
data class Lugar(val punto: Punto, val nombre: String? = null)

/** Una ruta candidata o elegida. `puntos` es la geometría completa. */
data class Ruta(
    val puntos: List<Punto>,
    val metros: Double,
    val segundos: Double,
    val estrategia: String,
    val fuente: Fuente = Fuente.OSM,
    val tienePeaje: Boolean = false,
    val tieneAutopista: Boolean = false,
) {
    enum class Fuente { OSM, GOOGLE }
}

/** Ruta elegida para mostrar, con sus comparaciones contra la más rápida. */
data class RutaMostrada(
    val ruta: Ruta,
    val nombre: String,
    /** Fracción (0..1) del recorrido que comparte con la más rápida. */
    val compartidoConRapida: Double,
    val minutosExtra: Double,
    /** Puntos para obligar a Google Maps a seguir esta ruta (vacío en la más rápida). */
    val anclas: List<Punto>,
    /** Duración con tráfico según Google (si hay clave). */
    val segundosConTrafico: Double? = null,
)

data class Preferencias(
    val vehiculo: Vehiculo = Vehiculo.CARRO,
    /** Cuántas rutas mostrar como máximo. */
    val maxRutas: Int = 8,
    /** Descartar rutas que tarden más que la más rápida en este porcentaje. */
    val maxExtraPorcentaje: Int = 60,
    /** Si dos rutas comparten más que esto, se queda solo la más rápida de las dos. */
    val maxCompartido: Double = 0.75,
    /** Puntos intermedios al abrir en Google Maps (la app de Android acepta hasta 9). */
    val anclasGoogleMaps: Int = 3,
    val evitarPeajes: Boolean = false,
)
