package com.davidsant7e.carro.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.davidsant7e.carro.datos.Ajustes
import com.davidsant7e.carro.datos.Boveda
import com.davidsant7e.carro.datos.Ubicacion
import com.davidsant7e.carro.nucleo.Buscador
import com.davidsant7e.carro.nucleo.Enlaces
import com.davidsant7e.carro.nucleo.ErrorRuta
import com.davidsant7e.carro.nucleo.Geo
import com.davidsant7e.carro.nucleo.Google
import com.davidsant7e.carro.nucleo.Itinerario
import com.davidsant7e.carro.nucleo.Optimizador
import com.davidsant7e.carro.nucleo.Parada
import com.davidsant7e.carro.nucleo.Recorrido
import com.davidsant7e.carro.nucleo.ValhallaParadas
import com.davidsant7e.carro.nucleo.Lugar
import com.davidsant7e.carro.nucleo.Nominatim
import com.davidsant7e.carro.nucleo.Preferencias
import com.davidsant7e.carro.nucleo.Punto
import com.davidsant7e.carro.nucleo.Vehiculo
import com.davidsant7e.carro.red.Http
import com.davidsant7e.carro.registro.Registro
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Fase {
    data object Inactivo : Fase
    data object Ubicando : Fase
    data class Buscando(val hechas: Int, val total: Int) : Fase
    data object Listo : Fase
    data class Fallo(val mensaje: String) : Fase
}

data class Estado(
    val prefs: Preferencias = Preferencias(),
    /** Null = salir desde la ubicación actual. */
    val origen: Lugar? = null,
    val destino: Lugar? = null,
    val consulta: String = "",
    val sugerencias: List<Lugar> = emptyList(),
    val buscandoLugar: Boolean = false,
    val fase: Fase = Fase.Inactivo,
    val resultado: Buscador.Resultado? = null,
    /** Desde dónde se calcularon las rutas mostradas. */
    val origenUsado: Punto? = null,
    val seleccion: Int = 0,
    val recientes: List<Lugar> = emptyList(),
    val tieneClaveGoogle: Boolean = false,
    val servidorValhalla: String = "",
    val aviso: String? = null,
    // ---------- varias paradas ----------
    val modoParadas: Boolean = false,
    val paradas: List<Parada> = emptyList(),
    val itinerario: Itinerario? = null,
    /** Se cambiaron paradas o tiempos después de organizar: hay que recalcular. */
    val desactualizado: Boolean = false,
    val organizando: String? = null,
    /** La parada pendiente donde estás ahora (detectada por GPS al abrir la app). */
    val estasEn: Parada? = null,
    /** Parada tocada en el mapa (para el menú: siguiente, terminar aquí, quitar). */
    val paradaTocada: Parada? = null,
) {
    val pendientes: List<Parada> get() = paradas.filter { !it.visitada }
}

class CarroViewModel(app: Application) : AndroidViewModel(app) {
    private val ajustes = Ajustes(app)
    private val _estado = MutableStateFlow(
        Estado(
            prefs = ajustes.preferencias,
            recientes = ajustes.recientes,
            tieneClaveGoogle = Boveda.hay(app),
            servidorValhalla = ajustes.servidorValhalla,
        ),
    )
    val estado: StateFlow<Estado> = _estado
    private var trabajo: Job? = null

    init {
        val (paradas, itin) = Recorrido.deJson(ajustes.rutaParadas)
        _estado.update { it.copy(modoParadas = ajustes.modoParadas, paradas = paradas, itinerario = itin) }
    }

    private val ctx get() = getApplication<Application>()

    fun escribirConsulta(texto: String) = _estado.update { it.copy(consulta = texto) }

    fun cerrarAviso() = _estado.update { it.copy(aviso = null) }

    /** Texto compartido desde Google Maps / Waze, un enlace geo:, coordenadas o una dirección. */
    fun recibir(texto: String) {
        Registro.evento("COMPARTIDO", "recibido", "largo" to texto.length, "es_enlace" to texto.contains("http"))
        _estado.update { it.copy(consulta = texto.lines().firstOrNull { l -> l.isNotBlank() }?.trim().orEmpty()) }
        viewModelScope.launch { interpretar(texto) }
    }

