package com.davidsant7e.carro.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davidsant7e.carro.datos.Ubicacion

class MainActivity : ComponentActivity() {
    private val vm: CarroViewModel by viewModels()

    private val pedirUbicacion = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it } && vm.estado.value.destino != null) vm.buscarRutas()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Ubicacion.tienePermiso(this)) {
            pedirUbicacion.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        if (savedInstanceState == null) recibir(intent)
        setContent {
            TemaCarro {
                val e by vm.estado.collectAsStateWithLifecycle()
                PantallaPrincipal(e, vm, pegar = ::portapapeles, abrir = ::abrir)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recibir(intent)
    }

    /** Lugar compartido desde Google Maps / Waze (texto) o enlace geo:. */
    private fun recibir(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let(vm::recibir)
            Intent.ACTION_VIEW -> intent.dataString?.let(vm::recibir)
        }
    }

    private fun portapapeles(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val texto = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (texto.isNullOrBlank()) Toast.makeText(this, "No hay nada copiado.", Toast.LENGTH_SHORT).show()
        return texto
    }

    /** Abre el enlace en la app de Google Maps o Waze si está instalada; si no, en el navegador. */
    private fun abrir(url: String) {
        val uri = Uri.parse(url)
        val paquete = when {
            "google.com/maps" in url -> "com.google.android.apps.maps"
            "waze.com" in url -> "com.waze"
            else -> null
        }
        val intento = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(Intent(intento).apply { paquete?.let { setPackage(it) } })
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(intento)
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "No hay una app para abrir el mapa.", Toast.LENGTH_LONG).show()
            }
        }
    }
}
