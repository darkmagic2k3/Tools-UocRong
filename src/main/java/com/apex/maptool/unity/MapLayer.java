package com.apex.maptool.unity;

import java.nio.file.Path;

/**
 * 1 layer map đã resolve: texture + vị trí/size world (unity units) + sort order.
 * cx,cy = tâm layer (unity units). w,h = kích thước (unity units).
 */
public final class MapLayer {
    public final Path texture;
    public final double cx, cy;   // center, unity units
    public final double w, h;     // size, unity units
    public final double angleDeg; // world rotation Z (degrees, CCW)
    public final long sortKey;    // draw ascending (nhỏ = vẽ trước = dưới)
    public final boolean flipX, flipY;
    public final String debugName;

    public MapLayer(Path texture, double cx, double cy, double w, double h, double angleDeg,
                    long sortKey, boolean flipX, boolean flipY, String debugName) {
        this.texture = texture;
        this.cx = cx; this.cy = cy;
        this.w = w; this.h = h;
        this.angleDeg = angleDeg;
        this.sortKey = sortKey;
        this.flipX = flipX; this.flipY = flipY;
        this.debugName = debugName;
    }
}
