# Handoff: Ước Rồng Tools — Giao diện Dark Theme mới (Quản lý Shop)

## Overview
Thiết kế lại toàn bộ giao diện tool quản trị game "UocRongOnline Tools" (desktop app quản lý server game) với **màu đen chủ đạo**, chữ to rõ dễ đọc, nút bấm rõ ràng, và một số cải tiến UX. Màn hình chính là **Quản lý Shop**: sidebar điều hướng, form chỉnh sửa item, bảng danh sách item, và 2 modal (Thêm item mới, Option chỉ số).

## About the Design Files
File `UocRong Tools v2.dc.html` trong gói này là **bản thiết kế tham chiếu viết bằng HTML** — prototype thể hiện giao diện và hành vi mong muốn, **không phải code production để copy trực tiếp**. Nhiệm vụ của Claude Code là **tái tạo thiết kế này trong môi trường codebase hiện có của tool** (WinForms/WPF, Electron, JavaFX, web…) theo pattern và thư viện sẵn có. Nếu chưa có môi trường UI, hãy chọn framework phù hợp nhất với dự án (ví dụ Electron hoặc web app) rồi triển khai theo thiết kế.

## Fidelity
**High-fidelity (hifi)** — mọi màu sắc, cỡ chữ, khoảng cách, bo góc trong tài liệu này là giá trị cuối cùng. Tái tạo pixel-perfect bằng công cụ của codebase đích.

## Design Tokens

### Màu sắc
| Token | Hex | Dùng cho |
|---|---|---|
| `bg-app` | `#060608` | Nền ngoài cùng |
| `bg-main` | `#0d0d10` | Nền khu vực nội dung chính |
| `bg-panel` | `#0a0a0d` | Sidebar, title bar, top bar |
| `bg-surface` | `#121216` | Cửa sổ trong (inner window), modal |
| `bg-surface-2` | `#17171c` | Header bảng, hint box, nút phụ |
| `bg-input` | `#1a1a20` | Input, select |
| `bg-zebra` | `#15151a` | Dòng bảng chẵn (zebra) |
| `bg-hover-row` | `#1c1c23` | Hover dòng bảng |
| `border` | `#2a2a33` | Viền input/nút |
| `border-soft` | `#26262e` | Viền panel, kẻ bảng |
| `border-divider` | `#1c1c22` | Đường phân cách sidebar/title bar |
| `text` | `#f0f0f4` | Chữ chính |
| `text-secondary` | `#c6c6d0` | Chữ phụ |
| `text-muted` | `#8b8b98` | Label, header cột |
| `text-dim` | `#5c5c68` | Placeholder, chữ mờ |
| `accent` (vàng hổ phách) | `#f0b429` | Nút chính, tab active, dòng chọn, brand |
| `accent-hover` | `#ffc94d` | Hover accent |
| `accent-bg` | `rgba(240,180,41,.12)` | Nền item nav active, dòng chọn `.13` |
| `on-accent` | `#151004` | Chữ trên nút vàng |
| `green` | `#34d399` (bg `rgba(52,211,153,.1)`, border `.35`) | Thêm, +Option |
| `red` | `#f87171` (bg `rgba(248,113,113,.08–.1)`, border `.35`) | Xóa, Close, Exit |
| `blue` | `#60a5fa` (bg `rgba(96,165,250,.1)`) | Sửa |
| `purple` | `#a78bfa` (bg `rgba(167,139,250,.1)`, border `.4`) | Option (chỉ số), cột Option |
| `price-gem` | `#7dd3fc` | Giá Ngọc xanh |
| `price-gold` | `#f0b429` | Giá Vàng |

### Typography
- Font: **Be Vietnam Pro** (Google Fonts, hỗ trợ tiếng Việt) — weights 400/500/600/700/800.
- Chữ nền tảng: 15px. Tên item trong bảng: 15px/600. Số liệu: `font-variant-numeric: tabular-nums`.
- Header cột bảng: 12px/700, UPPERCASE, letter-spacing .06–.08em, màu `text-muted`.
- Label form: 14px/500 màu `text-muted`. Tiêu đề section sidebar: 11px/700 UPPERCASE letter-spacing .16em màu `text-dim`.
- Tiêu đề trang "Quản lý Shop": 18px/700. Tiêu đề modal: 17px/700.
- Brand: "APEX GAMES" 11px/700 màu accent; "ƯỚC RỒNG TOOLS" 22px/800 (chữ TOOLS màu accent) + gạch dưới 44×3px accent.

