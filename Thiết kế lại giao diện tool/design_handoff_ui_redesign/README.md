# Handoff: Ước Rồng Tools — bố cục lại Player Viewer (Hào Quang) & Bố cục Map

## 1. Tóm tắt

Thiết kế lại bố cục 2 màn nặng nhất của tool desktop `UocRongOnline Tools` (Java Swing + FlatLaf):

- **Player Viewer → tab Hào Quang**: rail phải 5+ khối control rời rạc → **4 bước có số**, khối đã xong gập lại; điều khiển xem thử dời lên thanh khung xem.
- **Bố cục Map**: 3 hàng công cụ (~34 control) → **1 hàng + 3 menu bật xuống**; thêm panel Thuộc tính bên phải; thanh dưới gom số liệu thành chip.

Mục tiêu đã chốt với người dùng: bố cục rõ ràng, dễ dùng, phân bổ hợp lý; **giữ nguyên 100% token màu/typography trong `Theme.java`**; không bỏ chức năng nào.

## 2. Về các file thiết kế trong gói này

`Uoc Rong Tools - Redesign.dc.html` là **bản tham chiếu thiết kế viết bằng HTML** — không phải code để copy vào sản phẩm. Nhiệm vụ là **dựng lại đúng bố cục đó trong codebase Swing hiện có** (`com.apex.maptool.ui.*`), dùng đúng các thành phần và helper đang có (`Theme.primary/ghost/tint/spin/check`, `FlatLaf`, `GridBagLayout`, `BoxLayout`).

Mở file HTML trong trình duyệt để xem. File có 2 khung 1920×1080 (Màn 1 = Player Viewer, Màn 2 = Bố cục Map) kèm chú thích số 1–7 dưới mỗi khung giải thích lý do từng thay đổi.

## 3. Độ hoàn thiện

**High-fidelity.** Màu, cỡ chữ, chiều cao control, khoảng cách, bán kính bo góc trong bản HTML là giá trị cuối. Dựng lại đúng pixel ở mức Swing cho phép (chênh ±1–2px do FlatLaf border là chấp nhận được).

## 4. Design tokens

### 4.1 Màu — dùng nguyên hằng số đã có trong `com.apex.maptool.ui.Theme`

| Token | Hex | Dùng ở đâu trong bản mới |
|---|---|---|
| `BG_APP` | `#060608` | nền cửa sổ |
| `BG_MAIN` | `#0d0d10` | nền vùng nội dung, nền card con trong panel (step card) |
| `BG_PANEL` | `#0a0a0d` | sidebar, top bar, nền khung xem (canvas) |
| `BG_SURFACE` | `#121216` | nền panel: danh sách, inspector, thẻ nguồn, thanh công cụ, thanh dưới |
| `BG_SURFACE2` | `#17171c` | nút ghost, chip |
| `BG_INPUT` | `#1a1a20` | ô nhập, combo, spinner, track slider (`#2a2a33` cho rãnh) |
| `BG_ZEBRA` | `#15151a` | dòng cây đang mở (Layer_Ground) |
| `BORDER` | `#2a2a33` | viền input/nút/chip |
| `BORDER_SOFT` | `#26262e` | viền panel, viền step card |
| `DIVIDER` | `#1c1c22` | đường kẻ trong panel, border-bottom tab row |
| `TEXT` | `#f0f0f4` | tiêu đề, giá trị số |
| `TEXT_2` | `#c6c6d0` | nhãn control |
| `TEXT_MUTED` | `#8b8b98` | nhãn phụ, tab không chọn |
| `TEXT_DIM` | `#5c5c68` | section header, hint, đơn vị |
| `ACCENT` | `#f0b429` | nút chính, tab active, checkbox tick, badge bước |
| `ACCENT_HOVER` | `#ffc94d` | chữ trên nền accent mờ, tab active |
| `ACCENT_SEL` | `#2f2718` | dòng chọn trong list/cây, chip trạng thái |
| `ACCENT_NAV` | `#221e11` | nav active, badge số bước |
| `ON_ACCENT` | `#151004` | chữ trên nút vàng |
| `GREEN` | `#34d399` | trạng thái OK, nút Chạy |
| `RED` | `#f87171` | Đóng tool / Thoát app / Hoàn tác tất cả |
| `BLUE` | `#60a5fa` | Áp SCALE / Áp VỊ TRÍ |
| `PURPLE` | `#a78bfa` | dấu lớp SAU (aura) |

