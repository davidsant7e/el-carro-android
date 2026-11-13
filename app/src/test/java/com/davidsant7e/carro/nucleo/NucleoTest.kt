package com.davidsant7e.carro.nucleo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NucleoTest {

    private fun cerca(esperado: Punto, real: Punto?, tolerancia: Double = 1e-4) {
        assertNotNull(real)
        assertEquals(esperado.lat, real!!.lat, tolerancia)
        assertEquals(esperado.lon, real.lon, tolerancia)
    }

    // ---------------------------------------------------------------- JSON

    @Test fun json_ida_y_vuelta() {
        val texto = """{"a":[1,2.5,-3e2],"b":"tilde á \"comillas\" ñ","c":null,"d":true,"e":{}}"""
        val v = Json.parse(texto)
        assertEquals(listOf(1.0, 2.5, -300.0), v.lista("a"))
        assertEquals("tilde á \"comillas\" ñ", v.texto("b"))
        assertEquals(v, Json.parse(Json.escribir(v)))
        assertEquals("""{"x":1,"y":2.5,"z":[true,null]}""", Json.escribir(linkedMapOf("x" to 1, "y" to 2.5, "z" to listOf(true, null))))
    }

    // ------------------------------------------------------------ geometría

    @Test fun polilinea_ejemplo_de_google() {
        val p = Geo.decodificar("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, p.size)
        cerca(Punto(38.5, -120.2), p[0], 1e-6)
        cerca(Punto(40.7, -120.95), p[1], 1e-6)
        cerca(Punto(43.252, -126.453), p[2], 1e-6)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", Geo.codificar(p))
    }

    @Test fun polilinea_precision_6_de_valhalla() {
        val ruta = listOf(Punto(4.6486, -74.0628), Punto(4.6201, -74.0702), Punto(4.5981, -74.076))
        val p = Geo.decodificar(Geo.codificar(ruta, 6), 6)
        ruta.zip(p).forEach { (a, b) -> cerca(a, b, 1e-6) }
        // Inicio real de una respuesta de valhalla1.openstreetmap.de (Bogotá, 7 nov 2026).
        cerca(Punto(4.648592, -74.062789), Geo.decodificar("_hvzGh{lglC", 6).first(), 1e-6)
    }

    @Test fun distancia_desplazar_y_alongar() {
        val a = Punto(4.6, -74.08)
        val b = Geo.desplazar(a, 90.0, 1000.0)
        assertEquals(1000.0, Geo.distancia(a, b), 1.0)
        assertEquals(90.0, Geo.rumbo(a, b), 0.5)
        val (medio, rumbo) = Geo.alongar(listOf(a, b), 0.5)
        assertEquals(500.0, Geo.distancia(a, medio), 1.0)
        assertEquals(90.0, rumbo, 0.5)
        val muestras = Geo.remuestrear(listOf(a, b), 100.0)
        assertEquals(11, muestras.size)
    }

    // ------------------------------------------------------------- enlaces

    private fun coords(e: Enlaces.Interpretacion): Punto? = (e as? Enlaces.Interpretacion.Coordenadas)?.lugar?.punto

    @Test fun enlaces_de_google_maps() {
        cerca(
            Punto(4.6015, -74.0663),
            coords(Enlaces.interpretar("https://www.google.com/maps/place/Museo+del+Oro/@4.6019,-74.0679,17z/data=!3m1!4b1!4m6!3m5!1s0x8e3f!8m2!3d4.6015!4d-74.0663!16s")),
        )
        val conNombre = Enlaces.interpretar("https://www.google.com/maps/place/Museo+del+Oro/@4.6019,-74.0679,17z/data=!3d4.6015!4d-74.0663")
        assertEquals("Museo del Oro", (conNombre as Enlaces.Interpretacion.Coordenadas).lugar.nombre)
        cerca(Punto(4.65, -74.05), coords(Enlaces.interpretar("https://maps.google.com/?q=4.65,-74.05")))
        cerca(Punto(4.65, -74.05), coords(Enlaces.interpretar("https://www.google.com/maps/search/4.65,+-74.05?entry=tts")))
        cerca(Punto(4.70, -74.10), coords(Enlaces.interpretar("https://www.google.com/maps/dir/4.6,-74.0/4.70,-74.10/@4.6,-74.05,13z")))
    }

    @Test fun enlace_corto_pide_redireccion_y_conserva_nombre() {
        val e = Enlaces.interpretar("Parque de la 93\nhttps://maps.app.goo.gl/AbCdEf123")
        assertTrue(e is Enlaces.Interpretacion.Redirigir)
        e as Enlaces.Interpretacion.Redirigir
        assertEquals("https://maps.app.goo.gl/AbCdEf123", e.url)
        assertEquals("Parque de la 93", e.nombre)
    }

    @Test fun enlaces_de_waze() {
        cerca(Punto(4.6097, -74.0817), coords(Enlaces.interpretar("Usa Waze: https://waze.com/ul?ll=4.6097,-74.0817&navigate=yes")))
        cerca(Punto(4.6097, -74.0817), coords(Enlaces.interpretar("https://ul.waze.com/ul?place=ChIJ&ll=4.6097%2C-74.0817&navigate=yes")))
        // geohash "d2g6" + más precisión: centro aproximado en Bogotá
        val g = Enlaces.geohash("d2g6f1x")!!
        assertTrue(g.lat in 4.0..5.5 && g.lon in -75.0..-73.5)
        assertTrue(coords(Enlaces.interpretar("https://waze.com/ul/hd2g6f1x")) != null)
    }

    @Test fun geo_y_coordenadas_sueltas() {
        cerca(Punto(4.6, -74.08), coords(Enlaces.interpretar("geo:4.6,-74.08")))
        val g = Enlaces.interpretar("geo:0,0?q=4.6,-74.08(Mi casa)") as Enlaces.Interpretacion.Coordenadas
        assertEquals("Mi casa", g.lugar.nombre)
        assertEquals(Enlaces.Interpretacion.Buscar("Calle 72 Bogotá"), Enlaces.interpretar("geo:0,0?q=Calle%2072%20Bogot%C3%A1"))
        cerca(Punto(4.6097, -74.0817), coords(Enlaces.interpretar(" 4.6097, -74.0817 ")))
        assertEquals(Enlaces.Interpretacion.Buscar("Unicentro Bogotá"), Enlaces.interpretar("Unicentro Bogotá"))
        assertEquals(Enlaces.Interpretacion.Nada, Enlaces.interpretar("   "))
        assertTrue(Enlaces.interpretar("0,0") !is Enlaces.Interpretacion.Coordenadas)
    }

    // ------------------------------------------------------- comparar y elegir

    /** Ruta en "L": primero al este y luego al sur, o al revés. */
    private fun ruta(vararg p: Punto, segundos: Double, estrategia: String = "x") =
        Ruta(p.toList(), Geo.longitud(p.toList()), segundos, estrategia)

    private val o = Punto(4.60, -74.10)
    private val d = Punto(4.58, -74.08)
    private val esquinaNE = Punto(4.60, -74.08)
    private val esquinaSO = Punto(4.58, -74.10)

    @Test fun compartido_y_seleccion() {
        val a = ruta(o, esquinaNE, d, segundos = 600.0)
        val aCasi = ruta(o, esquinaNE, Punto(4.581, -74.0801), d, segundos = 610.0)
        val b = ruta(o, esquinaSO, d, segundos = 700.0)
        val lenta = ruta(o, Punto(4.65, -74.2), d, segundos = 2000.0)
        assertTrue(Seleccion.parecido(Seleccion.celdas(a.puntos), Seleccion.celdas(aCasi.puntos)) > 0.9)
        assertTrue(Seleccion.parecido(Seleccion.celdas(a.puntos), Seleccion.celdas(b.puntos)) < 0.2)
        val elegidas = Seleccion.elegir(listOf(b, lenta, aCasi, a), Preferencias())
        assertEquals(listOf(a, b), elegidas) // aCasi repite a; la lenta pasa del +60 %
    }

    @Test fun anclas_caen_en_el_tramo_propio_y_en_orden() {
        val rapida = listOf(o, esquinaNE, d)
        val otra = listOf(o, esquinaSO, d)
        val anclas = Seleccion.anclas(otra, rapida, 3)
        assertTrue(anclas.isNotEmpty() && anclas.size <= 3)
        val celdasRapida = Seleccion.celdas(rapida)
        anclas.forEach { assertTrue(Seleccion.celdas(listOf(it, it)).none { c -> c in celdasRapida }) }
        // En orden de recorrido: la distancia desde el origen sobre la ruta crece.
        val muestras = Geo.remuestrear(otra, Seleccion.CELDA_M / 2)
        val posiciones = anclas.map { a -> muestras.indexOfFirst { it == a } }
        assertEquals(posiciones.sorted(), posiciones)
        assertTrue(Seleccion.anclas(rapida, rapida, 3).isEmpty())
    }

    // ---------------------------------------------------------------- plan

    @Test fun desvios_quedan_a_ambos_lados() {
        val base = listOf(o, Punto(4.60, -74.00)) // ~11 km hacia el este
        val desvios = Plan.desvios(base)
        assertEquals(6, desvios.size)
        val izq = desvios.first { it.estrategia.startsWith("desvio_izquierda_50") }.via.single()
        val der = desvios.first { it.estrategia.startsWith("desvio_derecha_50") }.via.single()
        assertTrue(izq.lat > 4.60 && der.lat < 4.60) // yendo al este, la izquierda es el norte
        assertEquals(Plan.separacion(Geo.longitud(base)), Geo.distancia(izq, Punto(4.60, izq.lon)), 30.0)
        assertEquals("Por la izquierda, a mitad de camino", Plan.nombre(desvios.first().estrategia))
        assertEquals(3, Plan.bloqueos(base).size)
        assertTrue(Plan.desvios(listOf(o, Punto(4.6005, -74.1))).isEmpty()) // trayecto corto
    }

    @Test fun cuerpo_valhalla() {
        val c = Valhalla.Consulta("desvio", via = listOf(Punto(4.61, -74.09)), opciones = mapOf("use_highways" to 0.0))
        val j = Json.parse(Valhalla.cuerpo(o, d, Vehiculo.MOTO, c, evitarPeajes = true))
        assertEquals("motorcycle", j.texto("costing"))
        val ubic = j.lista("locations")!!
        assertEquals(3, ubic.size)
        assertEquals("through", ubic[1].texto("type"))
        assertEquals(0.0, j.obj("costing_options").obj("motorcycle").num("use_tolls"))
        assertTrue(Valhalla.url(Valhalla.SERVIDOR, "{}").startsWith("https://valhalla1.openstreetmap.de/route?json=%7B"))
    }

    private fun respuestaValhalla(vararg rutas: Pair<List<Punto>, Double>): String {
        fun viaje(p: List<Punto>, seg: Double) = mapOf(
            "legs" to listOf(mapOf("shape" to Geo.codificar(p, 6))),
            "summary" to mapOf("length" to Geo.longitud(p) / 1000, "time" to seg, "has_toll" to false),
        )
        val (p0, s0) = rutas.first()
        return Json.escribir(mapOf("trip" to viaje(p0, s0), "alternates" to rutas.drop(1).map { mapOf("trip" to viaje(it.first, it.second)) }))
    }

    @Test fun leer_valhalla_y_error() {
        val rutas = Valhalla.leer(respuestaValhalla(listOf(o, esquinaNE, d) to 600.0, listOf(o, esquinaSO, d) to 700.0), "base")
        assertEquals(2, rutas.size)
        assertEquals("base·alt1", rutas[1].estrategia)
        assertEquals(700.0, rutas[1].segundos, 0.0)
        cerca(esquinaSO, rutas[1].puntos[1], 1e-6)
        try {
            Valhalla.leer("""{"error_code":442,"error":"No path could be found for input","status_code":400}""", "x")
            throw AssertionError("debía fallar")
        } catch (e: ErrorRuta) {
            assertTrue("No path" in e.message!!)
        }
    }

    // -------------------------------------------------------------- google

    @Test fun url_de_google_maps_y_waze() {
        val url = Google.urlMaps(o, d, listOf(Punto(4.59, -74.1), Punto(4.58, -74.09)), Vehiculo.MOTO)
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1&origin=4.600000,-74.100000&destination=4.580000,-74.080000"))
        assertTrue("&travelmode=two-wheeler" in url)
        assertTrue("&waypoints=4.590000%2C-74.100000%7C4.580000%2C-74.090000" in url)
        assertTrue("waypoints" !in Google.urlMaps(null, d, emptyList(), Vehiculo.CARRO))
        assertEquals("https://waze.com/ul?ll=4.580000,-74.080000&navigate=yes", Google.urlWaze(d))
    }

    @Test fun routes_api_cuerpo_y_respuesta() {
        val j = Json.parse(Google.cuerpoRoutes(o, d, Vehiculo.CARRO, anclas = listOf(esquinaSO), alternativas = true))
        assertEquals("TRAFFIC_AWARE", j.texto("routingPreference"))
        assertEquals(true, (j.lista("intermediates")!![0] as Map<*, *>)["via"])
        assertNull((j as Map<*, *>)["computeAlternativeRoutes"]) // la API no permite ambas cosas
        val r = Google.leerRoutes("""{"routes":[{"distanceMeters":5123,"duration":"901s","staticDuration":"700s","polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC"}}]}""")
        assertEquals(901.0, r[0].segundos, 0.0)
        assertEquals(700.0, Google.aRuta(r[0], 0).segundos, 0.0)
        assertEquals(2, r[0].puntos.size)
        assertEquals(
            listOf("Museo del Oro, Cra. 6 #15-88, Bogotá", "Museo del Oro, Bogotá", "Cra. 6 #15-88, Bogotá", "Museo del Oro, Cra. 6, Bogotá"),
            Nominatim.variantes("Museo del Oro, Cra. 6 #15-88, Bogotá"),
        )
        assertEquals(listOf("Unicentro"), Nominatim.variantes(" Unicentro "))
        assertEquals(1, Nominatim.leer("""[{"lat":"4.6","lon":"-74.08","display_name":"Bogotá"}]""").size)
    }

    // ------------------------------------------------------------- buscador

    @Test fun buscador_completo_con_red_simulada() {
        val norte = listOf(o, esquinaNE, d)
        val sur = listOf(o, esquinaSO, d)
        val diagonal = listOf(o, Punto(4.59, -74.09), d)
        var gets = 0
        val buscador = Buscador(
            get = { url ->
                gets++
                val cuerpo = java.net.URLDecoder.decode(url.substringAfter("json="), "UTF-8")
                when {
                    "\"alternates\":2" in cuerpo -> respuestaValhalla(norte to 600.0, sur to 660.0)
                    "shortest" in cuerpo -> respuestaValhalla(diagonal to 640.0)
                    "exclude_polygons" in cuerpo -> """{"error":"No path could be found for input"}"""
                    else -> respuestaValhalla(norte to 600.0)
                }
            },
            post = { _, _, _ -> error("sin Google") },
            pausaMs = 0,
        )
        var ultimo = 0 to 0
        val r = buscador.buscar(o, d, Preferencias(), progreso = { h, t -> ultimo = h to t })
        assertEquals(listOf(600.0, 640.0, 660.0), r.rutas.map { it.ruta.segundos })
        assertEquals("La más rápida", r.rutas[0].nombre)
        assertTrue(r.rutas[0].anclas.isEmpty())
        assertTrue(r.rutas[1].anclas.isNotEmpty())
        assertTrue(r.rutas.any { "la más corta" in it.nombre || "corta" in it.nombre })
        assertEquals(r.consultas, gets)
        assertEquals(ultimo.second, ultimo.first)
        assertTrue(r.avisos.single().startsWith("3 de"))
    }

    @Test fun buscador_con_trafico_de_google() {
        val norte = listOf(o, esquinaNE, d)
        val sur = listOf(o, esquinaSO, d)
        val posts = ArrayList<String>()
        val buscador = Buscador(
            get = { url ->
                val cuerpo = java.net.URLDecoder.decode(url.substringAfter("json="), "UTF-8")
                if ("\"alternates\":2" in cuerpo) respuestaValhalla(norte to 600.0, sur to 660.0) else respuestaValhalla(norte to 600.0)
            },
            post = { _, cuerpo, cab ->
                posts += cuerpo
                assertEquals("CLAVE", cab["X-Goog-Api-Key"])
                """{"routes":[{"distanceMeters":5000,"duration":"1200s","staticDuration":"620s","polyline":{"encodedPolyline":"${Geo.codificar(sur)}"}}]}"""
            },
            pausaMs = 0,
        )
        val r = buscador.buscar(o, d, Preferencias(), claveGoogle = "CLAVE")
        assertTrue(r.rutas.all { it.segundosConTrafico == 1200.0 })
        assertTrue(posts.first().contains("computeAlternativeRoutes"))
        assertTrue(posts.drop(1).any { "intermediates" in it })
    }
}