### Spacing / bo góc / bóng
- Bo góc: input & nút nhỏ 8–9px, nút chính 10px, inner window 14px, modal 16px.
- Chiều cao: input/select 42px, nút chính 44–46px, nút phụ 40px, top bar 60px, title bar 38px.
- Bóng: inner window `0 18px 60px rgba(0,0,0,.5)`; modal `0 30px 90px rgba(0,0,0,.7)`; nút vàng `0 4px 18px rgba(240,180,41,.25)`.
- Scrollbar tùy biến: track `#0d0d10`, thumb `#2a2a33` bo 6px.
- App root có `min-width: 1360px`.

## Screens / Views

### 1. Khung app (title bar + sidebar + main)
- **Title bar** 38px, nền `bg-panel`, viền dưới `border-divider`. Trái: "UocRongOnline Tools" 13px muted. Phải: 3 nút cửa sổ – ▢ ✕ (44×34px, hover nền `#1a1a20`; nút ✕ hover nền `#c0392b` chữ trắng).
- **Sidebar** 252px cố định, nền `bg-panel`, viền phải, padding 20/14px:
  - Brand block (xem Typography).
  - 4 nút hệ thống (42px, radius 9px, weight 600, icon + chữ, căn trái):
    - `⛶ Full Screen`, `↻ Restart App`: nền `bg-surface-2`, viền `border`; hover đổi màu viền/chữ (accent cho Full Screen, green cho Restart).
    - `✕ Close Tool`, `⏻ Exit App`: nền/viền đỏ nhạt, chữ `red`; hover nền đỏ đậm hơn `.18`.
  - Nhóm nav theo section: **DATABASE** (MySQL Config ⚙), **EDITORS** (Map Editor 🗺, **Shop 🛒 — active**, Chỉ số trang bị ⚔), **CỬA SỔ** (Xếp gọn cửa sổ 🗗). Item nav: padding 11/12px, radius 8px, 15px/500; hover nền `bg-surface-2`. Item active: nền `accent-bg`, chữ `accent-hover`, weight 600, viền trái 3px accent.
  - Cuối sidebar (margin-top auto): `v0.1.0 · 45.119.215.17:3306` 12px dim + chấm tròn 8px màu green (trạng thái kết nối DB).
- **Top bar** 60px: trái "🛒 Quản lý Shop" 18px/700; phải 2 nút ghost 38px (`⤢ Phóng to` hover viền blue, `– Thu nhỏ` hover viền accent).

### 2. Cửa sổ Quản lý Shop (inner window)
Nền `bg-surface`, viền `border-soft`, radius 14px, bóng lớn; nằm trong padding 20/24px của main.
- **Thanh tab**: nền `#0f0f13`, viền dưới. 2 tab: "Tab cửa hàng", "Item". Tab: padding 11/22/13px, 15px/600, màu muted, border-bottom 3px transparent; active: chữ `accent-hover` + border-bottom accent. Bên phải: label đếm "N item · Tab 1 — Cải Trang" 13px dim (cập nhật theo kết quả lọc).
- **Tab "Item"** = 2 cột: form 340px (viền phải) + bảng chiếm phần còn lại.

#### Form panel (trái, 340px, scroll dọc)
Grid `98px 1fr`, gap 12px, label căn giữa dòng. Thứ tự field:
1. **Tab** — select (1 — Cải Trang / 2 — Vật phẩm / 3 — Trang bị)
2. **Tìm** — input, placeholder "Gõ tên/id để lọc…" → **lọc bảng trực tiếp theo tên hoặc id** (live filter)
3. **Item** — select
4. **Loại tiền** — select (1 - Vàng / 2 - Ngọc xanh / 3 - Hồng ngọc)
5. **Giá** — input số
6. Hint box: "💡 Muốn bán bằng 2 loại tiền — tạo 2 dòng item riêng (mỗi dòng 1 loại tiền)." — 13px muted, nền `bg-surface-2`, viền `border-soft`, radius 9px, padding 10/12px
7. **Slot** — input number; **Kỳ giới hạn** — select (0 - Không…); **Lượt/kỳ (0=∞)** — input number; **Hành tinh** — select (-1 - Tất cả / 0 - Trái Đất / 1 - Namek / 2 - Xayda)