Nền màu mờ: nút tint = màu gốc alpha 26/255 (hover 51), viền alpha 90/255 (hover 140) — đúng như `Theme.painted(..., opaque=false)` đang làm.

### 4.2 Typography — `Theme.font(size, style)`, Be Vietnam Pro

| Vai trò | Cỡ | Style |
|---|---|---|
| Tiêu đề màn (top bar) | 16 | BOLD |
| Tiêu đề bước / tab | 14 | BOLD |
| Nhãn control, item nav, nhãn cây | 13 | PLAIN (nút: BOLD) |
| Chip, hint, giá trị phụ | 12 | PLAIN |
| Section header (chữ hoa, giãn cách) | 11 | BOLD |
| Brand "ƯỚC RỒNG TOOLS" | 19 | BOLD |

**Không dùng cỡ dưới 12.** Bản cũ có nhiều dòng 11px chạy dài — đã đổi thành chip 12px.
Giá trị số / đường dẫn / id dùng font monospace (Consolas) để cột số thẳng hàng.

### 4.3 Kích thước & khoảng cách

- Chiều cao control: nút chính 40, nút thường 36, nút trong panel 34, nút nhỏ / chip cao 30, chip 24–26, dòng cây 32–34, dòng nav 36.
- Bán kính: panel 10, nút/ô nhập 8, nút nhỏ/chip trong panel 7, chip tròn 999.
- Khoảng cách: giữa panel 12; padding panel 12; giữa control cùng hàng 8–10; giữa step card 6–8; padding vùng nội dung 16 trên / 20 hai bên / 18 dưới.
- Cột: sidebar 240 · panel danh sách 272 (Player Viewer) / 308 (Bố cục Map) · inspector 452 (Player Viewer) / 352 (Bố cục Map) · khung xem = phần còn lại.
- Top bar cao 56; thanh công cụ Bố cục Map cao 60; thanh dưới cao 44; tab row cao 46.

## 5. Vỏ app — `ui/MainFrame.java`

Thay đổi duy nhất ở sidebar (240px, `BG_PANEL`, viền phải `DIVIDER`):

1. **4 nút hệ thống to xếp dọc → 2 hàng gọn**: `Toàn màn hình` + `Khởi động lại` nằm cùng một hàng (`GridLayout(1,2,8,0)`, cao 34, ghost); `Đóng tool` + `Thoát app` (tint đỏ, cao 32) **đẩy xuống sát footer** — tách nút phá huỷ khỏi nút thường.
2. Nav giữ nguyên 3 section `DATABASE / EDITORS / SPINE`, dòng cao 36, chữ 13; item active: nền `ACCENT_NAV`, thanh 3px `ACCENT` sát lề trái, chữ `ACCENT_HOVER` (đã có trong `navItem()`).
3. Footer: chấm `GREEN` 7px + `v0.1.0 · <db host>` 12px monospace, có đường kẻ `DIVIDER` phía trên.
4. Top bar: tiêu đề 16 BOLD + `/` + tên ngữ cảnh (`Hào Quang`, `Map 1 — Làng Aru`) 13 `TEXT_MUTED`; hai nút cửa sổ thu còn ô vuông 32×32 (`⤢`, `–`) thay vì nút chữ 104px.

## 6. Màn 1 — Player Viewer / tab Hào Quang

File liên quan: `ui/PlayerViewerFrame.java` (khung + thẻ nguồn + danh sách + canvas), `ui/HaoQuangPanel.java` (rail phải).

### 6.1 Bố cục

