package com.davidsant7e.carro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.davidsant7e.carro.nucleo.Preferencias
import com.davidsant7e.carro.nucleo.RutaMostrada
import com.davidsant7e.carro.nucleo.Vehiculo
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PantallaPrincipal(
    e: Estado,
    vm: CarroViewModel,
    pegar: () -> String?,
    abrir: (String) -> Unit,
) {
    val snack = remember { SnackbarHostState() }
    var ajustes by remember { mutableStateOf(false) }
    LaunchedEffect(e.aviso) {
        e.aviso?.let { snack.showSnackbar(it); vm.cerrarAviso() }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VII · El Carro") },
                actions = { TextButton(onClick = { ajustes = true }) { Text("Ajustes", color = Durazno) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AzulNoche, titleContentColor = Durazno),
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { CampoDestino(e, vm, pegar) }
            if (e.sugerencias.isNotEmpty()) {
                item { Text("¿Cuál de estos?", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold) }
                itemsIndexed(e.sugerencias) { _, l ->
                    Text(
                        l.nombre ?: l.punto.texto(),
                        Modifier.fillMaxWidth().clickable { vm.elegirDestino(l) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (e.modoParadas) {
                seccionParadas(e, vm, abrir)
                return@LazyColumn
            }
            item { EstadoBusqueda(e, vm) }
            val rutas = e.resultado?.rutas.orEmpty()
            if (rutas.isNotEmpty()) {
                item {
                    MapaRutas(
                        origen = e.origenUsado, destino = e.destino?.punto, rutas = rutas, seleccion = e.seleccion,
                        alTocarRuta = vm::seleccionar,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                }
                item {
                    Text(
                        "${rutas.size} rutas distintas (de ${e.resultado?.candidatas} encontradas). Toca una para verla en el mapa.",
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
                itemsIndexed(rutas) { i, r ->
                    TarjetaRuta(i, r, i == e.seleccion, onClick = { vm.seleccionar(i) },
                        abrirMaps = { vm.enlaceGoogleMaps(i)?.let(abrir) },
                        abrirWaze = { vm.enlaceWaze()?.let(abrir) })
                }
                e.resultado?.avisos?.forEach { aviso ->
                    item { Text(aviso, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    Text(
                        "Rutas: © OpenStreetMap · Valhalla (FOSSGIS)" + if (e.tieneClaveGoogle) " · tráfico: Google" else "",
                        Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else if (e.recientes.isNotEmpty() && e.fase == Fase.Inactivo && e.sugerencias.isEmpty()) {
                item { Text("Recientes", Modifier.padding(horizontal = 16.dp), fontWeight = FontWeight.Bold) }
                itemsIndexed(e.recientes) { _, l ->
                    Text(
                        l.nombre ?: l.punto.texto(),
                        Modifier.fillMaxWidth().clickable { vm.elegirDestino(l) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    e.paradaTocada?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.tocarParada(null) },
            title = { Text(p.lugar.nombre ?: "Parada", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { vm.marcarSiguiente(p.id); vm.tocarParada(null) }) {
                        Text(if (p.siguiente) "Quitar «ir ahora»" else "Ir ahora a esta (siguiente parada)")
                    }
                    TextButton(onClick = { vm.marcarFinal(p.id); vm.tocarParada(null) }) {
                        Text(if (p.final) "Quitar «terminar aquí»" else "Terminar la ruta aquí")
                    }
                    TextButton(onClick = { vm.marcarVisitada(p.id, !p.visitada); vm.tocarParada(null) }) {
                        Text(if (p.visitada) "Marcar como pendiente" else "Marcar como hecha")
                    }
                    TextButton(onClick = { vm.quitarParada(p.id); vm.tocarParada(null) }) {
                        Text("Quitar parada", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.tocarParada(null) }) { Text("Cerrar") } },
        )
    }
    if (ajustes) DialogoAjustes(e, onCerrar = { ajustes = false }, onGuardar = { p, clave, borrar, servidor ->
        vm.guardarAjustes(p, clave, borrar, servidor); ajustes = false
    })
}

@Composable
private fun CampoDestino(e: Estado, vm: CarroViewModel, pegar: () -> String?) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !e.modoParadas, onClick = { vm.cambiarModo(false) }, label = { Text("Una ruta") })
            FilterChip(
                selected = e.modoParadas, onClick = { vm.cambiarModo(true) },
                label = { Text(if (e.pendientes.isEmpty()) "Varias paradas" else "Varias paradas (${e.pendientes.size})") },
            )
        }
        Text(
            if (e.origen == null) "Desde: tu ubicación actual" else "Desde: ${e.origen.nombre ?: e.origen.punto.texto()}",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = e.consulta,
            onValueChange = vm::escribirConsulta,
            label = { Text(if (e.modoParadas) "Agregar parada" else "¿A dónde vas?") },
            placeholder = { Text("Dirección, lugar, coordenadas o enlace") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.buscarDestino() }),
            trailingIcon = {
                if (e.buscandoLugar) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else TextButton(onClick = vm::buscarDestino) { Text("Buscar") }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { pegar()?.let(vm::recibir) }) { Text("Pegar enlace") }
            Vehiculo.entries.forEach { v ->
                FilterChip(selected = e.prefs.vehiculo == v, onClick = { vm.cambiarVehiculo(v) }, label = { Text(v.etiqueta) })
            }
        }
        Text(
            if (e.modoParadas) "Consejo: en Google Maps o Waze toca Compartir → El Carro y se agrega como parada."
            else "Consejo: en Google Maps o Waze toca Compartir → El Carro.",
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EstadoBusqueda(e: Estado, vm: CarroViewModel) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val f = e.fase) {
            Fase.Ubicando -> {
                Text("Buscando tu ubicación…")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is Fase.Buscando -> {
                Text("Buscando rutas hacia ${e.destino?.nombre ?: "el destino"}… (${f.hechas} de ${f.total})")
                LinearProgressIndicator(progress = { f.hechas.toFloat() / f.total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = vm::cancelar) { Text("Cancelar") }
            }
            is Fase.Fallo -> {
                Text(f.mensaje, color = MaterialTheme.colorScheme.error)
                Button(onClick = vm::buscarRutas) { Text("Reintentar") }
            }
            Fase.Listo -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Hacia: ${e.destino?.nombre ?: e.destino?.punto?.texto()}", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = vm::buscarRutas) { Text("Actualizar") }
            }
            Fase.Inactivo -> if (e.destino != null) Button(onClick = vm::buscarRutas) { Text("Buscar rutas") }
        }
    }
}

private fun minutos(seg: Double) = (seg / 60).roundToInt().let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }

@Composable
private fun TarjetaRuta(i: Int, r: RutaMostrada, elegida: Boolean, onClick: () -> Unit, abrirMaps: () -> Unit, abrirWaze: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).clickable(onClick = onClick),
        border = if (elegida) BorderStroke(2.dp, colorRuta(i)) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(colorRuta(i), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(r.nombre, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(minutos(r.ruta.segundos), fontWeight = FontWeight.Bold)
            }
            val km = "%.1f km".format(r.ruta.metros / 1000)
            val extra = if (r.minutosExtra >= 0.5) " · +${r.minutosExtra.roundToInt()} min" else ""
            val comparte = if (i == 0) "" else " · comparte ${(r.compartidoConRapida * 100).roundToInt()} % con la más rápida"
            Text("$km$extra$comparte", style = MaterialTheme.typography.bodySmall)
            r.segundosConTrafico?.let { Text("Con el tráfico de ahora (Google): ${minutos(it)}", style = MaterialTheme.typography.bodySmall) }
            val marcas = listOfNotNull("peajes".takeIf { r.ruta.tienePeaje }, "autopista".takeIf { r.ruta.tieneAutopista })
            if (marcas.isNotEmpty()) Text("Incluye: ${marcas.joinToString()}", style = MaterialTheme.typography.bodySmall)
            if (elegida) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = abrirMaps) { Text("Abrir en Google Maps") }
                    if (i == 0) OutlinedButton(onClick = abrirWaze) { Text("Waze") }
                }
                if (r.anclas.isNotEmpty()) {
                    Text(
                        "Google Maps la seguirá pasando por ${r.anclas.size} punto(s) intermedio(s); los anuncia como paradas.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogoAjustes(
    e: Estado,
    onCerrar: () -> Unit,
    onGuardar: (Preferencias, String?, Boolean, String) -> Unit,
) {
    var maxRutas by remember { mutableFloatStateOf(e.prefs.maxRutas.toFloat()) }
    var maxExtra by remember { mutableFloatStateOf(e.prefs.maxExtraPorcentaje.toFloat()) }
    var anclas by remember { mutableFloatStateOf(e.prefs.anclasGoogleMaps.toFloat()) }
    var sinPeajes by remember { mutableStateOf(e.prefs.evitarPeajes) }
    var clave by remember { mutableStateOf("") }
    var borrarClave by remember { mutableStateOf(false) }
    var servidor by remember { mutableStateOf(e.servidorValhalla) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text("Ajustes") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("Mostrar hasta ${maxRutas.roundToInt()} rutas")
                    Slider(maxRutas, { maxRutas = it }, valueRange = 3f..10f, steps = 6)
                }
                item {
                    Text("Descartar las que tarden más de +${maxExtra.roundToInt()} % que la más rápida")
                    Slider(maxExtra, { maxExtra = it }, valueRange = 20f..150f, steps = 12)
                }
                item {
                    Text("Puntos intermedios al abrir en Google Maps: ${anclas.roundToInt()}")
                    Slider(anclas, { anclas = it }, valueRange = 1f..8f, steps = 6)
                    Text("Más puntos = Google sigue la ruta con más exactitud, pero anuncia más paradas.", style = MaterialTheme.typography.labelSmall)
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Evitar peajes", Modifier.weight(1f))
                        Switch(sinPeajes, { sinPeajes = it })
                    }
                }
                item {
                    Text("Tráfico en vivo (opcional)", fontWeight = FontWeight.Bold)
                    Text(
                        if (e.tieneClaveGoogle) "Hay una clave de Google guardada (cifrada en el celular)." else
                            "Con una clave de la API Routes de Google, cada ruta muestra su tiempo con el tráfico de ahora. Google cobra por consulta (tiene un cupo gratis mensual).",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(clave, { clave = it }, label = { Text("Clave de Google") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (e.tieneClaveGoogle) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Borrar la clave guardada", Modifier.weight(1f))
                        Switch(borrarClave, { borrarClave = it })
                    }
                }
                item {
                    OutlinedTextField(servidor, { servidor = it }, label = { Text("Servidor Valhalla") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("El público de FOSSGIS no pide clave. Cámbialo solo si tienes uno propio.", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onGuardar(
                    e.prefs.copy(
                        maxRutas = maxRutas.roundToInt(), maxExtraPorcentaje = maxExtra.roundToInt(),
                        anclasGoogleMaps = anclas.roundToInt(), evitarPeajes = sinPeajes,
                    ),
                    clave, borrarClave, servidor,
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onCerrar) { Text("Cancelar") } },
    )
}
