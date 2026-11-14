# Nảy Party — Tay cầm (Android)

App Android native làm **tay cầm điện thoại** cho game tiệc **Nảy Party**.
Màn hình lớn (TV / máy tính) vẫn mở web như cũ tại
<https://nay-party.nguyenhailinh140101.workers.dev> để tạo phòng; điện thoại
cài app này, nhập mã phòng 6 số + tên là vào chơi — không qua trình duyệt,
không qua webview, chạm là ăn ngay ở tầng hệ điều hành.

## Cách dùng

1. Mở web Nảy Party trên TV/máy tính, tạo phòng, thêm Máy nếu thiếu người.
2. Mở app trên điện thoại, nhập **mã phòng** và **tên**, bấm **Vào phòng**.
3. TV chọn game; điện thoại tự hiện đúng tay cầm của từng game:
   - **Nhanh Tay** — nút ĐỢI… / BẤM NGAY! khổng lồ.
   - **Đố Vui Tốc Độ** — 4 đáp án lớn, khoá ngay khi chọn, báo đúng/sai ở màn công bố.
   - **Đoán Số Chuẩn** — bàn phím số + nút ĐOÁN.
   - **Đua Bấm Nút** — nút BẤM khổng lồ + số lần bấm của mình.
   - **Nhớ Màu** — 4 ô màu Đỏ / Xanh lá / Xanh dương / Vàng.
   - **Đẩy Ra Khỏi Sàn / Né Đạn** — gamepad thật: D-pad giữ hướng + A (ĐẨY) + B (Lướt),
     đa chạm theo từng ngón, hiện số mạng và màn K.O. khi bị loại.
   - **Kéo Co** — nút KÉO khổng lồ (mỗi lần bấm là một nhịp kéo), thanh nút thắt trực tiếp.
   - **Chia Hay Cướp** — ô nhập lời thương lượng + nút Gửi trong lúc đàm phán,
     nút CHIA / CƯỚP bí mật ở pha chọn.
   - **Cướp Sao** — gamepad (A: XÔ, B: Lướt), hiện tổng sao đã nhặt của mình.
   - **Giữ Vương Miện** — gamepad (A: XÔ, B: Lướt), banner "BẠN ĐANG GIỮ
     VƯƠNG MIỆN 👑" + điểm giữ của mình khi đang đội vương miện.
   - **Sơn Sàn** — gamepad (A: SƠN BÙNG, B: Lướt), hiện số ô đã sơn của mình.
   - **Cao Bồi** — nút khổng lồ ĐỢI… / BẮN!, kẹt súng khi bắn sớm, hiện thời
     gian phản ứng + điểm ở cuối mỗi vòng.
   - **Chung Một Thân** (hợp tác) — gamepad mà chỉ các hướng được chia cho
     mình mới sáng (hướng của đồng đội bị làm mờ), hiện điểm kiểm tra + tim đội.
   - **Đẩy Hộp Về Đích** (hợp tác) — gamepad thường, hiện số hộp đội đã giao.
   - **Chèo Thuyền** (hợp tác) — nút CHÈO khổng lồ tự cháy VÀNG đúng cửa sổ
     nhịp (±260ms, tính theo giờ máy chủ đã đồng bộ), hiện số lần trúng
     nhịp / tạt nước của mình.
   - **Xe Tăng Đấu Trường** — gamepad (A: BẮN, B: Lướt), nòng súng hướng
     theo hướng di chuyển cuối, hiện số mạng, rung khi mất mạng / bị loại.
   - **Đặt Bom** — gamepad (A: ĐẶT BOM, B: Lướt), đặt xong chạy ngay —
     bom nổ sau 2 giây theo hình chữ thập; hiện số mạng.
   - **Kẻ Giả Mạo** — mỗi vòng: đọc câu hỏi *của riêng mình* + 4 đáp án
     (khoá ngay khi chọn), qua pha bàn luận có ô chat chung cho cả phòng,
     rồi bấm tố cáo từng người chơi khác; màn công bố hiện kẻ giả mạo,
     cả hai câu hỏi thật/giả và điểm của mình.
   - **Thủ Thành** (hợp tác) — gamepad (A: BẮN, B: Lướt), header hiện
     máu pha lê + số slime cả đội đã hạ, cập nhật tại chỗ.
   - **Giải Mã Chung** (hợp tác) — danh sách các bước mật mã *chỉ của
     mình* (bước đã xong có ✓, bước tới lượt mình có 👉), 4 nút ký hiệu
     ĐỎ / XANH / VÀNG / HỒNG, header hiện màn + tim đội + tiến độ.
   - **Tiến Lên Miền Nam** — bài trên tay hiện dạng lưới lá bài (♦♥ đỏ),
     chạm để chọn/bỏ chọn (lá được chọn nhấc lên + viền vàng), nút ĐÁNH
     BÀI / BỎ LƯỢT / BỎ CHỌN; hiện bộ trên bàn cần chặn, số lá còn lại
     của từng đối thủ và thứ hạng khi về đích. Đánh sai bị máy chủ từ
     chối thì bài đã chọn vẫn giữ nguyên.
   - Game hợp tác: màn kết quả và bục vinh quang hiện **banner thành tích
     đội** (hạng + điểm mỗi người + thành tích đội) giống bản web.
4. App rung khi trả lời đúng, thắng vòng, mất mạng/bị loại (giống bản web),
   giữ màn hình luôn sáng khi đang trong phòng, và tự nhớ mã phòng + tên.

## Build

Không dùng Gradle. GitHub Actions dùng thẳng bộ Android SDK build-tools có
sẵn trên runner (aapt2 → javac → d8 → zipalign → apksigner với debug key tạo
tại chỗ) — xem `.github/workflows/build.yml`. APK debug nằm trong artifact
`nay-party-controller-apk` của mỗi lần chạy workflow, file
`nay-party-controller.apk`.

- Package: `com.nayparty.controller`
- minSdk 24 · targetSdk 34
- Java thuần (UI dựng bằng code), không thư viện ngoài — JSON dùng `org.json`
  có sẵn trong Android.

## API

App nói chuyện với cùng room API của worker Nảy Party:

- `POST /api/rooms/:code/join` `{name}` → `{seat, playerToken, color}`
- `GET /api/rooms/:code/state?role=player&token=…` (poll ~300ms)
- `POST /api/rooms/:code/action` `{role:"player", token, action, …}` với các
  action `tap`, `answer`, `guess`, `color`, `pad {u,d,l,r,a,b}`, `choose`, `chat`,
  `vote {seat}` (Kẻ Giả Mạo), `symbol {sym}` (Giải Mã Chung)
