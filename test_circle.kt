import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.core.model.LatLong

class CircleTest {
    fun test(c: Circle, l: LatLong) {
        c.setLatLong(l)
    }
}
