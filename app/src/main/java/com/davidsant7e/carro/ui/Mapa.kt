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

/** Mapa del modo «Varias paradas»: la ruta en orden y cada parada con su número. Mantener presionado agrega. */
@Composable
fun MapaParadas(
    origen: Punto?,
    ruta: List<Punto>,
    paradas: List<com.davidsant7e.carro.nucleo.Parada>,
    alMantener: (Punto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val alMantenerActual = androidx.compose.runtime.rememberUpdatedState(alMantener)
    val mapa = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(4.65, -74.08))
            setOnTouchListener { v, _ -> v.parent?.requestDisallowInterceptTouchEvent(true); false }
        }
    }
    val eventos = remember {
        org.osmdroid.views.overlay.MapEventsOverlay(object : org.osmdroid.events.MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p?.let { alMantenerActual.value(Punto(it.latitude, it.longitude)) }
                return true
            }
        })
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
            m.overlays.add(eventos)
            if (ruta.size >= 2) {
                m.overlays.add(Polyline(m).apply {
                    setPoints(ruta.map { GeoPoint(it.lat, it.lon) })
                    outlinePaint.apply {
                        color = colorRuta(0).toArgb()
                        strokeWidth = 12f
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                })
            }
            origen?.let { m.overlays.add(marcador(m, it, "Salida")) }
            var n = 0
            for (p in paradas) {
                val etiqueta = if (p.visitada) "✓" else (++n).toString()
                m.overlays.add(Marker(m).apply {
                    position = GeoPoint(p.lugar.punto.lat, p.lugar.punto.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    title = "$etiqueta · ${p.lugar.nombre ?: "Parada"}"
                    icon = circuloNumerado(m.context, etiqueta, p.visitada)
                })
            }
            val puntos = paradas.map { it.lugar.punto } + listOfNotNull(origen) + ruta
            val firma = puntos.size to ruta.size
            if (puntos.size >= 2 && m.tag != firma) {
                m.tag = firma
                val caja = BoundingBox.fromGeoPoints(puntos.map { GeoPoint(it.lat, it.lon) })
                m.post { runCatching { m.zoomToBoundingBox(caja.increaseByScale(1.2f), false, 56) } }
            } else if (puntos.size == 1 && m.tag != firma) {
                m.tag = firma
                m.controller.setCenter(GeoPoint(puntos[0].lat, puntos[0].lon))
                m.controller.setZoom(15.0)
            }
            m.invalidate()
        },
    )
}

/** Círculo azul noche con el número en durazno (o gris con ✓ si ya se visitó). */
private fun circuloNumerado(ctx: android.content.Context, texto: String, hecha: Boolean): android.graphics.drawable.Drawable {
    val d = ctx.resources.displayMetrics.density
    val lado = (30 * d).toInt()
    val bmp = android.graphics.Bitmap.createBitmap(lado, lado, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val fondo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (hecha) AColor.GRAY else AColor.parseColor("#1F2A5C") }
    val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.parseColor("#F4A582"); style = Paint.Style.STROKE; strokeWidth = 2.5f * d }
    c.drawCircle(lado / 2f, lado / 2f, lado / 2f - 2 * d, fondo)
    c.drawCircle(lado / 2f, lado / 2f, lado / 2f - 2 * d, borde)
    val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AColor.parseColor("#F4A582"); textSize = 14 * d; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    c.drawText(texto, lado / 2f, lado / 2f - (letra.descent() + letra.ascent()) / 2, letra)
    return android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
}
