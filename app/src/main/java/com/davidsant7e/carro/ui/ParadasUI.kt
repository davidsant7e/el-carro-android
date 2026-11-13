package com.davidsant7e.carro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.davidsant7e.carro.nucleo.Parada
import com.davidsant7e.carro.nucleo.Visita
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val formatoHora = DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("es-CO"))

fun hora(ms: Long): String = formatoHora.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun duracion(seg: Double): String = (seg / 60).roundToInt().let { if (it >= 60) "${it / 60} h ${it % 60} min" else "$it min" }

/** Contenido de la lista en el modo «Varias paradas». */
fun LazyListScope.seccionParadas(e: Estado, vm: CarroViewModel, abrir: (String) -> Unit) {
    // Estás en una de las paradas: ofrecer salir y recalcular lo que falta.
    e.estasEn?.let { p ->
        item {
            Surface(color = Durazno.copy(alpha = 0.35f), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Estás en: ${p.lugar.nombre ?: "una parada"}", fontWeight = FontWeight.Bold)
                    Text("Cuando vayas a salir, recalculo el orden y las horas de lo que falta desde aquí y desde la hora de ese momento.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::salirAhora) { Text("Salir ahora") }
                }
            }
        }
    }

    val itin = e.itinerario
    if (e.paradas.isEmpty()) {
        item {
            Text(
                "Agrega las paradas: búscalas arriba, compártelas desde Google Maps o Waze (Compartir → El Carro) " +
                    "o mantén presionado un punto del mapa. Después toca «Organizar ruta».",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (e.paradas.isNotEmpty()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                e.organizando?.let {
                    Text(it)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                if (itin != null && !e.desactualizado) ResumenItinerario(e)
                if (e.desactualizado) Text("Cambiaste paradas o tiempos: vuelve a organizar para ver las horas nuevas.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = vm::organizar, enabled = e.organizando == null && e.pendientes.isNotEmpty()) {
                        Text(if (itin == null) "Organizar ruta" else "Recalcular desde aquí")
                    }
                    TextButton(onClick = vm::limpiarParadas) { Text("Borrar todas") }
                }
            }
        }
        // En el orden del itinerario; las que aún no tienen hora (nuevas o visitadas) al final.
        val visitas = if (e.desactualizado) emptyMap() else itin?.visitas?.associateBy { it.parada.id }.orEmpty()
        val ordenadas = e.paradas.sortedWith(compareBy({ it.visitada }, { p -> itin?.visitas?.indexOfFirst { it.parada.id == p.id }?.takeIf { it >= 0 } ?: 999 }))
        items(ordenadas, key = { it.id }) { p ->
            val numero = itin?.visitas?.indexOfFirst { it.parada.id == p.id }?.takeIf { it >= 0 && !e.desactualizado }?.plus(1)
            TarjetaParada(p, numero, visitas[p.id], vm, abrir)
        }
        val enlaces = vm.enlacesParadas()
        if (itin != null && !e.desactualizado && enlaces.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (enlaces.size == 1) {
                        Button(onClick = { abrir(enlaces[0]) }, modifier = Modifier.fillMaxWidth()) { Text("Abrir toda la ruta en Google Maps") }
                    } else {
                        Text("Google Maps acepta hasta 10 paradas por enlace: ábrela por tramos.", style = MaterialTheme.typography.bodySmall)
                        enlaces.forEachIndexed { i, url ->
                            OutlinedButton(onClick = { abrir(url) }, modifier = Modifier.fillMaxWidth()) { Text("Tramo ${i + 1} en Google Maps") }
                        }
                    }
                    Text(
                        "Los tiempos son sin tráfico (OpenStreetMap). En hora pico pueden ser más. La ruta queda guardada: " +
                            "al abrir El Carro en una parada te ofrece «Salir ahora».",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
    // Mapa: ruta organizada y paradas numeradas; mantener presionado agrega una parada.
    item {
        val orden = itin?.visitas?.map { it.parada.id }.orEmpty()
        MapaParadas(
            origen = itin?.origen ?: e.origenUsado,
            ruta = if (e.desactualizado) emptyList() else itin?.puntos.orEmpty(),
            paradas = e.paradas.sortedBy { p -> orden.indexOf(p.id).let { if (it < 0) 999 else it } },
            alMantener = vm::agregarDesdeMapa,
            alTocar = { vm.tocarParada(it) },
            modifier = Modifier.fillMaxWidth().height(300.dp),
        )
        Text("Mantén presionado el mapa para agregar una parada; toca una parada para elegirla como siguiente o como final.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall)
    }

}

@Composable
private fun ResumenItinerario(e: Estado) {
    val it = e.itinerario ?: return
    Card(colors = CardDefaults.cardColors(containerColor = AzulNoche)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Calculado a las ${hora(it.inicio)} · ${it.visitas.size} paradas", color = Durazno, style = MaterialTheme.typography.labelMedium)
            Text("Terminas a las ${hora(it.fin)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(
                "Manejando ${duracion(it.segundosViaje)} · en paradas ${duracion(it.minutosEnParadas * 60.0)} · ${"%.1f".format(it.metros / 1000)} km",
                color = Color.White, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TarjetaParada(p: Parada, numero: Int?, v: Visita?, vm: CarroViewModel, abrir: (String) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        border = if (numero == 1 && !p.visitada) BorderStroke(2.dp, Durazno) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (p.visitada) 0.25f else 0.5f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(26.dp).background(if (p.visitada) Color.Gray else AzulNoche, CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (p.visitada) "✓" else numero?.toString() ?: "•", color = Durazno, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.width(8.dp))
                Text(p.lugar.nombre ?: p.lugar.punto.texto(), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.quitarParada(p.id) }) { Text("✕") }
            }
            if (v != null && !p.visitada) {
                Text("${duracion(v.viajeSeg)} de camino · ${"%.1f".format(v.metros / 1000)} km", style = MaterialTheme.typography.bodySmall)
                Text("Llegas ${hora(v.llegada)} · sales ${hora(v.salida)}", fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Me quedo", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { vm.cambiarMinutos(p.id, -5) }, enabled = p.minutos > 0) { Text("−") }
                Text("${p.minutos} min", fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.cambiarMinutos(p.id, 5) }) { Text("+") }
                Spacer(Modifier.weight(1f))
                Checkbox(checked = p.visitada, onCheckedChange = { c -> vm.marcarVisitada(p.id, c) })
                Text("Hecha", style = MaterialTheme.typography.bodySmall)
            }
            if (!p.visitada) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = p.siguiente, onClick = { vm.marcarSiguiente(p.id) }, label = { Text("Ir ahora") })
                FilterChip(selected = p.final, onClick = { vm.marcarFinal(p.id) }, label = { Text("Terminar aquí") })
                TextButton(onClick = { abrir(vm.enlaceUnaParada(p)) }) { Text("Ir solo a esta") }
            }
        }
    }
}
