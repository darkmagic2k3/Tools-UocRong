# Waypoint Editor — UocRongOnline-Tools

Tài liệu track việc thêm chức năng quản lý waypoint (cổng `gate_way`) cho map editor.

## Bối cảnh

Tool đã có marker `GATEWAY` cơ bản (đặt bằng click, kéo, xóa, lưu DB/file). Việc này nâng cấp thành **Waypoint Editor** đầy đủ: cửa sổ riêng để liệt kê / thêm / sửa / xóa cổng, có set điểm rớt ở map đích.

UI tham khảo: MapEditor của NRS (`C:\LuuTruUnity\NgocRongSao.Com\Tools`) — chỉ học UX, không copy.

## Mô hình dữ liệu cổng (đã phân tích từ server)

`GateWay` (server `GateWay.java`) + `type` (client `GateWayInfo`). Tool lưu JSON key:

| Field | Nghĩa | Ai dùng |
|-------|-------|---------|
| `mapId` | map ĐÍCH cổng dẫn tới | client + server |
| `aX`, `aY` | vị trí CỔNG trên map đang đứng (chỗ cổng hiện ra). `getAx()` server random ±200 | client vẽ cổng; server first-spawn |
| `bX`, `bY` | vị trí player RỚT ở map đích sau khi qua cổng (= `goX/goY` của NRS) | server `setCurrentPostionJoinMap` |
| `type` | 0=click / 1=chạm tự động | CHỈ client (`EdgeOutSide`) |

Bằng chứng: `MapManager.setChangeMapPosition` (server) dùng `gateWay.getBx()/getBy()` làm spawn map đích. `getGateWayByCurrentMap` lấy cổng map hiện tại → map đích; không có thì fallback `arrive_position` map đích.

⚠️ **bX/bY mặc định 0/0 → player rớt tại (0,0) map đích** → phải cho người dùng set.

⚠️ **Key JSON**: client đọc `ax/ay` (case-insensitive), server đọc `aX/aY` (case-sensitive). Tool ghi `aX/aY/bX/bY` → đúng cả 2. Verify DB production dùng `aX/aY` (không phải `cx/cy` như seed) trước khi ghi đè.

## Quyết định thiết kế (đã chốt với user)

1. **Mô hình**: giữ điểm (`aX/aY` + `bX/bY` + `mapId` + `type`). Không đổi sang vùng rect như NRS.
2. **UI**: cửa sổ riêng (JFrame) như NRS — bảng list cổng + form sửa.
3. **Set bX/bY**: mở map đích lên canvas → click chọn điểm rớt → quay lại map gốc.

## Phạm vi

- [x] Phân tích mô hình data (aX/aY/bX/bY/type)
- [x] `MapCanvasPanel`: pick-mode (click 1 điểm trả toạ độ + banner + Esc hủy), callback selection-change, `selectMarker`/`panToMarker`, nhãn tên map đích, bỏ vẽ điểm B sai ngữ nghĩa, cổng đặt 1-lần
- [x] `WaypointEditorFrame` (mới): bảng cổng + form (dropdown map đích, dropdown type, ô aX/aY/bX/bY), nút Thêm/Xóa/Chọn-điểm-rớt, cảnh báo bX/bY=0
- [x] `MapToolApp`: nút mở cửa sổ, luồng "chọn điểm rớt map đích" (lưu/khôi phục map gốc), truyền danh sách map (id→name)
- [x] Đồng bộ 2 chiều list ↔ canvas (click dòng↔click cổng, kéo cổng cập nhật live)
- [x] Vẽ cổng bằng **vùng phạm vi hoạt động** (box ±200 server quanh aX) thay chấm tròn; checkbox toolbar "Vùng cổng" bật/tắt
- [x] Build verify: `mvn -o compile` + `package` EXIT=0, ra `target/map-editor.jar`
- [ ] **Chạy GUI thực tế** (cần display + DB + assets) — CHƯA test runtime, mới verify compile/package

## Hiển thị cổng = vùng collider Gate_Prefab (1:1 client)

Tool parse `Gate_Prefab.prefab` 1 lần lúc start → lấy **collider thật** rồi vẽ tại `(aX/ppu, aY/ppu)` mỗi cổng (giống `MapManager.UpdateGateWarp` của client):

