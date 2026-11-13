package com.davidsant7e.carro.red

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** HTTP simple y bloqueante (se llama desde Dispatchers.IO). */
object Http {
    /** Los servidores públicos de OpenStreetMap piden identificar la app. */
    const val AGENTE = "ElCarro/0.1 (Android; https://github.com/davidsant7e)"

    class ErrorHttp(val codigo: Int, mensaje: String) : IOException(mensaje)

    fun get(url: String): String = pedir(url, "GET", null, emptyMap())

    fun post(url: String, cuerpo: String, cabeceras: Map<String, String>): String =
        pedir(url, "POST", cuerpo, cabeceras + ("Content-Type" to "application/json; charset=utf-8"))

    private fun pedir(url: String, metodo: String, cuerpo: String?, cabeceras: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = metodo
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", AGENTE)
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("Accept-Language", "es")
            cabeceras.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (cuerpo != null) {
                c.doOutput = true
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val flujo = if (codigo in 200..299) c.inputStream else c.errorStream
            val texto = flujo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            // Valhalla y Google devuelven el detalle del error en JSON: se deja pasar para leerlo.
            if (codigo !in 200..299 && !texto.trimStart().startsWith("{")) {
                throw ErrorHttp(codigo, "El servidor respondió $codigo")
            }
            return texto
        } finally {
            c.disconnect()
        }
    }

    /**
     * Descarga una página web (siguiendo redirecciones) como la vería un navegador, hasta [maxBytes].
     * Se usa para leer las coordenadas de la página de un lugar de Google Maps.
     */
    fun pagina(url: String, maxBytes: Int = 2_000_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36")
            c.setRequestProperty("Accept-Language", "es-CO,es;q=0.9")
            val flujo = if (c.responseCode in 200..299) c.inputStream else c.errorStream ?: return ""
            flujo.use { f ->
                val buf = ByteArray(maxBytes)
                var n = 0
                while (n < maxBytes) {
                    val r = f.read(buf, n, maxBytes - n)
                    if (r < 0) break
                    n += r
                }
                return String(buf, 0, n, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Sigue las redirecciones de un enlace corto (maps.app.goo.gl, waze) sin descargar la página
     * y devuelve la URL final. Se detiene antes si una URL intermedia ya trae coordenadas.
     */
    fun resolverRedireccion(url: String, tieneCoordenadas: (String) -> Boolean, saltos: Int = 6): String {
        var actual = url
        repeat(saltos) {
            if (tieneCoordenadas(actual)) return actual
            val c = URL(actual).openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.requestMethod = "GET"
                c.connectTimeout = 10_000
                c.readTimeout = 10_000
                c.setRequestProperty("User-Agent", AGENTE)
                val codigo = c.responseCode
                val siguiente = c.getHeaderField("Location")
                if (codigo !in 300..399 || siguiente.isNullOrBlank()) return actual
                actual = URL(URL(actual), siguiente).toString()
            } finally {
                c.disconnect()
            }
        }
        return actual
    }
}