```
top bar 56
└ vùng nội dung (padding 16/20/18)
  ├ thẻ nguồn Spine (cao ~96, BG_SURFACE, bo 10)      ← 1 hàng đường dẫn + 1 hàng chip trạng thái
  └ hàng chính (cao còn lại, 3 cột, gap 12)
    ├ 272  panel Duyệt nhanh
    ├ auto khung xem (BG_PANEL, lưới 48px, bo 10)
    └ 452  inspector (tab Animation | Slot | Bone | Hào Quang)
```

### 6.2 Thẻ nguồn Spine

- Hàng 1: nhãn `Thư mục Spine` (13, `TEXT_MUTED`) · ô đường dẫn (cao 36, monospace 13, giãn hết) · `Chọn…` ghost 36 · `Nạp` primary 36 (padding ngang 22).
- Hàng 2 (thay dòng chữ `OK · 1 — 27 slot · 115 bone…` 11px cũ): các **chip cao 26** bo tròn:
  `✓ Nạp OK · Player/1` (tint GREEN) · `27 slot` · `115 bone` · `45 animation` · `1 skin` · `SkeletonData scale 0.00687500` (monospace, `TEXT_MUTED`).
  Swing: `JPanel` + `FlowLayout(LEFT, 8, 0)`, mỗi chip là `JLabel` bo góc vẽ tay (dùng lại cách vẽ của `Theme.painted`).

### 6.3 Panel Duyệt nhanh (272px)

- Header: `DUYỆT NHANH` (11 BOLD giãn cách, `TEXT_MUTED`) + số kết quả bên phải (`48 mục`, 12 `TEXT_DIM`).
- Combo loại (`Player`) cao 34; ô tìm cao 34 với placeholder `Tìm theo tên hoặc id…`.
- **Danh sách id 1 cột → lưới ô**: `JList` với `setLayoutOrientation(HORIZONTAL_WRAP)`, `visibleRowCount = -1`, ô rộng tối thiểu 44, cao 30, bo 7, chữ monospace 13; ô đang chọn: nền `ACCENT_SEL`, viền `ACCENT`, chữ `ACCENT_HOVER`.
- Chia nhóm theo dải id bằng header dòng `0 – 99`, `100 – 599`, `6000+` (11 BOLD `TEXT_DIM`, có `DIVIDER` phía trên). Cách rẻ nhất trong Swing: mỗi dải là 1 `JList` riêng xếp dọc trong `JPanel` cuộn chung — hoặc 1 `JList` + renderer vẽ header ở phần tử đầu dải.
- Lợi ích: thấy ~40 id trong một tầm mắt (bản cũ ~25) và tìm được bằng bàn phím.

### 6.4 Khung xem + thanh công cụ khung xem (cao 50)

Một hàng duy nhất, trái → phải:
`☑ Cỡ thật như game` · vạch 1px · `Về giữa` (ghost 30) · `62%` (ô monospace 30) · vạch · `▶ Chạy` (tint GREEN 30) · `15 FPS` (spinner 30, monospace) · `☑ Lặp` · giãn · `Nằm SAU player` (chip `ACCENT_SEL`, bật/tắt).

> **Đây là thay đổi kiến trúc quan trọng**: nhóm *Xem thử* (Chạy / FPS / Lặp / Hào quang nằm SAU player) chuyển từ rail phải lên thanh khung xem — đặt điều khiển ngay nơi mắt đang nhìn, đồng thời giải phóng ~200px chiều cao cho rail. Trong code: các widget `btPlay`, `spFps`, `ckLoop`, `ckBehind` của `HaoQuangPanel` được `PlayerViewerFrame` mượn lên thanh canvas (giữ nguyên listener; chỉ đổi nơi `add`).

Trong khung xem:
- Lưới 48px `rgba(255,255,255,.035)`, trục dọc `rgba(96,165,250,.45)`, đường đất `rgba(52,211,153,.35)`.
- Góc dưới trái: 2 chip trạng thái lớp — `hiện lớp SAU · hqsau1` (viền tím) / `lớp TRƯỚC · tắt` (xám). Hai chip này **là công tắc ẩn/hiện lớp** (thay 2 checkbox `Ẩn lớp SAU` / `Ẩn lớp TRƯỚC`).
- Góc dưới phải: chip hint `lăn chuột = phóng · kéo = dời lớp đang chọn`.

