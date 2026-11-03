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
4. **Import prompt** → **▶ Chạy**. Ảnh chạy trước. Mặc định app chờ **duyệt xong toàn bộ ảnh** rồi mới chuyển sang tạo video, và tự upload đúng ảnh của từng cảnh lên Flow theo thứ tự trong cột Reference IDs. Tắt công tắc "Duyệt xong hết ảnh rồi mới tạo video" trong cài đặt phim thì cảnh nào đủ ảnh sẽ chạy ngay.
5. **Duyệt**: xem trước từng kết quả.
   - `A` = duyệt: file được chép vào folder. Ảnh mang tên đúng ID (`CHAR-DOMINIC-1.png`); video mang tên mã phim + STT (`tap-5-ha-cupid_042.mp4`).
   - `R` = tạo lại: có thể sửa prompt trước khi tạo lại.
   - File tải về bị rỗng hoặc hỏng thì app tự tạo lại, không đưa lên màn Duyệt.
6. **Kiểm tra file thiếu/hỏng**: app tự quét khi bấm Chạy và mỗi 10 phút. Ảnh/video đã duyệt mà file trong folder bị mất hoặc hỏng thì app tự tạo lại đúng mục đó; nút cùng tên ở trang Phim dùng để quét ngay.
7. Ứng dụng chạy trong **cửa sổ riêng** (không phải tab trình duyệt). Đóng cửa sổ hoặc bấm **Thoát ứng dụng** để tắt hẳn.

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

> **Đặt dự án ở đường dẫn KHÔNG DẤU** (ví dụ `D:\Dev\AI-Video`). Với đường dẫn như `D:\Dev\Dự án\...`, IntelliJ, Maven và jpackage đều báo `InvalidPathException: Illegal char <?>`.

**1. Backend trong IntelliJ**
- Mở thư mục gốc dự án bằng IntelliJ.
- Chuột phải `backend/pom.xml` → **Add as Maven Project** (nếu IntelliJ chưa tự nhận).
- File → Project Structure → SDK = JDK 17.
- Chọn cấu hình chạy **Backend** (có sẵn trong `.run/`) → Run.
  - Working directory = thư mục gốc, để backend tìm thấy `engine\` và bật worker Python.
  - Dữ liệu thử nghiệm nằm ở `data\` của dự án, tách khỏi dữ liệu của bản exe.
- Kiểm tra: http://127.0.0.1:8080/api/system/info → `"workersAlive": 3`.

**2. Frontend riêng (hot reload)**
```bat
cd frontend
npm install
npm run dev
```
Mở http://localhost:5173. Các lời gọi `/api` được proxy sang backend `127.0.0.1:8080`.

**3. Chạy backend bằng dòng lệnh (không cần IntelliJ, không cần cài Maven)**
```bat
cd backend
mvnw.cmd -q package -DskipTests
cd ..
java -Dafm.data.dir=data -jar backend\target\app.jar
```
Muốn có giao diện ở cổng 8080 thì phải `npm run build` trong `frontend\` trước khi package.

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
