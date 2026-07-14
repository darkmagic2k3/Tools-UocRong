package com.apex.maptool.model;

/**
 * Điểm rơi VÀO map đang mở = bX/bY của 1 cổng ở map KHÁC (map nguồn) target vào map này.
 * Kéo trên map đích → sửa bX/bY của cổng đó → lưu về DB của map nguồn.
 */
public final class IncomingDrop {
    public final int srcMapId;   // map nguồn (chứa cổng)
    public final int gateIndex;  // vị trí cổng trong list_gate_way của map nguồn
    public int serverX, serverY; // bX/bY (server coord) — đổi khi kéo
    public boolean dirty;        // đã sửa, chờ lưu

    public IncomingDrop(int srcMapId, int gateIndex, int serverX, int serverY) {
        this.srcMapId = srcMapId;
        this.gateIndex = gateIndex;
        this.serverX = serverX;
        this.serverY = serverY;
    }
}
