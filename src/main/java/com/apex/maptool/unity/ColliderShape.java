package com.apex.maptool.unity;

/**
 * Collider đã resolve về world (unity units).
 * EDGE: polyline (đường đi/mặt đất). BOX: 4 góc CCW (tường/trigger).
 * isTrigger: true = vùng trigger (vd cổng EnterJoinmap), false = va chạm vật lý (mặt đất).
 */
public final class ColliderShape {
    public enum Kind { EDGE, BOX }

    public final Kind kind;
    public final double[][] pts;   // world points [n][2]; EDGE: polyline; BOX: 4 corners
    public final boolean isTrigger;
    public final String name;

    public ColliderShape(Kind kind, double[][] pts, boolean isTrigger, String name) {
        this.kind = kind;
        this.pts = pts;
        this.isTrigger = isTrigger;
        this.name = name;
    }
}
