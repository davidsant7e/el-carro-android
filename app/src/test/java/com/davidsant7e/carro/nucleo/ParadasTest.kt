package com.davidsant7e.carro.nucleo

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParadasTest {

    private fun permutaciones(l: List<Int>): List<List<Int>> =
        if (l.size <= 1) listOf(l) else l.flatMap { x -> permutaciones(l - x).map { listOf(x) + it } }

    private fun matrizAzar(n: Int, semilla: Int): Array<DoubleArray> {
        val r = Random(semilla)
        // Puntos en un plano con viajes asimétricos (una vía más lenta que la otra), como en la ciudad.
        val xy = List(n + 1) { r.nextDouble(0.0, 10_000.0) to r.nextDouble(0.0, 10_000.0) }
        return Array(n + 1) { i ->
            DoubleArray(n + 1) { j ->
                if (i == j) 0.0 else {
                    val d = Math.hypot(xy[i].first - xy[j].first, xy[i].second - xy[j].second)
                    d / 8.0 * (1.0 + 0.3 * r.nextDouble())
                }
            }
        }
    }

    @Test
    fun ordenExactoIgualAFuerzaBruta() {
        for (semilla in 1..25) {
            val n = 2 + semilla % 6 // 2..7 paradas
            val t = matrizAzar(n, semilla)
            val mejor = permutaciones((1..n).toList()).minOf { Optimizador.costo(t, it) }
            val o = Optimizador.orden(t)
            assertEquals((1..n).toSet(), o.toSet())
            assertEquals("semilla $semilla", mejor, Optimizador.costo(t, o), 1e-6)
        }
    }

    @Test
    fun terminaDondeConvieneNoVuelveAlInicio() {
        // Salida en 0; paradas en línea 1 → 2 → 3: lo lógico es ir en orden y no volver.
        val pos = listOf(0.0, 10.0, 20.0, 30.0)
        val t = Array(4) { i -> DoubleArray(4) { j -> Math.abs(pos[i] - pos[j]) } }
        assertEquals(listOf(1, 2, 3), Optimizador.orden(t))
        // Salida en 12, paradas en 10, 20 y 0: conviene ir primero a 20 y luego bajar (8+10+10 = 28 < 2+10+20).
        val pos2 = listOf(12.0, 10.0, 20.0, 0.0)
        val t2 = Array(4) { i -> DoubleArray(4) { j -> Math.abs(pos2[i] - pos2[j]) } }
        assertEquals(listOf(2, 1, 3), Optimizador.orden(t2))
    }

    @Test
    fun muchasParadasUsaAproximacionCercanaAlOptimo() {
        val n = 16
        val t = matrizAzar(n, 99)
        val o = Optimizador.orden(t)
        assertEquals((1..n).toSet(), o.toSet())
        // No debe ser peor que el vecino más cercano simple.
        val nn = ArrayList<Int>(); var actual = 0; val pend = (1..n).toMutableSet()
        while (pend.isNotEmpty()) { val s = pend.minBy { t[actual][it] }; nn += s; pend -= s; actual = s }
        assertTrue(Optimizador.costo(t, o) <= Optimizador.costo(t, nn) + 1e-9)
    }

    @Test
    fun paradaSinCaminoQuedaAlFinalSinRomper() {
        val inf = Double.POSITIVE_INFINITY
        val t = arrayOf(
            doubleArrayOf(0.0, 10.0, 20.0),
            doubleArrayOf(10.0, 0.0, inf),
            doubleArrayOf(20.0, 5.0, 0.0),
        )
        assertEquals(listOf(2, 1), Optimizador.orden(t))
    }

    @Test
    fun horarioConEstancias() {
        val a = Parada("a", Lugar(Punto(4.6, -74.0), "Banco"), minutos = 15)
        val b = Parada("b", Lugar(Punto(4.7, -74.1), "Mercado"), minutos = 30)
        val inicio = 1_000_000L
        val it = Recorrido.horario(
            Punto(4.5, -74.0), inicio, listOf(a, b),
            listOf(Tramo(600.0, 4000.0, listOf(Punto(4.5, -74.0), Punto(4.6, -74.0))), Tramo(900.0, 6000.0, emptyList())),
            Vehiculo.MOTO,
        )
        assertEquals(inicio + 600_000, it.visitas[0].llegada)
        assertEquals(inicio + 600_000 + 900_000, it.visitas[0].salida)
        assertEquals(it.visitas[0].salida + 900_000, it.visitas[1].llegada)
        assertEquals(it.visitas[1].llegada + 1_800_000, it.fin)
        assertEquals(1500.0, it.segundosViaje, 1e-9)
        assertEquals(45, it.minutosEnParadas)
        assertEquals(10_000.0, it.metros, 1e-9)
    }

    @Test
    fun paradaActualPorCercania() {
        val a = Parada("a", Lugar(Punto(4.6500, -74.0500)))
        val b = Parada("b", Lugar(Punto(4.6510, -74.0500)), visitada = true)
        val aqui = Punto(4.6509, -74.0500) // ~100 m de a, ~10 m de b (pero b ya se visitó)
        assertEquals("a", Recorrido.paradaActual(aqui, listOf(a, b))?.id)
        assertNull(Recorrido.paradaActual(Punto(4.70, -74.05), listOf(a, b)))
    }

    @Test
    fun gruposParaGoogleMaps() {
        val p = List(23) { Punto(4.0 + it / 100.0, -74.0) }
        val g = Recorrido.grupos(p)
        assertEquals(listOf(10, 10, 3), g.map { it.size })
        assertEquals(1, Recorrido.grupos(p.take(10)).size)
    }

    @Test
    fun guardarYLeerRuta() {
        val a = Parada("a", Lugar(Punto(4.6, -74.0), "Banco"), minutos = 15, visitada = true)
        val b = Parada("b", Lugar(Punto(4.7, -74.1), null), minutos = 30)
        val it = Recorrido.horario(Punto(4.5, -74.0), 5_000L, listOf(b), listOf(Tramo(60.0, 500.0, listOf(Punto(4.5, -74.0), Punto(4.7, -74.1)))), Vehiculo.CARRO)
        val (paradas, itin) = Recorrido.deJson(Recorrido.aJson(listOf(a, b), it))
        assertEquals(listOf(a, b), paradas)
        assertEquals(it.visitas, itin!!.visitas)
        assertEquals(2, itin.puntos.size)
        assertEquals(4.7, itin.puntos[1].lat, 1e-6)
        assertEquals(emptyList<Parada>() to null, Recorrido.deJson("basura{"))
    }

    @Test
    fun matrizYTramosDeValhalla() {
        val matriz = """{"sources_to_targets":[
          [{"from_index":0,"to_index":0,"time":0,"distance":0},{"from_index":0,"to_index":1,"time":300,"distance":2.5}],
          [{"from_index":1,"to_index":0,"time":320,"distance":2.6},{"from_index":1,"to_index":1,"time":0,"distance":0}]]}"""
        val t = ValhallaParadas.leerMatriz(matriz, 2)
        assertEquals(300.0, t[0][1], 1e-9)
        assertEquals(320.0, t[1][0], 1e-9)
        val sinCamino = """{"sources_to_targets":[[{"from_index":0,"to_index":1,"time":null}]]}"""
        assertEquals(Double.POSITIVE_INFINITY, ValhallaParadas.leerMatriz(sinCamino, 2)[0][1], 0.0)
        val forma = Geo.codificar(listOf(Punto(4.6, -74.0), Punto(4.61, -74.01)), 6)
        val ruta = """{"trip":{"legs":[{"summary":{"time":120.5,"length":1.2},"shape":"$forma"},{"summary":{"time":60,"length":0.4},"shape":""}]}}"""
        val tramos = ValhallaParadas.leerTramos(ruta)
        assertEquals(2, tramos.size)
        assertEquals(1200.0, tramos[0].metros, 1e-6)
        assertEquals(2, tramos[0].puntos.size)
        val cuerpo = Json.parse(ValhallaParadas.cuerpoRuta(listOf(Punto(1.0, 2.0), Punto(3.0, 4.0)), Vehiculo.MOTO, true))
        assertEquals("motorcycle", cuerpo.texto("costing"))
        assertEquals("break", cuerpo.lista("locations")!![1].texto("type"))
        assertTrue(ValhallaParadas.urlMatriz("https://x/", "{}").startsWith("https://x/sources_to_targets?json="))
    }
}