Input/select: 42px, nền `bg-input`, viền `border`, radius 9px, chữ 15px; focus viền accent.

Nút hành động (theo thứ tự):
- **＋ Thêm item mới** — nút chính: 46px, nền accent, chữ `on-accent` 16px/700, bóng vàng; hover `accent-hover`. → mở modal Thêm item.
- Grid 3×2 nút 40px: **Thêm** (green) / **Sửa** (blue) / **Xóa** (red) — kiểu tint: nền màu `.1`, viền `.35`, chữ màu tương ứng, hover nền `.2`; **⧉ Copy** / **📋 Paste** / **↻ Reload** — ghost: nền `bg-surface-2`, viền `border`, hover sáng viền.
- **⚙ Option (chỉ số) item** — 44px, tint purple. → mở modal Option.

#### Bảng item (phải)
- Grid columns: `56px minmax(180px,1fr) 140px 56px 80px 88px 92px` — ID / Tên item / Giá (căn phải) / Slot / Giới hạn / Hành tinh / Option.
- Header: nền `bg-surface-2`, viền dưới; style header cột như Design Tokens.
- Dòng: padding ô 11/14px, radius 8px, viền trái 3px transparent; zebra dòng chẵn nền `bg-zebra`; hover `bg-hover-row`; con trỏ pointer.
- **Dòng đang chọn**: nền `rgba(240,180,41,.13)`, viền trái 3px accent, tên item màu `accent-hover`.
- Màu ô: ID muted; Tên 15px/600 trắng (ellipsis khi tràn); Giá tabular-nums 600 — Ngọc xanh `price-gem`, Vàng `price-gold`; Giới hạn dim; Option màu purple, 13px.
- Click dòng → chọn (single select), đồng bộ với form.

#### Tab "Tab cửa hàng"
Danh sách card dọc (max-width 640px, gap 8px): mỗi card nền `bg-surface-2`, viền `border-soft`, radius 10px, padding 16/18px, hover viền accent. Trong card: badge số thứ tự 36×36px radius 9px nền `accent-bg` chữ accent 700; tên tab 16px/600; bên phải "N item" 14px muted. Dữ liệu mẫu: 1 Cải Trang (21) / 2 Vật phẩm (34) / 3 Trang bị (58) / 4 Đá nâng cấp (12).

### 3. Modal "Thêm item mới vào Shop"
- Overlay: `rgba(0,0,0,.7)` + `backdrop-filter: blur(3px)`, căn giữa, z-index cao.
- Hộp: **`width: min(1180px, 94vw)`**, max-height 90vh, nền `bg-surface`, viền `#33333d`, radius 16px, cấu trúc cột flex: header cố định / thân scroll / footer cố định.
- Header: tiêu đề "＋ Thêm item mới vào Shop" + nút ✕ 34×34px (hover viền+chữ đỏ).
- **Thân: grid 2 cột `480px 1fr`, gap 26px, align-start**:
  - **Cột trái — form** (grid `110px 1fr`, gap 12px): Tab shop (select, viền accent để nhấn mạnh field đầu) / Tìm item (input) / Item bán (select) / **Loại bán** — 2 radio (accent-color vàng, 17px): "Tiền tệ (Vàng / Ngọc / Hồng ngọc)" mặc định, "Vật phẩm (dùng item id làm giá)" / Đơn vị giá (select) / Giá / SL (input) / hint box "Shop bán tiền: chọn Vàng/Ngọc. Shop đổi đồ: chọn Vật phẩm → item id làm giá." / Slot / Kỳ giới hạn / Lượt/kỳ (0=∞) / Hành tinh.
  - **Cột phải — Option**: label "Option (chỉ số) — **không bắt buộc** (ghi vào equip_info):"; bảng option chiếm hết chiều cao còn lại (min-height ~420px) — header cột `56px 1fr 90px 90px 1fr` (ID / Tên option / Giá trị / Bonus± / Kết quả); trạng thái rỗng căn giữa: "Chưa có option nào — bấm ＋ Option để thêm"; dưới bảng 2 nút 40px: **＋ Option** (green tint), **－ Xóa** (red tint).
- Footer (viền trên, căn phải): **Đóng** (ghost 44px) · **✓ Thêm vào Shop** (accent 44px/700, bóng vàng).