    /** Botón "Buscar" junto al campo de destino. */
    fun buscarDestino() {
        val texto = _estado.value.consulta.trim()
        if (texto.isEmpty()) return
        viewModelScope.launch { interpretar(texto) }
    }

    private suspend fun interpretar(texto: String) {
        _estado.update { it.copy(buscandoLugar = true, sugerencias = emptyList()) }
        try {
            when (val e = Enlaces.interpretar(texto)) {
                is Enlaces.Interpretacion.Coordenadas -> elegirDestino(e.lugar)
                is Enlaces.Interpretacion.Buscar -> buscarEnMapa(e.texto)
                is Enlaces.Interpretacion.Redirigir -> {
                    val final = withContext(Dispatchers.IO) {
                        Http.resolverRedireccion(e.url, { Enlaces.coordenadasDeUrl(it) != null })
                    }
                    // Si el enlace final no trae coordenadas (/maps/place/<nombre>/data=…), se leen de la página.
                    val p = Enlaces.coordenadasDeUrl(final)
                        ?: if ("google." in final) withContext(Dispatchers.IO) { runCatching { Enlaces.coordenadasDePagina(Http.pagina(final)) }.getOrNull() } else null
                    when {
                        p != null -> elegirDestino(Lugar(p, e.nombre ?: Enlaces.nombreDeUrl(final)))
                        e.nombre != null -> buscarEnMapa(e.nombre)
                        else -> when (val otra = Enlaces.interpretar(final)) {
                            is Enlaces.Interpretacion.Buscar -> buscarEnMapa(otra.texto)
                            else -> aviso("No encontré coordenadas en el enlace. Escribe la dirección.")
                        }
                    }
                }
                Enlaces.Interpretacion.Nada -> aviso("Escribe una dirección, pega un enlace o comparte un lugar desde Google Maps o Waze.")
            }
        } catch (e: IOException) {
            Registro.error("LUGAR", e)
            aviso("Sin conexión: ${e.message ?: "revisa internet"}")
        } finally {
            _estado.update { it.copy(buscandoLugar = false) }
        }
    }

    private suspend fun buscarEnMapa(texto: String) {
        val cerca = _estado.value.origenUsado ?: _estado.value.origen?.punto
        // Google comparte "Nombre, dirección, ciudad": si el texto completo no aparece en
        // OpenStreetMap se prueba con partes (la dirección sola, o el nombre con la ciudad).
        val lugares = withContext(Dispatchers.IO) {
            var encontrados = emptyList<Lugar>()
            for ((n, intento) in Nominatim.variantes(texto).withIndex()) {
                if (n > 0) Thread.sleep(1100) // Nominatim: máximo una consulta por segundo
                encontrados = Nominatim.leer(Http.get(Nominatim.url(intento, cerca)))
                if (encontrados.isNotEmpty()) break
            }
            encontrados
        }
        when (lugares.size) {
            0 -> aviso("No encontré «$texto». Prueba con más detalle (barrio, ciudad) o comparte el lugar desde Google Maps.")
            1 -> elegirDestino(lugares.single())
            else -> _estado.update { it.copy(sugerencias = lugares) }
        }
    }

    fun elegirDestino(lugar: Lugar) {
        if (_estado.value.modoParadas) {
            agregarParada(lugar)
            return
        }
        ajustes.recordar(lugar)
        _estado.update {
            it.copy(
                destino = lugar,
                consulta = lugar.nombre ?: lugar.punto.texto(),
                sugerencias = emptyList(),
                recientes = ajustes.recientes,
                resultado = null,
                fase = Fase.Inactivo,
            )
        }
        buscarRutas()
    }

    fun usarUbicacionActual() = _estado.update { it.copy(origen = null) }