class ParadasFijasTest {
    private fun lineal(pos: List<Double>) = Array(pos.size) { i -> DoubleArray(pos.size) { j -> Math.abs(pos[i] - pos[j]) } }

    @Test
    fun paradaFinalElegida() {
        // Salida en 0, paradas en 10, 20, 30: sin restricción iría 1,2,3; si la final es la 1 (en 10)…
        val t = lineal(listOf(0.0, 10.0, 20.0, 30.0))
        assertEquals(listOf(1, 2, 3), Optimizador.orden(t))
        assertEquals(1, Optimizador.orden(t, ultimo = 1).last())
        assertEquals(50.0, Optimizador.costo(t, Optimizador.orden(t, ultimo = 1)), 1e-9) // 3,2,1 o 2,3,1 (empate)
    }

    @Test
    fun siguienteElegidaYFinal() {
        val t = lineal(listOf(0.0, 10.0, 20.0, 30.0, 40.0))
        assertEquals(listOf(3, 2, 1, 4), Optimizador.orden(t, primero = 3, ultimo = 4).let { o ->
            // primero 3 (en 30), luego lo más barato hasta terminar en 4 (en 40): 30→20→10→40
            o
        })
        assertEquals(3, Optimizador.orden(t, primero = 3).first())
        assertEquals(listOf(4, 3, 2, 1), Optimizador.orden(t, primero = 4))
    }

