# Shop Editor — UocRongOnline-Tools

Quản lý shop trong tool: sửa tab shop + thêm/xóa/sửa item trong shop.

## Schema (verify từ DB thật bằng `cols`)

> ⚠ **Server đã gộp shop về 1 BẢNG `shop`** (mỗi dòng = 1 tab + toàn bộ item nhúng JSON, giống NroBlue). Bảng cũ `shop_type_config` + `shop_item_config` server GIỮ LÀM BACKUP, không còn load. Tool đọc/ghi thẳng bảng `shop`.

### `shop` — 1 tab cửa hàng (kèm toàn bộ item)
| Cột | Ý nghĩa |
|---|---|
| `id` (PK, gán tay) | Id tab. **Trùng enum `TypeShop` client — KHÔNG tạo/xóa/đổi id** (client hardcode, thêm type = build lại client) |
| `type` | Loại shop (parity NroBlue). Runtime chưa phân nhánh, mặc định 0 — tool KHÔNG động vào |
| `shop_name` | Tên tab |
| `mission_req` | Nhiệm vụ tối thiểu để tab hiện |
| `npcs` | JSON array int `[2,26,31]` — npcId mở shop này |
| `items` (LONGTEXT) | JSON array item của tab — xem entity server `ShopItemJson` (dưới) |

### `shop.items` — mỗi phần tử = 1 item (map entity `ShopItemJson`)
| Field JSON | Ý nghĩa |
|---|---|
| `id` | Id client gửi khi mua — tool tự sinh (max id toàn shop + 1), **phải duy nhất** |
| `infoId` | → item_info_config.id (PHẢI tồn tại, không client NRE) |
| `shopSlot` | Vị trí hiển thị |
| `clazz` | -1=tất cả, 0=Trái Đất, 1=Namek, 2=Saiyan |
| `limitType` | 0=không, 1=ngày, 2=tuần, 3=tháng |
| `limit` | Lượt mua/kỳ, ≤0 = vô hạn |
| `prices` | JSON `[{"key":<tiền>,"value":<giá>}]`. key: 1=Vàng, 2=Ngọc xanh, 3=Hồng ngọc. ⚠ **rỗng/hỏng = item FREE** (server không check) — editor bắt buộc validate ≥1 pair, value>0. UI client chỉ hiện pair đầu |
| `name` | Tên item lưu kèm cho dễ đọc — **server bỏ qua** khi load |

### `item_info_config` — template item (picker)
`id` (gán tay), `name`, `item_type`, `quality`... Icon client = sprite atlas tên theo id (tool chưa render icon item — phase sau).

### `version_tracker`
`table_name` PK, `version`, `last_op`, `last_changed_at`. Sau khi sửa shop → bump version 2 bảng để client tải config mới.

## Flow deploy sau khi sửa
1. Tool ghi DB — read-modify-write cột `shop.items` (backup bảng `shop` trước — `backup/shop_{ts}.bak.json`)
2. Tool bump `version_tracker` (table_name = `shop`)
3. ⚠ **Restart game server** — `ShopManager` chỉ load lúc boot, không reload runtime
4. ServerConfig có endpoint reload `POST /api/v2/configs/reload` (chưa gọi tự động từ tool)

## Quyết định thiết kế
- Cửa sổ riêng `ShopEditorFrame` (luôn-trên), độc lập canvas: trái = list tab (sửa name/mission_req/npcs), phải = bảng item + form sửa.
- Thao tác **ghi NGAY** vào DB (CRUD từng hành động) + backup trước mỗi ghi + bump version. Không buffer diff.
- KHÔNG cho tạo/xóa shop_type (client hardcode 1-18). ID các loại đều read-only.
- Price editor: 1 pair chính (dropdown tiền + giá). Nhiều pair → sửa JSON tay (warn UI client chỉ hiện pair đầu).

## Trạng thái
- [x] Khám phá schema (server entities + verify DB cols)
- [x] `ShopDao`: đọc/ghi 1 bảng `shop` (item nhúng cột JSON `items`) — types/items/itemInfos, insert/update/delete item (read-modify-write JSON), updateType, validate price (chặn FREE), backup bảng `shop`, bump version_tracker
- [x] `ShopEditorFrame`: trái=list tab (sửa name/mission/npcs), phải=bảng item + dialog Thêm/Sửa/Xóa (item picker, giá+loại tiền, limit, hành tinh)
- [x] Nút 🛒 Shop Editor (section Shop) + lệnh headless `shoptest`
- [x] Verify DB thật: 22 tab, 52 item tab 1, 909 item template — SQL chạy đúng
- [ ] Test GUI ghi thật (insert/update/delete) — nên thử trên item test trước

## Ghi chú từ data thật
- DB có **22 tab** (client enum TypeShop tới 18 — tab 19-22 thêm sau, kiểm tra client trước khi sửa các tab này)
- Nhiều item `limit_type=-1/limit=-1` (vô hạn) — dialog quy về 0 khi lưu (tương đương)
- Cùng item 2 dòng giá khác nhau (vd 6001: 99tr vàng HOẶC 99 ngọc) = 2 row riêng, đúng thiết kế