### 6.5 Rail phải — inspector 452px

```
tab row 46      Animation | Slot | Bone | [Hào Quang]   (active: chữ ACCENT_HOVER + gạch 3px ACCENT)
hàng phạm vi 46 [Hào quang SM | Đặc biệt] segmented + chip "đang sửa: SM #1"
thân (cuộn)     4 step card, gap 6, padding 8
chân (ghim)     nút chính 40 + dòng đường dẫn 12
```

**Step card** = `BG_MAIN`, viền `BORDER_SOFT`, bo 10. Header cao 40: badge tròn 24 (số bước, nền `ACCENT_NAV`, chữ `ACCENT`; bước đã xong → nền `GREEN` mờ + dấu `✓`) · tiêu đề 14 BOLD · giãn · tóm tắt 12 `TEXT_MUTED` · mũi `▾/▴`. Thân: padding 10/12, gap 9.

| Bước | Trạng thái mặc định | Nội dung |
|---|---|---|
| 1 · Nguồn ảnh | gập, tóm tắt `Import SM · 5 frame` | 3 nút bằng nhau: `Import SM…` · `Tạo mới (PNG)` · `Sửa import` |
| 2 · Hai lớp ảnh | mở | 2 hàng: `[● Lớp SAU] [combo hqsau1] [ … ]` + dòng trạng thái `✓ 5 frame · HQ1.png … HQ5.png` (GREEN, thụt 111px cho thẳng cột); `[công tắc] [Lớp TRƯỚC] [combo blue] [ … ]` + dòng `tắt — bật công tắc để chọn ảnh lớp trước` (combo mờ khi tắt) |
| 3 · Canh chỉnh & áp vào game | mở | xem 6.6 |
| 4 · Nâng cao | gập | Enum / Anim script / Prefab (monospace) |

Swing: header là `JButton` phẳng (đã có mẫu `Theme.painted`), thân là `JPanel` `setVisible(false/true)` — không dùng `JTabbedPane`. Trạng thái gập/mở nhớ theo phiên (không cần lưu file).

### 6.6 Bước 3 — Canh chỉnh (gộp 2 khối cũ)

Bản cũ có **hai** khối rời: “Scale CẢ 2 lớp” và “Canh chỉnh (từng lớp)”. Bản mới gộp thành một, chọn đối tượng bằng segmented 3 lựa chọn:

`Đang chỉnh: [Lớp SAU] [Lớp TRƯỚC] [Cả 2 lớp]` (cao 28 trong khung 34, nền `BG_INPUT`)

Rồi lưới 3 cột `64 | giãn | 78`, gap 6/10:

| Nhãn | Slider | Ô số |
|---|---|---|
| Scale | rãnh 4px `#2a2a33`, phần đã kéo `ACCENT`, núm 14px `ACCENT` viền 2px `BG_MAIN` | `0.67×` |
| Offset X | như trên (gốc giữa) | `21` |
| Offset Y | như trên | `−35` |

- Hàng chip nhanh: `Nhanh` + `×0.5 ×0.75 ×1 ×1.5 ×2` (chip 24) + `PPU 55.5556` (monospace, `TEXT_DIM`, sát phải).
- Hàng nút: `Áp SCALE` (tint BLUE 34) · `Áp VỊ TRÍ` (tint BLUE 34) · `Reset` (ghost 34).
- **Mọi slider phải có ô số nhập tay** (bản cũ chỉ có nhãn chỉ-đọc). Dùng `Theme.spin(...)` + `Theme.spinInt(...)` (đã xử lý commit khi nút không nhận focus).
- Ô "Đổi cỡ × / Áp PPU" cũ: gộp vào chip nhanh + nút `Áp SCALE`; nếu vẫn cần nhập hệ số tự do thì đặt trong bước 4 (Nâng cao).