    @Test
    fun fijasConMuchasParadas() {
        val r = kotlin.random.Random(7)
        val pos = List(16) { r.nextDouble(0.0, 100.0) }
        val t = lineal(pos)
        val o = Optimizador.orden(t, primero = 5, ultimo = 9)
        assertEquals(5, o.first()); assertEquals(9, o.last()); assertEquals((1..15).toSet(), o.toSet())
    }

    @Test
    fun guardaFinalYSiguiente() {
        val a = Parada("a", Lugar(Punto(4.6, -74.0), "A"), final = true)
        val b = Parada("b", Lugar(Punto(4.7, -74.1), "B"), siguiente = true)
        assertEquals(listOf(a, b), Recorrido.deJson(Recorrido.aJson(listOf(a, b), null)).first)
    }

    @Test
    fun coordenadasEnLaPaginaDeGoogle() {
        assertEquals(Punto(6.2045, -75.5778), Enlaces.coordenadasDePagina(
            """<meta content="https://maps.google.com/maps/api/staticmap?center=6.2045%2C-75.5778&amp;zoom=17" property="og:image">"""))
        assertEquals(Punto(6.2, -75.57), Enlaces.coordenadasDePagina("""window.APP_INITIALIZATION_STATE=[[[1234.5,-75.57,6.2],[0,0,0]"""))
        assertNull(Enlaces.coordenadasDePagina("<html>nada</html>"))
    }

    @Test
    fun variantesDelNombreDeGoogle() {
        val v = Nominatim.variantes("Homecenter | Medellín Av Los Industriales, Cra. 49, Av. Las Vegas #14-135, Medellín, El Poblado")
        assertTrue(v.toString(), v.size <= 4)
        assertTrue(v.toString(), v.any { it == "Homecenter, El Poblado" || it == "Homecenter, Medellín" })
        assertTrue(v.toString(), v.any { !it.contains('#') && it.contains("Cra. 49") })
        assertEquals(listOf("Calle 72 Bogotá"), Nominatim.variantes("Calle 72 Bogotá"))
    }
}