### 4. Modal "Option (chỉ số)"
- Hộp 720px, max-height 86vh, cùng style modal.
- Header: "⚙ Option (chỉ số) — **6002 · Cải Trang Cooler Vàng**" (phần item màu accent) + nút ✕.
- **Cảnh báo** (quan trọng): box nền `rgba(240,180,41,.08)`, viền `rgba(240,180,41,.3)`, radius 10px, chữ 13px màu `#e0c98a`, các từ khóa **chỉ số GỐC** và **RESTART** in đậm màu `accent-hover`: "⚠ Sửa ở đây thay đổi chỉ số GỐC của item (equip_info id 6002) — áp dụng MỌI NƠI item xuất hiện. Double-click cột ID/Tên để đổi option · sửa Giá trị/Bonus ngay trên bảng → xong RESTART server."
- Bảng (viền, radius 10px, scroll, header sticky): cột `64px 1.2fr 100px 100px 1.6fr` — ID / Tên option / Giá trị (căn phải, màu `accent-hover`) / Bonus± (căn phải) / Kết quả (màu green). Hover dòng `bg-hover-row`.
- Dữ liệu mẫu: 103 Sức đánh 2300/0 "Sức đánh + 23 %" · 102 KI 1900/0 "KI + 19 %" · 107 Hiệu suất hồi KI 300/0 "Hiệu suất hồi KI 3 %" · 35 Tăng 200/800 "Tăng 2 % sức đánh khi ở gầ…" · 19 Chống lạnh 1/0 "Chống lạnh: 1".
- Footer nút: **＋ Thêm** (green) · **－ Xóa** (red) · đẩy phải **💾 Lưu** (accent).

## Interactions & Behavior
- **Lọc live**: gõ vào ô "Tìm" lọc bảng theo tên (không phân biệt hoa thường) hoặc id; label đếm item cập nhật theo.
- **Chọn dòng**: click dòng bảng → highlight vàng; dữ liệu dòng nạp vào form bên trái (trong bản prototype chỉ highlight — bản thật cần nạp form).
- **Tab**: click chuyển "Tab cửa hàng" ↔ "Item".
- **Modal**: "＋ Thêm item mới" mở modal thêm; "⚙ Option (chỉ số) item" mở modal option; ✕/Đóng đóng modal. Nên hỗ trợ đóng bằng phím Esc và click overlay (cải tiến thêm).
- Hover states như mô tả từng nút; mọi nút có transition nhẹ là tốt (không bắt buộc).
- Trong bản thật: bảng option cho phép sửa Giá trị/Bonus inline, double-click ID/Tên để đổi option (như cảnh báo mô tả); Copy/Paste thao tác trên dòng đang chọn; Reload tải lại dữ liệu từ MySQL.

## State Management
- `activeTab: 'shop' | 'item'`
- `selectedItemId: number | null` — dòng đang chọn trong bảng
- `searchQuery: string` — lọc bảng
- `modal: null | 'add' | 'option'`
- Dữ liệu: danh sách item của tab shop hiện tại (từ MySQL), danh sách option của item đang chọn (equip_info). Sau thao tác Lưu/Thêm/Xóa → refresh danh sách; option gốc thay đổi yêu cầu restart server (hiện cảnh báo).

## Assets
- Font **Be Vietnam Pro** từ Google Fonts (nếu app desktop offline: bundle file font).
- Icon dùng ký tự unicode/emoji đơn giản (⛶ ↻ ✕ ⏻ ⚙ 🛒 ⚔ 🗺 🗗 ⤢ 💾 ⧉ 📋 ＋ －) — codebase thật nên thay bằng bộ icon sẵn có (Lucide/Fluent…) cùng kích cỡ ~16–18px.
- Không có hình ảnh bitmap nào.

## Files
- `UocRong Tools v2.dc.html` — prototype HTML đầy đủ (mở trực tiếp trong trình duyệt). Toàn bộ style là inline; phần logic (lọc, chọn dòng, tab, modal) nằm trong class `Component` cuối file.

## Screenshots
Ảnh chụp prototype (trong `screenshots/`):
- `01-item-tab.png` — màn hình chính, tab Item (form + bảng)
- `02-shop-tabs.png` — tab "Tab cửa hàng"
- `03-modal-them-item.png` — modal Thêm item mới (2 cột, Option bên phải)
- `04-modal-option.png` — modal Option (chỉ số)