    fun cambiarVehiculo(v: Vehiculo) {
        val prefs = _estado.value.prefs.copy(vehiculo = v)
        ajustes.preferencias = prefs
        _estado.update { it.copy(prefs = prefs) }
        if (_estado.value.destino != null && _estado.value.resultado != null) buscarRutas()
    }

    fun guardarAjustes(prefs: Preferencias, claveGoogle: String?, borrarClave: Boolean, servidor: String) {
        ajustes.preferencias = prefs
        ajustes.servidorValhalla = servidor
        if (borrarClave) Boveda.borrar(ctx) else if (!claveGoogle.isNullOrBlank()) Boveda.guardar(ctx, claveGoogle.trim())
        _estado.update { it.copy(prefs = prefs, tieneClaveGoogle = Boveda.hay(ctx), servidorValhalla = ajustes.servidorValhalla) }
    }

    fun seleccionar(i: Int) = _estado.update { it.copy(seleccion = i) }

    fun cancelar() {
        trabajo?.cancel()
        _estado.update { it.copy(fase = if (it.resultado != null) Fase.Listo else Fase.Inactivo) }
    }

    fun buscarRutas() {
        val destino = _estado.value.destino ?: return
        trabajo?.cancel()
        trabajo = viewModelScope.launch {
            val prefs = _estado.value.prefs
            _estado.update { it.copy(fase = Fase.Ubicando, resultado = null, seleccion = 0) }
            val origen = _estado.value.origen?.punto ?: Ubicacion.actual(ctx)
            if (origen == null) {
                _estado.update {
                    it.copy(fase = Fase.Fallo("No tengo tu ubicación. Activa el GPS y dale permiso de ubicación a El Carro."))
                }
                return@launch
            }
            _estado.update { it.copy(fase = Fase.Buscando(0, 1), origenUsado = origen) }
            try {
                val clave = withContext(Dispatchers.IO) { Boveda.leer(ctx) }
                val buscador = Buscador(Http::get, Http::post, _estado.value.servidorValhalla)
                val r = withContext(Dispatchers.IO) {
                    buscador.buscar(origen, destino.punto, prefs, clave) { h, t ->
                        _estado.update { it.copy(fase = Fase.Buscando(h, t)) }
                    }
                }
                _estado.update { it.copy(fase = Fase.Listo, resultado = r) }
                Registro.evento("RUTAS", "listas", "rutas" to r.rutas.size, "consultas" to r.consultas, "candidatas" to r.candidatas, "avisos" to r.avisos.size, "vehiculo" to prefs.vehiculo)
            } catch (e: ErrorRuta) {
                Registro.error("RUTAS", e)
                _estado.update { it.copy(fase = Fase.Fallo("El mapa no encontró ruta: ${e.message}")) }
            } catch (e: IOException) {
                Registro.error("RUTAS", e)
                _estado.update { it.copy(fase = Fase.Fallo("Sin conexión con el servidor de rutas (${e.message}). Intenta de nuevo.")) }
            }
        }
    }

    /** Enlace para abrir la ruta elegida en Google Maps. */
    fun enlaceGoogleMaps(i: Int): String? {
        val s = _estado.value
        val ruta = s.resultado?.rutas?.getOrNull(i) ?: return null
        val destino = s.destino ?: return null
        // Desde la ubicación actual se omite el origen: Google arranca la navegación desde donde estés.
        return Google.urlMaps(s.origen?.punto, destino.punto, ruta.anclas, s.prefs.vehiculo)
    }

    fun enlaceWaze(): String? = _estado.value.destino?.let { Google.urlWaze(it.punto) }

    private fun aviso(texto: String) = _estado.update { it.copy(aviso = texto) }

    // =============== varias paradas ===============

    fun cambiarModo(paradas: Boolean) {
        ajustes.modoParadas = paradas
        _estado.update { it.copy(modoParadas = paradas, sugerencias = emptyList(), consulta = "") }
        if (paradas) comprobarUbicacion()
    }

    private fun guardar() {
        val s = _estado.value
        ajustes.rutaParadas = Recorrido.aJson(s.paradas, s.itinerario)
    }

