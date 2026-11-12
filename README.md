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
  action `tap`, `answer`, `guess`, `color`, `pad {u,d,l,r,a,b}`, `choose`, `chat`
