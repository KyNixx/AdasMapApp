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
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity

class MainActivity : ComponentActivity() { // <-- Alterado aqui

    private lateinit var mapView: MapView
    private lateinit var debugText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Inicialização obrigatória do Mapsforge
        AndroidGraphicFactory.createInstance(application)

        val frameLayout = FrameLayout(this)
        
        mapView = MapView(this)
        frameLayout.addView(mapView)

        // Criar uma caixa de texto transparente para os Logs no ecrã
        debugText = TextView(this).apply {
            text = "A aguardar ligação da APU... (porta 5000)"
            setBackgroundColor(Color.parseColor("#90000000")) // Fundo preto semi-transparente
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 14f
        }
        
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM // Fica colado à parte de baixo do ecrã
        }
        frameLayout.addView(debugText, params)

        setContentView(frameLayout)

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

                mapView.setCenter(LatLong(38.756862,-9.192776)) // Tagus Park
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
                // Configurar o socket com reuseAddress para evitar erro se a porta estiver "presa"
                socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(java.net.InetSocketAddress(port))
                
                val buffer = ByteArray(2048)
                Log.d("AdasMapUDP", "Servidor UDP iniciado na porta $port")

                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    // Fica à espera (bloqueante, seguro pq está numa IO thread)
                    socket.receive(packet)
                    
                    // .trim() remove expaços em branco e quebras de linha indesejadas
                    val message = String(packet.data, 0, packet.length).trim()
                    Log.d("AdasMapUDP", "Recebido: $message")

                    try {
                        val json = JSONObject(message)
                        if (json.has("latitude") && json.has("longitude")) {
                            val lat = json.getDouble("latitude")
                            val lon = json.getDouble("longitude")

                            Log.d("AdasMapUDP", "Variáveis lidas do JSON -> Lat: $lat | Lon: $lon")

                            // Voltar para a Thread Principal para mexer na interface/mapa
                            withContext(Dispatchers.Main) {
                                // Mover o centro do mapa para as novas coordenadas
                                Log.d("AdasMapUDP", "Atualizando o mapa para o novo centro...")
                                mapView.model.mapViewPosition.center = LatLong(lat, lon)
                                
                                // Mostrar também o sucesso no ecrã para debug
                                debugText.text = "SUCESSO!\nÚltimo pacote recebido:\nLat: $lat\nLon: $lon\nRaw:\n$message"
                            }
                        } else {
                            Log.w("AdasMapUDP", "O JSON recebido não contém 'latitude' ou 'longitude'")
                            withContext(Dispatchers.Main) {
                                debugText.text = "Aviso: JSON incompleto.\nRaw: $message"
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("AdasMapUDP", "Erro ao processar JSON: ${e.message}")
                        withContext(Dispatchers.Main) {
                            debugText.text = "Erro JSON.\nRaw recebido: $message"
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AdasMapUDP", "Erro fatal no socket: ${e.message}")
                withContext(Dispatchers.Main) {
                    debugText.text = "Erro no Socket UDP:\n${e.message}"
                }
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