### 6.7 Chân rail (ghim)

`Tạo / cập nhật cho SM` — nút primary cao 40, full width, chữ 15 BOLD; dưới là dòng 12 `TEXT_DIM` monospace `ghi vào Enum_HaoQuang · PlayHaoQuangAnim · HaoQuang2`. Chân này **không cuộn** (`BorderLayout.SOUTH` của inspector), viền trên `DIVIDER`.

## 7. Màn 2 — Bố cục Map

File: `ui/MapLayoutEditorFrame.java` (`buildToolbar`, `buildFxRow`, `buildTreePanel`, `buildInspector`, `buildStatusBar`).

### 7.1 Thanh công cụ: 3 hàng → 1 hàng (cao 60)

Thứ tự trái → phải:

1. Combo map `1 — Làng Aru` (rộng 212, cao 36)
2. `Nạp` (primary 36)
3. Segmented **Chế độ**: `Chọn` / `Di chuyển` / `Đường kẻ` / `Biên map` (cao 30 trong khung 36; mục chọn nền `ACCENT`, chữ `ON_ACCENT`)
4. Ba **nút menu** (`JButton` + `JPopupMenu`), mỗi nút in kèm trạng thái ngay trên mặt nút:
   - `Hiện 4/6` — nút đang bật (nền `ACCENT_SEL`, viền accent) vì có mục bị tắt
   - `Lưới & hít`
   - `Hiệu ứng`
5. Giãn
6. `Xuất JSON` (ghost 36) · `Lưu vào prefab` (primary 36) · `⋯` (ô 36×36)

Nội dung 3 menu (dùng `JCheckBoxMenuItem`, nền `BG_SURFACE2`, dòng cao 32, chữ 13, có header nhóm 11 `TEXT_DIM`):

- **Hiện** → nhóm `LỚP VẼ`: Sprite · Collider · Lưới · Chỉ đường đất · Xem trước Player · Biên map; ngăn cách; `Hiện cả phần đã tắt`.
- **Lưới & hít** → `Cỡ lưới` (spinner) · `Hít lưới` · `Hít khít` + `ngưỡng (px)` (spinner) · dòng nhắc `Giữ Alt khi kéo = tạm tắt hít khít`.
- **Hiệu ứng** → `Chạy hiệu ứng` + `tốc độ` (spinner) · `Reset` · `Hiện hiệu ứng` · `Nhãn hiệu ứng` · `Hiệu ứng trong map…` · `Thêm Spine…`.
- **⋯** → `Canh khung` · `Khớp biên vào ảnh map` · ngăn cách · `Hoàn tác tất cả` (chữ `RED`).

`Sorting layer: đọc từ TagManager.asset (21 layer)` rời khỏi thanh công cụ → xuống panel phải (7.3).

### 7.2 Panel cây (308px)

- Header: `PHẦN TỬ MAP` + `71 phần tử · 6 nhóm`; ô tìm cao 34 `Tìm phần tử, layer, order…`.
- Dòng cây cao 32–34: mũi mở/gập · checkbox 15px (bật/tắt GameObject) · tên 13 · **giãn** · badge phải `L7 · #6` (bo 5, cao 20, monospace 11, nền `BG_INPUT` viền `BORDER_SOFT`). Dòng đang chọn: nền `ACCENT_SEL`, thanh 3px `ACCENT` bên trái, badge tint accent.
- Nhóm (`Layer_0`, `Layer_Ground`…) hiện **số phần tử con** ở cột phải; nhóm đang mở nền `BG_ZEBRA`.
- Việc cần làm trong renderer: tách chuỗi `SPR L7#5` thành badge căn phải để quét mắt theo cột thay vì đọc chữ dính nhau.

### 7.3 Panel phải (352px) — MỚI

Tab: `Thuộc tính` (active) | `Layer` | `Hiệu ứng`.

