# Nhật ký phiên làm việc 15/07/2026 — Shop editor + giao diện Dark/Amber

Tổng kết toàn bộ thay đổi trong phiên (chưa commit — xem mục cuối).

## 1. Shop editor đọc bảng `shop` mới (server gộp 1 bảng)
- Server gộp `shop_type_config` + `shop_item_config` → 1 bảng **`shop`** (mỗi dòng = 1 tab, cột `items` = JSON mảng item theo entity `ShopItemJson`: `id, infoId, shopSlot, clazz, limitType, limit, name, prices[{key,value}]`).
- `ShopDao` viết lại: đọc/ghi thẳng bảng `shop` (read-modify-write JSON), **tự sinh id item = max toàn shop + 1**, giữ nguyên public API nên UI không đổi. Backup `backup/shop_{ts}.bak.json` + bump `version_tracker` trước/sau mỗi ghi.
- Đã commit trước đó: `997ca9c`.

## 2. Fix lag toàn tool (đã commit? CHƯA — nằm trong đợt chưa commit)
- **Nguyên nhân đo được**: mở 1 connection MySQL remote = **1.5–6.8s (tb 3.46s)**, query chỉ 3–25ms. Tool cũ mở connection MỚI mỗi thao tác + chạy trên EDT.
- Fix: `Db.java` dùng **HikariCP** (pool singleton, minIdle 2, keepalive 120s) → **3464ms → 3ms**; warmup pool ở luồng nền lúc khởi động; `ShopEditorFrame.loadAll()` + `EquipEditorFrame.load()` chạy SwingWorker (off-EDT); tối ưu combo item (precompute search key bỏ dấu + setModel 1 lần).
- pom: thêm `com.zaxxer:HikariCP:5.1.0` + `org.slf4j:slf4j-nop:1.7.36`.

## 3. Option (chỉ số) của item trong shop
- **Phát hiện chính**: option của item = buff trong **`equip_info.info_buff`** theo `infoId` (format `type-value` / `type-value-bonus`, `;` ngăn). Tên option từ `game/enums/ItemAttribute.java` server (84 tên) — parse bằng `AttrNames`.
- Bảng item có cột **Option** (đếm chỉ số) + nút **⚙ Option** / double-click → dialog xem/sửa/thêm/xóa, ghi `equip_info` (upsert — item CHƯA có row equip_info thì tạo mới: `EquipDao.upsertOptions`, đã test rollback).
- **Picker option** có ô nhập **Giá trị + Bonus±** và **preview Kết quả** ngay trong dialog chọn (double-click dòng option = sửa prefill).
- Fix kèm: `config.properties` thiếu `server.repo` (thêm, trỏ `D:/Dự án Ước Rồng/UocRongOnline-Server`); `ToolConfig` đọc properties **UTF-8** (trước là ISO-8859-1 làm hỏng path tiếng Việt).

## 4. Nút "➕ Thêm item mới" (panel đầy đủ)
- Dialog 2 cột (form trái + bảng option phải, non-modal): Tab shop · Tìm/chọn item · **Loại bán 2 kiểu**: Tiền tệ (Vàng/Ngọc/Hồng ngọc) hoặc **Vật phẩm** (item id làm giá — shop đổi đồ) · Giá/SL · Slot · Giới hạn · Hành tinh · Option (không bắt buộc).

## 5. Giao diện Dark + Amber (theo handoff `Tool giao diện màu đen/design_handoff_uocrong_tools`)
- `Theme.java`: toàn bộ design token (nền `#0d0d10`, accent `#f0b429`...), factory nút `primary` (vàng) / `tint` (green/red/blue/purple) / `ghost`; FlatLaf dark tùy biến (table zebra, tab underline vàng, input tối...).
- Font **Be Vietnam Pro** bundle trong jar (`src/main/resources/fonts/`, 5 weights, tải từ google/fonts) — load ở `Theme.loadFonts()`.
- `MainFrame`: sidebar kiểu handoff (brand ƯỚC RỒNG **TOOLS**, 4 nút hệ thống, nav DATABASE/EDITORS có active state vàng, footer chấm xanh + host DB), top bar 52px, **MySQL Config dialog** (test kết nối + lưu `config.properties`).
- Cỡ chữ đã cân chỉnh theo yêu cầu (base 13px, input 34px, row bảng 33px — lần đầu làm to quá, đã giảm ~2 nấc).
- Cột Giá màu: Ngọc = cyan `#7dd3fc`, Vàng = amber; cột Option tím; tên item đậm.

## 6. Kiến trúc vỏ app: CardLayout (fix biến dạng)
- **Bug**: JDesktopPane MDI chỉ cho 1 internal frame maximized → mở tool thứ 2 làm tool cũ un-maximize + mất title bar → biến dạng.
- **Fix**: bỏ MDI hoàn toàn → **CardLayout** (`MainFrame.stack`), mỗi tool 1 card full-khung, `showTool()` phải `revalidate()+repaint()` (thiếu là card mới bị layout stale → cắt nội dung). Map editor thêm `startEmbedded()` trả JComponent.
- Form trái Shop bọc `vScroll()` (cửa sổ thấp thì cuộn, không cắt nút). ⚠ Lưu ý kỹ thuật: panel trong viewport KHÔNG được set `preferredSize(w, 0)` (viewport sẽ cho cao 0) — width đặt trên scrollpane.
- Tự mở Shop khi khởi động. Phóng to/Thu nhỏ điều khiển cửa sổ chính.

## 7. Icon item + icon tiền tệ
- Folder **`IconItem/`** (repo root): 1043 ảnh `<itemId>.png` 64-70px (đã xóa 1043 file `.meta` Unity).
- `ItemIcons`: cache decode+scale 26px, nhớ cả id thiếu ảnh; tìm folder ở CWD/cạnh jar.
- Icon hiện ở: bảng item (trước tên) · combo Item · combo "Item bán" · picker "Chọn vật phẩm làm giá" · **cột Giá** (icon loại tiền sau số — key giá là item id) · combo Loại tiền/Đơn vị giá.

## 8. Dialog phóng to
- Option dialog: 1000×680 (tự co theo màn hình); Thêm item mới: 1120×660.

## Cách chạy
- Build: `mvn clean package -DskipTests` (JDK 21 — pom đã hạ release 22→21, run.bat nhận JDK ≥21).
- Chạy: `run.bat` (vỏ đầy đủ) hoặc `run.bat shop` / `java -jar target/map-editor.jar shop` (mở thẳng Shop).
- Sau khi sửa data: **RESTART game server** (ShopManager chỉ load lúc boot).

## Việc còn treo
- [ ] **COMMIT toàn bộ đợt thay đổi này** (user yêu cầu KHÔNG kèm `Co-Authored-By: Claude` để GitHub Desktop không hiện logo). Đang chờ user chốt: folder `IconItem/` (1043 ảnh) đưa vào git hay `.gitignore`.
- [ ] Lag còn lại ngoài DB (map canvas render, icon decode EDT ở ObjectPicker/MarkerList, debounce filter) — xem memory `tool-remaining-lag-followups`.
- [ ] Test ghi thật insert/update/delete item + lưu option trên DB (mới test đọc + upsert rollback).