    private fun cambiarParadas(f: (List<Parada>) -> List<Parada>) {
        _estado.update { it.copy(paradas = f(it.paradas), desactualizado = it.itinerario != null) }
        guardar()
    }

    fun agregarParada(lugar: Lugar) {
        val s = _estado.value
        if (s.paradas.any { !it.visitada && Geo.distancia(it.lugar.punto, lugar.punto) < 30 }) {
            aviso("Esa parada ya está en la lista.")
            return
        }
        if (s.pendientes.size >= ValhallaParadas.MAX_PARADAS) {
            aviso("Máximo ${ValhallaParadas.MAX_PARADAS} paradas pendientes.")
            return
        }
        ajustes.recordar(lugar)
        val p = Parada(id = java.util.UUID.randomUUID().toString().take(8), lugar = lugar)
        cambiarParadas { it + p }
        _estado.update { it.copy(consulta = "", sugerencias = emptyList(), recientes = ajustes.recientes) }
        aviso("Parada ${s.pendientes.size + 1}: ${lugar.nombre?.take(40) ?: lugar.punto.texto()}")
    }

    /** Mantener presionado en el mapa: agrega la parada y luego busca su nombre. */
    fun agregarDesdeMapa(p: Punto) {
        val provisional = Lugar(p, "Punto en el mapa")
        agregarParada(provisional)
        viewModelScope.launch {
            val nombre = withContext(Dispatchers.IO) { runCatching { Nominatim.leerReverso(Http.get(Nominatim.urlReverso(p))) }.getOrNull() }
            if (nombre != null) cambiarParadas { l -> l.map { if (it.lugar == provisional) it.copy(lugar = Lugar(p, nombre)) else it } }
        }
    }

    fun quitarParada(id: String) = cambiarParadas { l -> l.filterNot { it.id == id } }

    fun cambiarMinutos(id: String, delta: Int) =
        cambiarParadas { l -> l.map { if (it.id == id) it.copy(minutos = (it.minutos + delta).coerceIn(0, 600)) else it } }

    /** «Ir ahora a esta»: queda primera al organizar (solo una). */
    fun marcarSiguiente(id: String) = cambiarParadas { l ->
        val ya = l.any { it.id == id && it.siguiente }
        l.map { it.copy(siguiente = !ya && it.id == id, final = if (it.id == id && !ya) false else it.final) }
    }

    /** «Terminar aquí»: queda última al organizar (solo una). */
    fun marcarFinal(id: String) = cambiarParadas { l ->
        val ya = l.any { it.id == id && it.final }
        l.map { it.copy(final = !ya && it.id == id, siguiente = if (it.id == id && !ya) false else it.siguiente) }
    }

    fun tocarParada(id: String?) = _estado.update { s -> s.copy(paradaTocada = s.paradas.firstOrNull { it.id == id }) }

    fun marcarVisitada(id: String, v: Boolean) = cambiarParadas { l -> l.map { if (it.id == id) it.copy(visitada = v) else it } }

    fun limpiarParadas() {
        _estado.update { it.copy(paradas = emptyList(), itinerario = null, desactualizado = false, estasEn = null) }
        guardar()
    }

    /** Al abrir la app: ¿estás en una de las paradas pendientes? */
    fun comprobarUbicacion() {
        val s = _estado.value
        if (!s.modoParadas || s.pendientes.isEmpty() || !Ubicacion.tienePermiso(ctx)) return
        viewModelScope.launch {
            val aqui = Ubicacion.actual(ctx) ?: return@launch
            _estado.update { it.copy(estasEn = Recorrido.paradaActual(aqui, it.paradas), origenUsado = aqui) }
        }
    }

    /** «Salir ahora»: la parada donde estás queda hecha y se recalcula lo que falta desde aquí y desde esta hora. */
    fun salirAhora() {
        _estado.value.estasEn?.let { p ->
            // La parada hecha ya no puede ser «siguiente».
            cambiarParadas { l -> l.map { if (it.id == p.id) it.copy(visitada = true, siguiente = false) else it } }
        }
        _estado.update { it.copy(estasEn = null) }
        organizar()
    }