Card 1 — vật thể đang chọn (`Sprite_2`, nhóm `Layer_Ground`), lưới 2 cột `82 | giãn`, gap 8/10:

| Nhãn | Control |
|---|---|
| Sorting | combo `7 — Ground` (cao 32) |
| Order | spinner 74 + 2 nút vuông 36 `↑` `↓` (đưa lên/xuống trong cùng layer) |
| Vị trí | 2 ô `X −22.38` · `Y 1.84` (monospace, chia đều) |
| So với player | segmented `Sau player` / `Trước player` (cao 26) |

Card 2 — `Vùng & đường kẻ`: đếm `7 đường · 6 box` + 2 nút `Thêm đường kẻ` · `Thêm Spine…`.
Card 3 — dòng `Sorting layer` + `TagManager · 21 layer` (đưa từ thanh công cụ xuống).

Chân panel (ghim): `↶ Hoàn tác (0 bước)` (ghost 38, chiếm phần lớn) + ô 44×38 tint `RED` `⟲` = Hoàn tác tất cả, kèm dòng nhắc 12 `TEXT_DIM` `Hoàn tác tất cả nằm riêng, cách xa nút Lưu.`

### 7.4 Khung xem

- Lưới 56px; thẻ thông tin nổi ở **góc dưới trái** (rộng 330, nền `rgba(10,10,13,.94)`, viền `BORDER`, bo 10): hàng 1 `Map 1` + chip `chế độ CHỌN` + `zoom 9.9 px/u`; kẻ ngang; lưới 2 cột `chuột / server / hiệu ứng`.
  Bản cũ nhồi cả `chọn: Sprite_2 · layer 7 / order 6 · sau player` vào thẻ này — phần đó đã chuyển sang panel phải để **sửa được**, không chỉ đọc.
- Vật thể đang chọn: khung 1px `ACCENT` + 4 tay cầm 7px + nhãn `Sprite_2 · L7 #6` phía trên khung.
- Góc dưới phải: chip `Left / Right / Top / Bottom = biên map` và `6 trigger`.

### 7.5 Thanh dưới (cao 44)

Chip 12px: `71 phần tử` · `42 renderer` · `7 đường kẻ` · `6 vùng box` · `6 hiệu ứng` — giữa: **một dòng** mẹo chuột `Chuột phải hoặc Space+trái: pan · Lăn: zoom · Shift: khoá trục · Alt: tạm tắt hít khít` — phải: chip cảnh báo tint `ACCENT` `⚠ 7 nested prefab · 17 block stripped`.
Bản cũ là 3 dòng chữ 11px chen nhau.

## 8. Tương tác & trạng thái

- **Nút menu thanh công cụ**: bấm → `JPopupMenu` mở ngay dưới nút, lệch trái 0; nút đang mở giữ nền `ACCENT_SEL`. Mặt nút luôn in trạng thái tóm tắt (`4/6`) để không phải mở ra mới biết.
- **Step card**: bấm header → mở/gập; khi mở thì bước đang mở khác **không** tự đóng (người dùng có thể mở 2–3 bước cùng lúc); bước hoàn thành đổi badge sang `✓` GREEN + hiện tóm tắt.
- **Chip lớp trong khung xem**: bấm = ẩn/hiện lớp; lớp tắt → chip xám, viền `BORDER`.
- **Kéo trên khung xem** = đổi Offset của lớp đang chọn (giữ nguyên hành vi `HaoQuangPanel.dragBy`), và slider/ô số phải cập nhật ngay.
- **Nút phá huỷ**: `Đóng tool`, `Thoát app`, `Hoàn tác tất cả` luôn tint đỏ và **không đứng cạnh** nút Lưu/Nạp; `Hoàn tác tất cả` vẫn hỏi xác nhận.
- **Hover**: ghost → viền sáng thêm 30; primary → sáng thêm 22; dòng nav/cây → nền `BG_SURFACE2`.
- **Nhãn không được xuống dòng**: mọi nút/chip đặt `setPreferredSize`/`setMinimumSize` theo bề rộng chữ thật (`FontMetrics.stringWidth + padding`) và **không cho co** — trong `BoxLayout` dùng `setMaximumSize` = preferred; trong `GridBagLayout` dùng `weightx = 0`. Đây là lỗi phổ biến khi hàng bị chật: nút co lại vài px và nhãn tự ngắt thành 2 dòng.
- Không dùng `JToolBar` (nó cho phép co và tự thêm gờ kéo); dùng `JPanel` + `BoxLayout.X_AXIS` + `Box.createRigidArea` cho khoảng cách, `Box.createHorizontalGlue()` cho phần giãn.

