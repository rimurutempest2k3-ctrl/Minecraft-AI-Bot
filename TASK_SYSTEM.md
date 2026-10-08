# Task Manager — ghi chú triển khai V1

Mục tiêu của V1 là nhận một nhiệm vụ cụ thể, thực hiện được bằng các Skill có sẵn và báo kết quả đúng. Chưa cần tự nghĩ ra việc mới.

## Trạng thái

```text
QUEUED -> PREPARING -> RUNNING -> COMPLETED
                        |  ^
                        v  |
                       PAUSED

Có thể chuyển sang FAILED khi không xử lý được lỗi.
Người dùng có thể CANCEL khi nhiệm vụ chưa kết thúc.
```

- `QUEUED`: đang đợi.
- `PREPARING`: kiểm tra điều kiện trước khi chạy.
- `RUNNING`: đang thực hiện.
- `PAUSED`: dừng tạm, giữ tiến độ.
- `COMPLETED`: đã kiểm tra và đạt yêu cầu.
- `FAILED`: không hoàn thành được, có lý do cụ thể.
- `CANCELLED`: người dùng hủy.

Ba trạng thái cuối là kết thúc. Nếu chạy lại, tạo lượt chạy mới để không lẫn log.

## Ví dụ nhiệm vụ

```json
{
  "taskId": "task_001",
  "type": "COLLECT_ITEM",
  "parameters": {
    "item": "minecraft:oak_log",
    "count": 16
  },
  "status": "QUEUED",
  "retryCount": 0,
  "maxRetries": 3
}
```

Đây chỉ là định dạng thử nghiệm. Khi viết code sẽ bổ sung thời gian tạo, thời gian cập nhật, timeout và dữ liệu để tiếp tục sau khi thoát game.

## Cách xử lý COLLECT_ITEM

1. Đếm số item đang có trong inventory.
2. Nếu đã đủ 16 thì hoàn thành ngay. Mặc định của V1 là **sở hữu ít nhất 16 item**, không bắt buộc 16 item đó đều được nhặt sau khi nhận lệnh.
3. Nếu thiếu, tìm nguồn tài nguyên trong khu vực bot có thể tiếp cận.
4. Di chuyển, phá block phù hợp, nhặt vật phẩm.
5. Đọc lại inventory. Nếu chưa đủ thì tiếp tục, nhưng phải có giới hạn thời gian và phạm vi tìm kiếm.

Nếu bot không thấy cây, không nên cho đi vô tận. Nếu bị kẹt, thử tính lại đường một số lần. Không giải quyết được thì trả lỗi để người dùng biết.

## Một số mã lỗi dự kiến

| Mã | Ý nghĩa |
| --- | --- |
| `RESOURCE_NOT_FOUND` | Không tìm được tài nguyên trong phạm vi tìm kiếm |
| `PATHFINDING_FAILED` | Không tìm được đường |
| `PLAYER_STUCK` | Nhân vật không tiến triển |
| `OUT_OF_REACH` | Mục tiêu nằm ngoài tầm thao tác |
| `INVENTORY_FULL` | Không còn chỗ chứa |
| `TIMEOUT` | Quá thời gian cho phép |
| `SKILL_UNAVAILABLE` | Chưa có Skill để làm bước này |
| `DISCONNECTED` | Mất kết nối |

Không phải lỗi nào cũng nên thử lại. Ví dụ không có Skill hoặc không có quyền phá block thì retry liên tục cũng vô ích.

## Tạm dừng, hủy và khôi phục

Pause phải dừng việc phát lệnh mới, đồng thời yêu cầu Skill hiện tại dừng an toàn. Cancel phải giải phóng quyền điều khiển nhân vật.

Khi mở game lại, đọc tiến độ đã lưu nhưng **không chạy tiếp ngay lập tức**. Trước hết kiểm tra inventory, vị trí, dimension và mục tiêu còn tồn tại hay không.

## AI liên quan thế nào?

Lệnh cố định chạy trực tiếp qua Task Manager. Với yêu cầu tự nhiên, AI tạo kế hoạch trước, sau đó hệ thống kiểm tra Skill và tham số.

FAST THINK có thể đề xuất tiếp tục, chỉnh kế hoạch, dừng hoặc chuyển sang DEEP THINK. DEEP THINK dùng để phân tích kết quả hoặc lỗi khó. Trong V1, AI không tự thêm nhiệm vụ mới.

## Những bài thử đầu tiên

- Giao nhiệm vụ lấy 16 gỗ khi inventory đang có 0, 8 hoặc 16 gỗ.
- Gửi item ID không hợp lệ; hệ thống phải từ chối.
- Pause giữa lúc đang đi, sau đó Resume.
- Cancel khi đang đào; bot phải dừng.
- Cố tình cho đường đi bị chặn; phải báo lỗi sau số lần thử có giới hạn.
- Thoát game giữa nhiệm vụ, vào lại và kiểm tra trước khi tiếp tục.
- Gửi kế hoạch AI có Skill chưa hỗ trợ; không được chạy.
- Cho API mất phản hồi; game không được đứng.

Khi các trường hợp này ổn định mới coi Task Manager V1 đủ nền tảng để mở rộng.