    /** Ordena las paradas pendientes (sale de tu ubicación, termina donde convenga) y calcula horas. */
    fun organizar() {
        val pendientes = _estado.value.pendientes
        if (pendientes.isEmpty()) { aviso("No hay paradas pendientes."); return }
        trabajo?.cancel()
        trabajo = viewModelScope.launch {
            _estado.update { it.copy(organizando = "Buscando tu ubicación…") }
            try {
                val origen = _estado.value.origen?.punto ?: Ubicacion.actual(ctx)
                if (origen == null) {
                    aviso("No tengo tu ubicación. Activa el GPS y dale permiso de ubicación a El Carro.")
                    return@launch
                }
                val prefs = _estado.value.prefs
                val servidor = _estado.value.servidorValhalla
                val puntos = listOf(origen) + pendientes.map { it.lugar.punto }
                _estado.update { it.copy(organizando = "Calculando el mejor orden de ${pendientes.size} paradas…") }
                val orden = withContext(Dispatchers.IO) {
                    if (pendientes.size == 1) listOf(1) else {
                        val t = ValhallaParadas.leerMatriz(
                            Http.get(ValhallaParadas.urlMatriz(servidor, ValhallaParadas.cuerpoMatriz(puntos, prefs.vehiculo, prefs.evitarPeajes))),
                            puntos.size,
                        )
                        val primero = pendientes.indexOfFirst { it.siguiente }.takeIf { it >= 0 }?.plus(1)
                        val ultimo = pendientes.indexOfFirst { it.final }.takeIf { it >= 0 }?.plus(1)
                        Optimizador.orden(t, primero, ultimo)
                    }
                }
                val enOrden = orden.map { pendientes[it - 1] }
                _estado.update { it.copy(organizando = "Trazando la ruta…") }
                val tramos = withContext(Dispatchers.IO) {
                    val cuerpo = ValhallaParadas.cuerpoRuta(listOf(origen) + enOrden.map { it.lugar.punto }, prefs.vehiculo, prefs.evitarPeajes)
                    ValhallaParadas.leerTramos(Http.get(ValhallaParadas.urlRuta(servidor, cuerpo)))
                }
                val itin = Recorrido.horario(origen, System.currentTimeMillis(), enOrden, tramos, prefs.vehiculo)
                _estado.update { it.copy(itinerario = itin, desactualizado = false, origenUsado = origen) }
                Registro.evento("PARADAS", "organizadas", "paradas" to enOrden.size, "km" to itin.metros / 1000.0, "min_viaje" to itin.segundosViaje / 60.0)
                guardar()
            } catch (e: ErrorRuta) {
                Registro.error("PARADAS", e)
                aviso("El mapa no encontró ruta: ${e.message}")
            } catch (e: IOException) {
                Registro.error("PARADAS", e)
                aviso("Sin conexión con el servidor de rutas (${e.message}). Intenta de nuevo.")
            } finally {
                _estado.update { it.copy(organizando = null) }
            }
        }
    }

    /** Enlaces de Google Maps con las paradas pendientes en orden (partidos de a 10 si son muchas). */
    fun enlacesParadas(): List<String> {
        val s = _estado.value
        val visitas = s.itinerario?.visitas.orEmpty().filter { v -> s.paradas.any { it.id == v.parada.id && !it.visitada } }
        if (visitas.isEmpty()) return emptyList()
        val grupos = Recorrido.grupos(visitas.map { it.parada.lugar.punto })
        return grupos.mapIndexed { i, g ->
            val desde = if (i == 0) null else grupos[i - 1].last()
            Google.urlMaps(desde, g.last(), g.dropLast(1), s.prefs.vehiculo)
        }
    }

    fun enlaceUnaParada(p: Parada): String = Google.urlMaps(null, p.lugar.punto, emptyList(), _estado.value.prefs.vehiculo)
}
