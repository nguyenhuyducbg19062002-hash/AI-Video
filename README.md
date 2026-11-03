# AI Film Maker

Tự động hóa Bước 3 (ảnh reference) và Bước 4 (video) từ output của Omni-Flash-Pipeline. Hệ thống dừng ở mỗi kết quả để người dùng duyệt, xoay vòng nhiều tài khoản Google, và đóng gói thành một bộ cài 1-click.

Thiết kế chi tiết (state machine, DB, luồng xoay vòng tài khoản): [docs/ARCHITECTURE-V2.md](docs/ARCHITECTURE-V2.md).

## Cách dùng (người dùng cuối)

1. Cài bằng `AIFilmMaker-Setup-<ver>.exe` (mặc định vào `C:\AIFilmMaker`), rồi bấm đúp biểu tượng **AI Film Maker**. App mở trong cửa sổ riêng.
2. **Tài khoản** → Thêm tài khoản Google Flow → bấm **Đăng nhập** → đăng nhập Google trong cửa sổ Edge vừa mở → đóng cửa sổ → bấm **Đã đăng nhập xong**.
3. **Phim** → Thêm phim:
   - Folder Ảnh và Folder Video (nơi lưu file đã duyệt).
   - Thư mục `output/<phim>/` của Omni-Flash-Pipeline: tùy chọn.
   - Chế độ chạy: **Chạy ngầm**, hoặc **Mở Flow** (tool điền sẵn, bạn tự bấm tạo).
4. Nạp dữ liệu ở trang Phim:
   - **Thêm Excel ảnh**: `reference-prompts.xlsx` (ảnh gốc) và/hoặc `prompt-anh-panel.xlsx` (ảnh PANEL tạo từ ảnh gốc).
   - **Thêm Excel video**: `all-video-prompts.xlsx`.
   - **Thêm ảnh có sẵn**: thư mục ảnh đặt tên theo ID (ví dụ `Ảnh tham chiếu/CHAR-CALEB-1.png`). Ảnh đã có sẵn thì app không tạo lại.
5. **Danh sách cảnh**: xem từng cảnh theo đúng thứ tự file (tab Tạo ảnh / Tạo video). Mỗi cảnh hiện mô tả, prompt và các ảnh reference theo thứ tự upload lên Flow.
   - Nút **"+ Thêm ảnh"** gắn thêm ảnh cho cảnh, chọn được nhiều ảnh một lần.
   - Nút **So sánh** đặt kết quả cạnh ảnh reference và prompt để kiểm tra trước khi duyệt.
6. **▶ Chạy**. Một cảnh chỉ chạy khi mọi ảnh reference của nó đã có: ảnh gốc → ảnh PANEL → video. Mặc định app chờ **duyệt xong toàn bộ ảnh** rồi mới chuyển sang video, và tự upload đúng ảnh của từng cảnh lên Flow theo thứ tự trong cột Reference IDs.
7. **Duyệt**: xem trước từng kết quả.
   - `A` = duyệt: file được chép vào folder. Ảnh mang tên đúng ID (`CHAR-DOMINIC-1.png`); video mang tên mã phim + STT (`tap-5-ha-cupid_042.mp4`).
   - `R` = tạo lại: có thể sửa prompt trước khi tạo lại.
   - File tải về bị rỗng hoặc hỏng thì app tự tạo lại, không đưa lên màn Duyệt.
8. **Kiểm tra file thiếu/hỏng**: app tự quét khi bấm Chạy và mỗi 10 phút. Ảnh/video đã duyệt mà file trong folder bị mất hoặc hỏng thì app tự tạo lại đúng mục đó; nút cùng tên ở trang Phim dùng để quét ngay.
9. Ứng dụng chạy trong **cửa sổ riêng** (không phải tab trình duyệt). Đóng cửa sổ hoặc bấm **Thoát ứng dụng** để tắt hẳn.

Dữ liệu (DB, profile tài khoản, file chờ duyệt, log) nằm ở `%LOCALAPPDATA%\AIFilmMaker`. Gỡ cài đặt không xóa thư mục này.

## Selector Google Flow

Google Flow không có API công khai, nên tool thao tác trên giao diện web. Các selector nằm trong `engine/selectors.json` (sau khi cài: `C:\AIFilmMaker\engine\selectors.json`). Flow đổi giao diện thì sửa file này, không cần build lại.

Cách tool làm một cảnh (đã chạy thật trên Flow ngày 03/11/2026, tạo ảnh PANEL-1):
1. Vào dự án Flow của phim **trên tài khoản đang chạy** (mỗi tài khoản Google có dự án riêng; chưa có hoặc dự án đã bị xóa thì tự bấm "Dự án mới", link được nhớ ngay cho các cảnh sau), đặt số bản tạo = x1. Chạy được cả giao diện tiếng Việt / tiếng Anh, cả bố cục "Tác nhân" lẫn bố cục tạo trực tiếp.
2. Tải lên dự án những ảnh reference **chưa có** (Flow giữ nguyên tên file, ví dụ `CHAR-CALEB-1.png`).
3. Gõ prompt; mỗi `@ID` được thay bằng chip ảnh của Flow (gõ `@`, tìm ID, bấm đúng tên file).
4. Bấm Tạo. Ô prompt của Flow là chat với **Tác nhân**; nếu Tác nhân hỏi lại "proceed?", tool tự trả lời đồng ý (`agent_auto_reply`).
5. Chờ ô kết quả mới, rồi ⋮ → Tải xuống (ảnh 1K, video theo chất lượng của phim) và lưu với tên đúng ID.

- **Chạy ngầm**: tool tự làm cả 5 bước.
- **Mở Flow**: cửa sổ Flow hiện ra để bạn xem. **Ảnh**: tool tự làm cả 5 bước (nếu gắn được đủ ảnh @tag). **Video**: tool làm bước 1–3, bạn kiểm tra rồi tự bấm Tạo và Tải xuống. Mỗi tài khoản một cửa sổ, chạy song song theo ô "Song song" của phim. Đóng cửa sổ hoặc bấm "Tạm dừng phim" thì phim tạm dừng; nút "Tài khoản hết lượt" đổi sang tài khoản khác.
- Chưa kiểm tra trên Flow thật: chọn độ dài và chất lượng video, nội dung thông báo hết lượt / vi phạm chính sách (`TODO` trong file).
- Ghi lại selector bằng: `python -m playwright codegen --channel msedge https://flow.google.com/`

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
