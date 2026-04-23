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
    private lateinit var locationMarker: org.mapsforge.map.layer.overlay.Marker
    
    // Dicionário para guardar marcadores de outros veículos (chave: stationID)
    private val otherVehicles = mutableMapOf<Int, org.mapsforge.map.layer.overlay.Marker>()

    // Função auxiliar para desenhar pontos redondos dinâmicos
    private fun createDotBitmap(colorHex: String): org.mapsforge.core.graphics.Bitmap {
        val dotSize = 40
        val bitmap = android.graphics.Bitmap.createBitmap(dotSize, dotSize, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Círculo interior
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor(colorHex)
            isAntiAlias = true
        }
        canvas.drawCircle(dotSize / 2f, dotSize / 2f, dotSize / 2f, paint)
        
        // Borda branca do ponto
        val borderPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }
        canvas.drawCircle(dotSize / 2f, dotSize / 2f, (dotSize / 2f) - 2f, borderPaint)

        val drawable = android.graphics.drawable.BitmapDrawable(resources, bitmap)
        return AndroidGraphicFactory.convertToBitmap(drawable)
    }

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

                // ======== CRIAR PONTO AZUL DO NOSSO VEÍCULO (144) ========
                val markerBitmap = createDotBitmap("#0000FF") // Azul puro
                
                locationMarker = org.mapsforge.map.layer.overlay.Marker(
                    LatLong(38.756862, -9.192776), 
                    markerBitmap, 
                    0, 0 // Desvios/Offsets
                )
                
                // Adicionamos o marcador ao mapa (por cima de tudo o resto)
                mapView.layerManager.layers.add(locationMarker)
                // ====================================================

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
                            
                            // Tenta extrair o ID, se não existir um id no JSON assome que é a 144
                            val stationId = if (json.has("stationID")) json.getInt("stationID") else 144

                            Log.d("AdasMapUDP", "Variáveis lidas do JSON -> ID: $stationId | Lat: $lat | Lon: $lon")

                            // Voltar para a Thread Principal para mexer na interface/mapa
                            withContext(Dispatchers.Main) {
                                val newLocation = LatLong(lat, lon)

                                if (stationId == 144) {
                                    // SOU EU (144) -> Focar a câmara no novo sítio
                                    Log.d("AdasMapUDP", "Centrando e atualizando o nosso carro (144)")
                                    mapView.model.mapViewPosition.center = newLocation
                                    locationMarker.latLong = newLocation
                                    
                                    debugText.text = "SUCESSO!\nO Nosso Carro (144):\nLat: $lat\nLon: $lon"
                                } else {
                                    // SÃO OUTROS CARROS -> Atualiza posição sem centralizar o ecrã
                                    if (otherVehicles.containsKey(stationId)) {
                                        // O carro já estava registado, apenas vamos movê-lo de sítio
                                        otherVehicles[stationId]?.latLong = newLocation
                                    } else {
                                        // Novo vizinho! Vamos apadrinhar com uma cor nova (Vermelho) e registar
                                        Log.d("AdasMapUDP", "Novo carro desenhado! ID $stationId")
                                        val otherBitmap = createDotBitmap("#FF0000") // Vermelho
                                        val newMarker = org.mapsforge.map.layer.overlay.Marker(
                                            newLocation, 
                                            otherBitmap, 
                                            0, 0
                                        )
                                        mapView.layerManager.layers.add(newMarker)
                                        otherVehicles[stationId] = newMarker
                                    }
                                    
                                    debugText.text = "PONTO EXTERNO RECEBIDO!\nCarro $stationId\nLat: $lat | Lon: $lon"
                                }
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