## 9. Việc cần làm theo file

| File | Việc |
|---|---|
| `ui/MainFrame.java` | sidebar: 2 nút hệ thống lên hàng ngang, 2 nút đỏ xuống chân; nút cửa sổ ở top bar thu thành ô 32; top bar thêm phần ngữ cảnh sau dấu `/` |
| `ui/PlayerViewerFrame.java` | thẻ nguồn 1 hàng + hàng chip; danh sách id thành lưới ô + nhóm dải id; thanh khung xem 1 hàng (nhận thêm widget xem thử từ `HaoQuangPanel`); 2 chip lớp + chip hint trong khung xem |
| `ui/HaoQuangPanel.java` | `buildBody()` chia lại thành 4 step card có header gập (bỏ `group()` phẳng); gộp “Scale cả 2 lớp” + “Canh chỉnh” thành 1 bước có segmented 3 lựa chọn; `sliderRow()` thêm ô số nhập tay; chân panel ghim nút `Tạo / cập nhật cho SM`; xuất các widget xem thử ra ngoài cho canvas |
| `ui/MapLayoutEditorFrame.java` | `buildToolbar()` + `buildFxRow()` gộp còn 1 hàng + 3 `JPopupMenu` + menu `⋯`; `buildTreePanel()` renderer badge căn phải + số con; `buildInspector()` thêm card thuộc tính (Sorting/Order/Vị trí/trước-sau player) + chân ghim hoàn tác; `buildStatusBar()` 1 hàng chip |
| `ui/MapLayoutCanvas.java` | thẻ thông tin nổi rút còn 3 dòng số liệu và dời xuống góc dưới trái |
| `ui/Theme.java` | không đổi token; có thể thêm helper: `chip(String, Color)`, `stepHeader(int, String, String)`, `segmented(String…)`, `popupMenuButton(String, JPopupMenu)` |

## 10. Kiểm tra khi xong (checklist)

- [ ] Không control nào có nhãn xuống 2 dòng ở 1600×900 và 1920×1080.
- [ ] Rail phải Player Viewer hiện đủ 4 bước + nút chính **không cần cuộn** ở 1080p (đo: chiều cao nội dung ≤ chiều cao vùng thân).
- [ ] Thanh công cụ Bố cục Map đúng **1 hàng**, không cuộn ngang ở 1600px.
- [ ] Không có chữ nào nhỏ hơn 12.
- [ ] Nút đỏ không nằm cạnh nút Lưu/Nạp.
- [ ] Mọi chức năng cũ vẫn tới được (đối chiếu: 6 checkbox “Hiện”, cỡ lưới, hít lưới, hít khít + ngưỡng, chạy/tốc độ/reset hiệu ứng, nhãn hiệu ứng, hiệu ứng trong map, thêm Spine, hiện biên map, canh khung, xuất JSON, hoàn tác tất cả).

## 11. Assets

Không có ảnh mới. Hai vùng gạch chéo trong bản HTML là **placeholder cho khung render thật** (Spine của `SpineRenderer`, prefab map của `MapLayoutCanvas`) — không phải hình cần vẽ. Font Be Vietnam Pro đã nằm trong `src/main/resources/fonts/`.

## 12. Files trong gói

- `Uoc Rong Tools - Redesign.dc.html` — bản thiết kế 2 màn 1920×1080 + chú thích 1–7 dưới mỗi màn (mở bằng trình duyệt).
- `README.md` — tài liệu này.
