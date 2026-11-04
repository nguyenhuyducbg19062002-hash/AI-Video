# Chạy song song nhiều lệnh trên một tài khoản (nhánh `feature/chay-song-song-nhieu-lenh`)

Mục tiêu: trong lúc Flow đang tạo lệnh A, app gửi tiếp lệnh B, C… trên **cùng tài khoản**; lệnh nào xong trước thì tải về và
chuyển sang "Chờ duyệt" trước. Nhiều tài khoản cùng chạy như vậy. Thời gian chờ Google (video 5–7 phút) được chồng lên nhau
thay vì cộng dồn.

## 1. Hiện trạng

- Mỗi tài khoản chỉ phục vụ **một job một lúc** (`AccountEntity.leasedJobId`, nhả ngay khi job xong). Một worker Python = một
  job = một lần mở Edge → gửi → chờ → tải → báo.
- Trình duyệt ngồi chờ Google gần hết thời gian của job. Năng suất = số tài khoản.

## 2. Kết quả thử nghiệm trên Flow thật (04/11/2026, tài khoản ULTRA, bố cục tạo trực tiếp, chế độ ẢNH)

Thử trong một dự án Flow mới riêng, ảnh tốn **0 credit** ở gói ULTRA (video 8s/720p tốn 12 credit).

| Điều cần biết | Kết quả |
|---|---|
| Flow có cho gửi lệnh mới khi lệnh trước còn đang chạy? | **Có.** Gửi 3 lệnh liên tiếp không chờ: cả 3 chạy đồng thời, tiến độ tăng song song. |
| Tốc độ | 3 ảnh xong sau khoảng **37 giây** (chạy lần lượt khoảng 105 giây). Các ảnh xong lệch nhau 0–3 giây. |
| Ô đang tạo trông thế nào | Có ngay sau khi bấm Tạo (≤ 300 ms), nằm **trên cùng**. `aria-label` = **nội dung prompt**, chữ "N%" chạy; **chưa có** `data-media-id`. |
| Khi xong | `data-media-id` xuất hiện, `aria-label` đổi thành tên Flow tự đặt (ví dụ "Red apple on table"). |
| Gắn dấu (thuộc tính / thuộc tính JS) lên ô có bền không? | **Không.** Flow vẽ lại ô: chỉ ô mới nhất giữ dấu, các ô khác mất (1/3). Không dùng được. |
| Thứ tự ô | **Ổn định**: ô mới nhất ở trên cùng; lệnh gửi thứ k luôn ứng với ô thứ k tính từ dưới lên trong số ô do Flow tạo. |
| Nút Tạo | Tạm vô hiệu ngay sau khi bấm (vì ô prompt trống), bật lại khi gõ prompt mới. Không có thông báo từ chối. |

Chưa kiểm tra (cần làm trước khi coi là xong):
- **Video** có chạy song song giống ảnh không, ô video đang tạo có nhận diện được như ô ảnh không (tốn 12 credit/video).
- Tài khoản **PRO** `acc_1` (giao diện tiếng Việt, bố cục "Tác nhân"): chạy song song được mấy lệnh, ảnh tốn bao nhiêu credit.
- Giới hạn số lệnh đồng thời của Flow theo gói (mới thử 3).

## 3. Thiết kế

### 3.1 Gắn kết quả với đúng lệnh (điểm khó nhất)
Không dùng dấu gắn lên ô. Mỗi phiên (một tài khoản, một lần mở Edge) ghi:
- `n0` = số ô **do Flow tạo** đã có trong dự án lúc bắt đầu phiên (bỏ qua ô ảnh người dùng tải lên: nhãn kết thúc bằng
  `.png/.jpg…`, hàm `_media_ids` đã làm vậy);
- danh sách lệnh đã gửi theo thứ tự, mỗi lệnh có `rank` = 0, 1, 2…

Ô của lệnh có `rank` r là phần tử thứ `(tổng số ô Flow tạo − n0 − 1 − r)` trong danh sách ô (mới nhất ở đầu). Khi ô còn đang chạy,
đối chiếu thêm `aria-label == prompt` để phát hiện lệch. Nếu số ô không khớp `n0 + số lệnh đã gửi` (ai đó tạo / xóa ô bằng tay) thì
coi phiên **lệch pha**: ngừng gửi thêm, chờ các lệnh đang chạy xong rồi đóng phiên; lệnh nào không gắn được thì báo lỗi tạm thời
(tự thử lại).

### 3.2 Phía backend
- `accounts.max_concurrent` (mặc định **1**, đặt ở trang Tài khoản; ULTRA đề xuất 3). Mặc định 1 để không đổi hành vi cũ và vì
  chưa kiểm tra tài khoản PRO / bố cục "Tác nhân".
- Thuê tài khoản theo **số job đang giữ** (thay cho `leasedJobId` đơn): tài khoản `LEASED` khi còn job đang chạy, về `READY` khi
  job cuối xong.
- API mới `POST /api/worker/claim-more { workerId, accountId }`: cấp thêm job cho đúng tài khoản worker đang giữ phiên, nếu
  chưa đủ `max_concurrent`; chọn job theo đúng thứ tự "chạy theo cảnh" hiện có. `204` khi hết việc hoặc đã đủ.
- Heartbeat, hủy, thu hồi job treo vẫn theo **từng job** như cũ.
- Hết lượt (quota) của tài khoản: các job đang chạy vẫn được thu kết quả; không cấp thêm.

### 3.3 Phía worker / driver
`FlowBrowserDriver.run_session(account, first_job, claim_more, report_*)`:
vòng lặp một luồng (Playwright đồng bộ không dùng nhiều luồng):
1. còn chỗ và còn việc → `claim_more` → gửi lệnh mới (tải ảnh tham chiếu, gõ prompt, bấm Tạo, ghi `rank`) — khoảng 10–15 giây;
2. đọc lưới một lần, xem lệnh nào đã có `data-media-id` → tải về → báo `/generated` **ngay** (chuyển "Chờ duyệt");
3. nghỉ 1,5 giây; lặp tới khi không còn lệnh đang chạy và không còn việc → đóng Edge.
Các lệnh cùng phiên dùng chung một lần mở Edge và một dự án Flow của tài khoản đó.

### 3.4 Kiểm chứng
- `FakeDriver` mô phỏng nhiều lệnh đồng thời (độ trễ ngẫu nhiên) để kiểm tra phía backend và vòng lặp phiên mà không cần Flow.
- Unit test chọn job / đếm lease / chống vượt `max_concurrent`.
- Chạy thật: ảnh (0 credit) trước, video sau khi được phép tiêu credit.

## 4. Rủi ro và cách giảm
- **Điều khoản / khóa tài khoản:** gửi dồn dập nhiều lệnh một tài khoản có thể bị Google để ý hơn. Giảm: `max_concurrent` do người
  dùng đặt, mặc định 1, giãn 2–3 giây giữa các lần gửi; không thêm kỹ thuật che giấu (xem `ARCHITECTURE-V2.md`).
- **Hết lượt nhanh hơn:** cùng số lệnh nhưng tiêu credit nhanh hơn theo thời gian; tài khoản hết lượt sẽ chuyển trạng thái nghỉ như cũ.
- **Gắn nhầm kết quả:** xem 3.1; luôn có kiểm tra lệch pha.
- **Edge treo giữa phiên:** các lệnh đang chạy mất heartbeat → backend thu hồi như cũ; lệnh đã gửi lên Flow mà chưa tải được sẽ
  được nhận lại nhờ cơ chế `_pending_result` đã có (tải thay vì tạo lại).
