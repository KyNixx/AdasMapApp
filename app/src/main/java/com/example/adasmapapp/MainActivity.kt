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
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.graphics.Color as MapsforgeColor
import org.mapsforge.map.layer.overlay.Polygon
import org.mapsforge.map.layer.overlay.Polyline
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() { // <-- Alterado aqui

    private lateinit var mapView: MapView
    private lateinit var debugText: TextView
    private lateinit var alarmText: TextView // NOVO: Texto de Alarme UI
    
    // Mantemos o Polygon (em metros reais) invisivel para features futuras e o Marker para interface
    private lateinit var locationPolygon: Polygon
    private lateinit var locationMarker: org.mapsforge.map.layer.overlay.Marker
    private var locationLine: Polyline? = null // Linha amarela de previsão
    
    // Dicionário para guardar as instâncias de outros veículos (chave: stationID)
    inner class VehicleData(var polygon: Polygon, var marker: org.mapsforge.map.layer.overlay.Marker, var line: Polyline?)
    private val otherVehicles = mutableMapOf<Int, VehicleData>()

    // Função auxiliar para desenhar o Marker visual (tamanho fixo em pixeis)
    private fun createDotBitmap(colorHex: String): org.mapsforge.core.graphics.Bitmap {
        val dotSize = 40
        val bitmap = android.graphics.Bitmap.createBitmap(dotSize, dotSize, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor(colorHex)
            isAntiAlias = true
        }
        canvas.drawCircle(dotSize / 2f, dotSize / 2f, dotSize / 2f, paint)
        
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

    // Função auxiliar para desenhar um polígono geográfico (retângulo do carro)
    private fun createVehiclePolygon(center: LatLong, lengthMs: Double, widthMs: Double, headingDeg: Double, colorEnum: MapsforgeColor): Polygon {
        val graphicFactory = AndroidGraphicFactory.INSTANCE
        
        val paintFill = graphicFactory.createPaint()
        try {
            paintFill.setColor(graphicFactory.createColor(colorEnum))
        } catch (e: NoSuchMethodError) {}
        paintFill.setStyle(Style.FILL)
        
        val paintStroke = graphicFactory.createPaint()
        // Se a cor pedida for TRANSPARENT, a borda também fica transparente para ficar 100% invisível
        val strokeColor = if (colorEnum == MapsforgeColor.TRANSPARENT) MapsforgeColor.TRANSPARENT else MapsforgeColor.WHITE
        paintStroke.setColor(graphicFactory.createColor(strokeColor))
        paintStroke.setStyle(Style.STROKE)
        paintStroke.setStrokeWidth(4f)

        val polygon = Polygon(paintFill, paintStroke, graphicFactory)
        
        val rEarth = 6378137.0
        val centerLat = Math.toRadians(center.latitude)
        val centerLon = Math.toRadians(center.longitude)
        val angle = Math.toRadians(headingDeg)
        
        // Metros a partir do centro (x = direita/esquerda, y = frente/trás)
        val dx = widthMs / 2.0
        val dy = lengthMs / 2.0
        
        val corners = listOf(
            Pair(-dx, dy), // Frente-Esquerda
            Pair(dx, dy),  // Frente-Direita
            Pair(dx, -dy), // Trás-Direita
            Pair(-dx, -dy) // Trás-Esquerda
        )
        
        for (corner in corners) {
            val cx = corner.first
            val cy = corner.second
            
            // Rotação seguindo o heading (ângulo começa no Norte 0, e avança no sentido dos ponteiros do relógio)
            val rx = cx * cos(angle) + cy * sin(angle)
            val ry = -cx * sin(angle) + cy * cos(angle)
            
            val dLat = ry / rEarth
            val dLon = rx / (rEarth * cos(centerLat)) // ajustado pela latitude
            
            polygon.latLongs.add(LatLong(Math.toDegrees(centerLat + dLat), Math.toDegrees(centerLon + dLon)))
        }
        
        return polygon
    }

    // Função que prevê onde o carro vai estar em X segundos projetando uma Polyline Amarela Reta a partir do heading e speed.
    private fun createPredictionLine(start: LatLong, speedMs: Double, headingDeg: Double): Polyline? {
        // Segundo os testes do formato CAM ITS, heading é impossivel se for 3601 / speed invalido > 163
        if (headingDeg >= 360.0 || speedMs < 0.1 || speedMs >= 163.0) return null 
        
        val forecastSeconds = 4.0
        val distanceMeters = speedMs * forecastSeconds

        val rEarth = 6378137.0 // Raio da Terra em Metros
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val brng = Math.toRadians(headingDeg)
        val angularDist = distanceMeters / rEarth

        // Fórmulas Geográficas esféricas
        val lat2 = asin(sin(lat1) * cos(angularDist) + cos(lat1) * sin(angularDist) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(angularDist) * cos(lat1), cos(angularDist) - sin(lat1) * sin(lat2))
        
        val end = LatLong(Math.toDegrees(lat2), Math.toDegrees(lon2))

        val graphicFactory = AndroidGraphicFactory.INSTANCE
        val paintStroke = graphicFactory.createPaint()
        // MapsforgeColor pode não ter YELLOW, usamos Android Color
        try {
            paintStroke.setColor(android.graphics.Color.YELLOW)
        } catch (e: Exception) {
            paintStroke.setColor(graphicFactory.createColor(MapsforgeColor.RED)) // Falha segura
        }
        paintStroke.setStyle(Style.STROKE)
        paintStroke.setStrokeWidth(6f) // Linha visível! Pode configurar para 8f ou 10f se quiser mais grossa
        
        val polyline = Polyline(paintStroke, graphicFactory)
        polyline.latLongs.add(start)
        polyline.latLongs.add(end)
        
        return polyline
    }

    // --- FUNÇÕES DE DETEÇÃO DE COLISÃO ---
    private fun checkCollisions(): Boolean {
        if (locationLine == null || locationLine!!.latLongs.size < 2) return false
        val myStart = locationLine!!.latLongs[0]
        val myEnd = locationLine!!.latLongs[1]

        for ((id, data) in otherVehicles) {
            val theirStart = data.marker.latLong
            val theirLine = data.line
            val theirPolygonPoints = data.polygon.latLongs

            // 1. A nossa linha cruza-se com o retângulo de bounding do vizinho?
            if (theirPolygonPoints.size >= 4) {
                // Testar intersecção com cada segmento do polígono (retângulo)
                for (i in 0 until 4) {
                    val p3 = theirPolygonPoints[i]
                    val p4 = theirPolygonPoints[(i + 1) % 4]
                    if (segmentsIntersect(myStart, myEnd, p3, p4)) {
                        return true
                    }
                }
            }

            // 2. A nossa linha cruza-se com a linha de predição deles?
            if (theirLine != null && theirLine.latLongs.size >= 2) {
                val theirEnd = theirLine.latLongs[1]
                if (segmentsIntersect(myStart, myEnd, theirStart, theirEnd)) {
                    return true
                }
            }
        }
        return false
    }

    private fun segmentsIntersect(p1: LatLong, p2: LatLong, p3: LatLong, p4: LatLong): Boolean {
        // Função matemática simples para saber se as retas P1-P2 e P3-P4 se intersetam 
        // Usamos counter-clockwise check para testar os eixos cartesianos topológicos puros
        fun ccw(a: LatLong, b: LatLong, c: LatLong): Boolean {
            return (c.latitude - a.latitude) * (b.longitude - a.longitude) > (b.latitude - a.latitude) * (c.longitude - a.longitude)
        }
        return (ccw(p1, p3, p4) != ccw(p2, p3, p4)) && (ccw(p1, p2, p3) != ccw(p1, p2, p4))
    }

    private fun distancePointToSegment(p: LatLong, a: LatLong, b: LatLong): Double {
        // Aproximação esférica rápida -> Converter Lat/Lon em cartesianos 2D na área local X/Y em Metros (relativos a A)
        val rEarth = 6371000.0
        val latA = Math.toRadians(a.latitude)
        
        fun toX(lon: Double) = rEarth * Math.toRadians(lon - a.longitude) * Math.cos(latA)
        fun toY(lat: Double) = rEarth * Math.toRadians(lat - a.latitude)

        val px = toX(p.longitude)
        val py = toY(p.latitude)
        val bx = toX(b.longitude)
        val by = toY(b.latitude)

        // Distância de P = (px, py) até segmento A=(0,0) - B=(bx, by)
        val lengthSquared = bx * bx + by * by
        if (lengthSquared == 0.0) return Math.sqrt(px * px + py * py)

        // Projeção matemática normalizada [0.0 > t > 1.0] garantida não falhar nos extremos da linha
        var t = (px * bx + py * by) / lengthSquared
        t = Math.max(0.0, java.lang.Math.min(1.0, t))

        val projX = t * bx
        val projY = t * by

        val dx = px - projX
        val dy = py - projY
        return Math.sqrt(dx * dx + dy * dy)
    }
    // -------------------------------------

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

        // ======= ALARME DE COLISÃO =========
        alarmText = TextView(this).apply {
            text = "⚠ PERIGO DE COLISÃO! ⚠"
            setBackgroundColor(Color.RED)
            setTextColor(Color.WHITE)
            setPadding(32, 32, 32, 32)
            textSize = 22f
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE // Escondido por defeito
            elevation = 10f // Manter na frente do mapa
        }
        val alarmParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP // Fica colado no topo
        }
        frameLayout.addView(alarmText, alarmParams)
        // ===================================

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

                // ======== CRIAR AS 2 CAMADAS DO NOSSO VEÍCULO (144) ========
                val initialLoc = LatLong(38.756862, -9.192776)
                
                // 1) Polígono geográfico transparente/invisível (Retângulo a simular o carro)
                locationPolygon = createVehiclePolygon(initialLoc, 4.0, 2.0, 0.0, MapsforgeColor.TRANSPARENT)
                mapView.layerManager.layers.add(locationPolygon)
                
                // 2) Marker visual (Bolinha que vemos)
                val markerBitmap = createDotBitmap("#0000FF") // Azul
                locationMarker = org.mapsforge.map.layer.overlay.Marker(initialLoc, markerBitmap, 0, 0)
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
                            
                            val speedMs = if (json.has("speed")) json.getDouble("speed") else 0.0
                            val headingDeg = if (json.has("heading")) json.getDouble("heading") else 3601.0
                            val length = if (json.has("length")) json.getDouble("length") else 4.0
                            val width = if (json.has("width")) json.getDouble("width") else 2.0

                            Log.d("AdasMapUDP", "Variáveis lidas do JSON -> ID: $stationId | Lat: $lat | Lon: $lon")

                            // Voltar para a Thread Principal para mexer na interface/mapa
                            withContext(Dispatchers.Main) {
                                val newLocation = LatLong(lat, lon)

                                if (stationId == 144) {
                                    // SOU EU (144) -> Focar a câmara no novo sítio
                                    Log.d("AdasMapUDP", "Centrando e atualizando o nosso carro (144)")
                                    mapView.model.mapViewPosition.center = newLocation
                                    
                                    // 1) Atualiza marcador visual (permite setLatLong on-the-fly)
                                    locationMarker.latLong = newLocation
                                    
                                    // 2) Atualiza o Polígono transparente recriando a camada
                                    mapView.layerManager.layers.remove(locationPolygon)
                                    locationPolygon = createVehiclePolygon(newLocation, length, width, headingDeg, MapsforgeColor.TRANSPARENT)
                                    mapView.layerManager.layers.add(locationPolygon)
                                    
                                    // 3) Atualiza a linha de predição Amarela
                                    if (locationLine != null) mapView.layerManager.layers.remove(locationLine)
                                    locationLine = createPredictionLine(newLocation, speedMs, headingDeg)
                                    if (locationLine != null) mapView.layerManager.layers.add(locationLine)
                                    
                                    debugText.text = "SUCESSO!\nO Nosso Carro (144):\nLat: $lat\nLon: $lon\nVel: $speedMs m/s"
                                } else {
                                    // SÃO OUTROS CARROS -> Atualiza posição sem centralizar o ecrã
                                    if (otherVehicles.containsKey(stationId)) {
                                        val oldData = otherVehicles[stationId]!!
                                        
                                        // 1) Atualiza marker visual
                                        oldData.marker.latLong = newLocation
                                        
                                        // 2) Atualiza polígono invisível por baixo
                                        mapView.layerManager.layers.remove(oldData.polygon)
                                        val newPolygon = createVehiclePolygon(newLocation, length, width, headingDeg, MapsforgeColor.TRANSPARENT)
                                        mapView.layerManager.layers.add(newPolygon)
                                        oldData.polygon = newPolygon
                                        
                                        // 3) Atualiza a linha do vizinho
                                        if (oldData.line != null) mapView.layerManager.layers.remove(oldData.line)
                                        val newLine = createPredictionLine(newLocation, speedMs, headingDeg)
                                        if (newLine != null) mapView.layerManager.layers.add(newLine)
                                        oldData.line = newLine
                                        
                                    } else {
                                        // Novo vizinho!
                                        Log.d("AdasMapUDP", "Novo carro desenhado! ID $stationId")
                                        
                                        // Cria bolinha vermelha estática
                                        val otherBitmap = createDotBitmap("#FF0000") // Vermelho
                                        val newMarker = org.mapsforge.map.layer.overlay.Marker(newLocation, otherBitmap, 0, 0)
                                        
                                        // Cria polígono invisivel por baixo
                                        val newPolygon = createVehiclePolygon(newLocation, length, width, headingDeg, MapsforgeColor.TRANSPARENT)
                                        
                                        // Cria Linha Amarela nova
                                        val newLine = createPredictionLine(newLocation, speedMs, headingDeg)
                                        
                                        // Adiciona à UI
                                        if (newLine != null) mapView.layerManager.layers.add(newLine)
                                        mapView.layerManager.layers.add(newPolygon)
                                        mapView.layerManager.layers.add(newMarker) // Marker por cima da linha
                                        
                                        otherVehicles[stationId] = VehicleData(newPolygon, newMarker, newLine)
                                    }
                                    
                                    debugText.text = "PONTO RECEBIDO!\nCarro $stationId\nVelocidade: $speedMs"
                                }
                                
                                // ======== VERIFICAR COLISÕES A CADA ATUALIZAÇÃO ========
                                if (checkCollisions()) {
                                    alarmText.visibility = android.view.View.VISIBLE
                                } else {
                                    alarmText.visibility = android.view.View.GONE
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