| Vùng | Collider | Kích thước | Tâm (so với gốc aX/aY) |
|------|----------|------------|------------------------|
| Click | `Gate_Prefab` (root) | 4×1 | (0, 0) — tại aX/aY |
| Chạm | `EnterJoinmap` | 4×4 | (0, **−3**) — dưới gốc 3u |

- Vùng chạm nằm **dưới gốc 3u** → gate `aY` cao (4.5–6.4u) nhưng vùng chạm phủ xuống tới ~ground → player ở đất kích hoạt được. Đây là logic client thật, không phải bug.
- Click vùng = cam nhạt, chạm vùng = cam đậm. Chấm cam tâm = gốc `aX/aY`. Nhãn tên map đích. Chọn → viền vàng.
- Tắt bằng checkbox toolbar "Vùng cổng".
- Dump geometry: `gatedump` (headless).

### 1:1 đầy đủ (đã port logic runtime client)
- **duration x-offset** (`FindNearestGateIndexes` + `InitGateWay`): cổng gần mép trái→vùng chạm dịch −N, gần mép phải→+N, còn lại 0. Tính bằng tường biên Left/Right của map (`leftRightWall`). Bỏ qua cổng type==0 (đúng client).
  - **N = 4.5**. Client: `EdgeOutSide.InitGateWay` `float _x` switch −4.5f/+4.5f/0f. Tool: `GATE_DURATION_OFFSET=4.5` (double, `MapCanvasPanel`). **2 nơi phải khớp nhau.** Client cần build lại qua Unity để game áp dụng.
- **SetGateLine**: 2+ cổng cùng `mapId` → vùng chạm cổng sau mở rộng `w=|length|+10` nối về vùng chạm cổng đầu (`offset.x += length/2`). Replicate đúng `UpdateGateWarp` (`_edgeOutside[idMaps.IndexOf]`).
- Verify: map 15 `[14,14,16]` → g0 Left(−2), g1 Right(+2)+SetGateLine nối về g0, g2 Center. Map 16 `[15,15]` tương tự.
- Code: `MapCanvasPanel.computeGatewayTouch()`.

### Lưu ý: gate `aY` trong DB cao hơn ground
Map 1 (Làng Aru): ground y=60 (0.6u) nhưng gate→Nhà Gohan `aY=640`, →Vách núi Aru `aY=455`, →Đồi hoa cúc `aY=536`. Gate gốc cao là cố ý (khớp tên "Nhà Gohan" nổi cao trên capsule). `dbtest <mapId>` in TẤT CẢ marker để soi.

## Cách dùng

1. Mở tool, Load map cần sửa.
2. Bấm **🚪 Waypoint Editor** (panel phải) → cửa sổ riêng.
3. **Thêm cổng**: nút "➕ Thêm cổng (click map)" → chọn map đích ở dropdown trước → click lên canvas đặt cổng (đặt 1 lần).
4. **Sửa**: chọn cổng (click trên canvas hoặc dòng bảng) → form bên phải: đổi map đích (dropdown), type (click/chạm), nhập aX/aY hoặc kéo cổng trên canvas.
5. **Điểm rớt map đích (bX/bY)**: bấm "📍 Chọn điểm rớt ở map đích" → canvas mở map đích **+ hiện sẵn các cổng của map đích** (xem bối cảnh, không sửa được khi đang pick) → click chọn chỗ player rớt → tự quay lại map gốc. (Esc để hủy.)
6. **Xóa**: nút "🗑 Xóa cổng" hoặc phím Del.
7. Lưu: "💾 Lưu file (test)" hoặc "★ LƯU DB (chốt)" ở panel chính (có auto-backup).

## Build

```
JAVA_HOME = C:\Program Files\Java\jdk-22
mvn (bundled) = C:\Program Files\JetBrains\IntelliJ IDEA 2025.1.3\plugins\maven\lib\maven3\bin\mvn
mvn -o compile   # offline compile
```

## File then chốt

- `src/main/java/com/apex/maptool/model/Marker.java` — model cổng (`GATEWAY`)
- `src/main/java/com/apex/maptool/ui/MapCanvasPanel.java` — canvas render + chuột
- `src/main/java/com/apex/maptool/MapToolApp.java` — khung UI
- `src/main/java/com/apex/maptool/db/InfoDao.java` — `maps()` cho dropdown map đích
- `src/main/java/com/apex/maptool/db/MapInfoDao.java` / `LocalStore.java` — lưu/đọc (đã hỗ trợ GATEWAY)
