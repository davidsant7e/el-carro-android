package com.davidsant7e.carro.ui

import android.graphics.Color as AColor
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.davidsant7e.carro.nucleo.Punto
import com.davidsant7e.carro.nucleo.RutaMostrada
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** Colores de las rutas, en orden (la primera es la más rápida). */
val ColoresRutas = listOf(
    Color(0xFF1F6FEB), Color(0xFFD9480F), Color(0xFF2B8A3E), Color(0xFF9C36B5),
    Color(0xFFE8590C), Color(0xFF0C8599), Color(0xFFC2255C), Color(0xFF5C7CFA),
    Color(0xFF7048E8), Color(0xFF66A80F),
)

fun colorRuta(i: Int) = ColoresRutas[i % ColoresRutas.size]

/** Mapa de OpenStreetMap con todas las rutas; la elegida va encima y más gruesa. */
@Composable
fun MapaRutas(
    origen: Punto?,
    destino: Punto?,
    rutas: List<RutaMostrada>,
    seleccion: Int,
    alTocarRuta: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val mapa = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(4.65, -74.08))
            // Dentro de una lista que se desplaza: el mapa se queda con los gestos.
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    DisposableEffect(Unit) {
        mapa.onResume()
        onDispose { mapa.onPause(); mapa.onDetach() }
    }
    AndroidView(
        factory = { mapa },
        modifier = modifier,
        update = { m ->
            m.overlays.clear()
            // Primero las demás, al final la elegida (queda encima).
            val orden = rutas.indices.sortedBy { if (it == seleccion) 1 else 0 }
            for (i in orden) {
                val r = rutas[i]
                val linea = Polyline(m).apply {
                    setPoints(r.ruta.puntos.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.apply {
                        color = colorRuta(i).copy(alpha = if (i == seleccion) 1f else 0.55f).toArgb()
                        strokeWidth = if (i == seleccion) 14f else 7f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    setOnClickListener { _, _, _ -> alTocarRuta(i); true }
                }
                m.overlays.add(linea)
            }
            origen?.let { m.overlays.add(marcador(m, it, "Salida")) }
            destino?.let { m.overlays.add(marcador(m, it, "Destino")) }
            val puntos = rutas.flatMap { it.ruta.puntos } + listOfNotNull(origen, destino)
            if (puntos.size >= 2 && m.tag !== rutas) { // encuadrar solo cuando cambian las rutas
                m.tag = rutas
                val caja = BoundingBox.fromGeoPoints(puntos.map { GeoPoint(it.lat, it.lon) })
                m.post { runCatching { m.zoomToBoundingBox(caja.increaseByScale(1.15f), false, 48) } }
            }
            m.invalidate()
        },
    )
}

private fun marcador(m: MapView, p: Punto, titulo: String) = Marker(m).apply {
    position = GeoPoint(p.lat, p.lon)
    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
    title = titulo
    if (titulo == "Salida") icon = icon?.mutate()?.apply { setTint(AColor.parseColor("#1F2A5C")) }
}
