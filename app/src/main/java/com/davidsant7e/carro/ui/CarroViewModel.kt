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
import com.davidsant7e.carro.nucleo.Google
import com.davidsant7e.carro.nucleo.Lugar
import com.davidsant7e.carro.nucleo.Nominatim
import com.davidsant7e.carro.nucleo.Preferencias
import com.davidsant7e.carro.nucleo.Punto
import com.davidsant7e.carro.nucleo.Vehiculo
import com.davidsant7e.carro.red.Http
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
)

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

    private val ctx get() = getApplication<Application>()

    fun escribirConsulta(texto: String) = _estado.update { it.copy(consulta = texto) }

    fun cerrarAviso() = _estado.update { it.copy(aviso = null) }

    /** Texto compartido desde Google Maps / Waze, un enlace geo:, coordenadas o una dirección. */
    fun recibir(texto: String) {
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
                    val p = Enlaces.coordenadasDeUrl(final)
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
            } catch (e: ErrorRuta) {
                _estado.update { it.copy(fase = Fase.Fallo("El mapa no encontró ruta: ${e.message}")) }
            } catch (e: IOException) {
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
}
