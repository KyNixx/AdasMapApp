package com.example.adasmapapp

import android.os.Bundle
import androidx.activity.ComponentActivity // <-- Alterado aqui

import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil

import org.mapsforge.map.layer.renderer.TileRendererLayer

import org.mapsforge.core.model.LatLong
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import java.io.FileOutputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import android.util.Log

class MainActivity : ComponentActivity() { // <-- Alterado aqui

    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicialização obrigatória do Mapsforge
        AndroidGraphicFactory.createInstance(application)

        mapView = MapView(this)
        setContentView(mapView)

        // Corrotina para fazer a cópia pesada em segundo plano e não bloquear a App
        lifecycleScope.launch(Dispatchers.IO) {
            val mapFileOnDisk = File(cacheDir, "tagusPark.map")
            if (!mapFileOnDisk.exists()) {
                assets.open("tagusPark.map").use { input ->
                    FileOutputStream(mapFileOnDisk).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            // Quando a cópia terminar, carregamos o mapa na Thread Principal (UI)
            withContext(Dispatchers.Main) {
                val mapFile = MapFile(mapFileOnDisk)

                val tileCache = AndroidUtil.createTileCache(
                    this@MainActivity,
                    "mapcache",
                    mapView.model.displayModel.tileSize,
                    1f,
                    mapView.model.frameBufferModel.overdrawFactor
                )

                val layer = TileRendererLayer(
                    tileCache,
                    mapFile,
                    mapView.model.mapViewPosition,
                    AndroidGraphicFactory.INSTANCE
                )

                layer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)

                mapView.layerManager.layers.add(layer)

                mapView.setCenter(LatLong(38.736862,-9.302776)) // Tagus Park
                mapView.setZoomLevel(15.toByte())
                
                // Iniciar à escuta do pacote UDP após o mapa estar pronto
                startUdpServer()
            }
        }
    }

    private fun startUdpServer() {
        // Lançar rotina secundária (IO Thread) para não bloquear o telemóvel
        lifecycleScope.launch(Dispatchers.IO) {
            val port = 5000 // A porta onde o tablet vai estar à escuta
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(port)
                val buffer = ByteArray(2048)
                Log.d("AdasMapUDP", "Servidor UDP iniciado na porta $port")

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    // Fica à espera (bloqueante, seguro pq está numa IO thread)
                    socket.receive(packet)
                    
                    val message = String(packet.data, 0, packet.length)
                    Log.d("AdasMapUDP", "Recebido: $message")

                    try {
                        val json = JSONObject(message)
                        if (json.has("lat") && json.has("lon")) {
                            val lat = json.getDouble("lat")
                            val lon = json.getDouble("lon")

                            // Voltar para a Thread Principal para mexer na interface/mapa
                            withContext(Dispatchers.Main) {
                                mapView.setCenter(LatLong(lat, lon))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AdasMapUDP", "Erro ao processar JSON: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("AdasMapUDP", "Erro fatal no socket: ${e.message}")
            } finally {
                socket?.close()
            }
        }
    }

    override fun onDestroy() {
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
        super.onDestroy()
    }
}