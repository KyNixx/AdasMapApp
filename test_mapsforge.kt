import org.mapsforge.core.graphics.Color
import org.mapsforge.core.graphics.Style
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

fun test() {
   val g = AndroidGraphicFactory.INSTANCE
   val p = g.createPaint()
   p.color = org.mapsforge.core.graphics.Color.BLUE // Wait, is it int or MapsforgeColor?
}
