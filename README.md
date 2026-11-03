# AI Film Maker

Tự động hóa Bước 3 (ảnh reference) và Bước 4 (video) từ output của Omni-Flash-Pipeline. Hệ thống dừng ở mỗi kết quả để người dùng duyệt, xoay vòng nhiều tài khoản Google, và đóng gói thành một bộ cài 1-click.

Thiết kế chi tiết (state machine, DB, luồng xoay vòng tài khoản): [docs/ARCHITECTURE-V2.md](docs/ARCHITECTURE-V2.md).

## Cách dùng (người dùng cuối)

1. Cài bằng `AIFilmMaker-Setup-<ver>.exe` (mặc định vào `C:\AIFilmMaker`), rồi bấm đúp biểu tượng **AI Film Maker**. Giao diện tự mở trong trình duyệt.
2. **Tài khoản** → Thêm tài khoản Google Flow → bấm **Đăng nhập** → đăng nhập Google trong cửa sổ Edge vừa mở → đóng cửa sổ → bấm **Đã đăng nhập xong**.
3. **Phim** → Thêm phim:
   - Thư mục `output/<phim>/` của Omni-Flash-Pipeline.
   - Folder Ảnh và Folder Video (nơi lưu file đã duyệt).
   - Chế độ chạy: **Chạy ngầm**, hoặc **Mở Flow** (tool điền sẵn, bạn tự bấm tạo).
4. **Import prompt** → **▶ Chạy**. Ảnh chạy trước. Video chỉ chạy khi mọi ảnh reference của nó đã được duyệt.
5. **Duyệt**: xem trước từng kết quả.
   - `A` = duyệt: file được chép vào folder, tên đúng ID (`CHAR-DOMINIC-1.png`, `042.mp4`).
   - `R` = tạo lại: có thể sửa prompt trước khi tạo lại.
6. **Thoát** ở góc phải trên để tắt hẳn ứng dụng (app chạy ngầm, không có cửa sổ riêng).

Dữ liệu (DB, profile tài khoản, file chờ duyệt, log) nằm ở `%LOCALAPPDATA%\AIFilmMaker`. Gỡ cài đặt không xóa thư mục này.

## Selector Google Flow (bắt buộc cho chế độ Chạy ngầm)

Google Flow không có API công khai, nên tool thao tác trên giao diện web. Các selector nằm trong `engine/selectors.json` (sau khi cài: `C:\AIFilmMaker\engine\selectors.json`) và **chưa được cấu hình** (giá trị `TODO`).

- Ghi lại selector trên giao diện Flow thật bằng lệnh:
  ```
  python -m playwright codegen --channel msedge https://labs.google/fx/tools/flow
  ```
  rồi điền vào file. Không cần build lại.
- Nếu còn `TODO`, phim ở chế độ Chạy ngầm sẽ tự **tạm dừng** và báo thiếu khóa nào.
- Chế độ **Mở Flow** vẫn dùng được ngay: tool mở Edge, điền được bước nào thì điền, bạn tự làm phần còn lại. Khi bạn bấm Download trên Flow, file tự về hàng chờ duyệt với tên đúng ID.

## Phát triển

Cần JDK 17+, Maven, Node.js 18+, Python 3.13 có `playwright` và `requests`.

```bat
:: Backend + worker (worker Python tự bật từ engine\ trong thư mục hiện tại)
cd backend
set MAVEN_OPTS=-Dfile.encoding=UTF-8
mvn -q package -DskipTests
cd ..
java -jar backend\target\app.jar

:: Frontend có hot reload (proxy /api sang :8080)
cd frontend && npm install && npm run dev
```

Tùy chọn JVM hữu ích khi phát triển:

| Tùy chọn | Tác dụng |
|---|---|
| `-Dafm.data.dir=<dir>` | Dùng thư mục dữ liệu riêng |
| `-Dafm.noBrowser=true` | Không tự mở trình duyệt |
| `-Dafm.noWorkers=true` | Không bật worker (để chạy worker tay khi debug) |

Muốn chạy thử cả vòng mà không cần Google: tạo tài khoản và phim với nền tảng **Chạy thử (FAKE)**.
- Tài khoản có email bắt đầu bằng `quota` luôn báo hết quota, dùng để thử xoay vòng tài khoản.
- Prompt chứa `[fake:reject]` sẽ bị từ chối.

## Đóng gói

```bat
build_release.bat
```

Script build trong `C:\afm-build`, vì `jlink`/`jpackage` không nhận đường dẫn có dấu như "Dự án". Kết quả:
- `C:\afm-build\image\AIFilmMaker\AIFilmMaker.exe`: bản chạy ngay, gồm JRE thu gọn + Python embeddable.
- `dist\AIFilmMaker-Setup-<ver>.exe`: bộ cài, tạo khi máy có Inno Setup 6.3+.

**Lưu ý:** launcher của jpackage (JDK 17) **không chạy được trong thư mục có chữ có dấu**. Bộ cài mặc định `C:\AIFilmMaker` và chặn chọn thư mục có dấu. Bản chạy ngay cũng phải đặt ở đường dẫn ASCII.
