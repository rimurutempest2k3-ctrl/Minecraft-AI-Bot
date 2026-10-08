# Nhiều AI dùng chung một bot: Groq và Gemini

Mỗi yêu cầu chỉ có một AI trả lời được chấp nhận. AI chính được gọi trước; nếu thiếu key,
lỗi mạng, lỗi HTTP hoặc trả JSON không hợp lệ, chương trình xét AI dự phòng. Không gọi AI
thứ hai nếu AI thứ nhất đã thành công. Khi `bot ai auto on`, `ask` và `run` tự thực thi từng bước. Khi OFF, `ask` chỉ đề xuất và `run` bị khóa.

## Giao nhiệm vụ tự động

```text
bot start
bot ai auto on
bot ai run Hãy lấy tổng cộng 16 gỗ sồi vào túi đồ. Ưu tiên rương đã nhớ; thiếu thì đào thêm.
bot ai status
bot ai cancel
```

`run` gửi quan sát hiện tại, túi đồ, rương đang mở và bộ nhớ rương của đúng
server/chiều không gian. AI chọn một hành động JSON; mod kiểm tra rồi gọi lệnh
đã có. Khi đi/đào/mở/lấy/cất đồ còn chạy, chưa hỏi bước mới. Sau khi tác vụ dừng,
gửi kết quả lệnh và quan sát mới để AI xác định thành công hay lỗi.
Console tự hiện từng bước. Dùng `bot ai auto off` để hủy nhiệm vụ AI; sau đó `bot ai ask` chỉ đề xuất.

Giới hạn 20 lượt API, 10 phút/nhiệm vụ, 3 phút/hành động; wait chờ 5 giây.
Lệnh thay đổi trạng thái hoặc giao tác vụ thủ công ngắt nhiệm vụ AI. Lệnh đọc
info/inventory/status/memory/list/show không ngắt. `bot stop`, `bot pause` và
`bot ai cancel` ngăn các bước tiếp theo. Pause không tự khôi phục kế hoạch AI;
resume rồi giao run mới theo tiến độ đã thực tế đạt được.

Đổi thế giới/nhân vật, chết hoặc đóng game sẽ hủy; không tự chạy lại sau khởi động.
Phản hồi API đến muộn sau hủy không được thực thi. Yêu cầu API đang gửi có thể
vẫn hoàn tất ở nền; chờ API hết bận trước khi giao run khác hoặc đổi key/model.
ask/message từ AI kết thúc nhiệm vụ để người dùng xử lý; done là AI báo đã đủ
theo quan sát, chưa có bộ kiểm chứng tự động mọi mục tiêu ngôn ngữ tự nhiên.

Rương cần được mở ít nhất một lần để có ID trong bộ nhớ. Nội dung đã nhớ có thể
cũ; AI phải mở và đọc lại trước khi lấy. Baritone cần công cụ phù hợp để đào.
Chưa hỗ trợ chế tạo, ăn, chiến đấu hoặc đổi chiều không gian. Đã kiểm thử giả lập
trình tự, hủy, phản hồi muộn, giới hạn và tham số; cần thử luồng API + game thực tế.

## Bắt đầu trong IntelliJ

1. Chạy lại Minecraft Client/runClient và mở console bằng `.\bot-console.bat`.
2. Tạo key tại https://console.groq.com/keys bằng tài khoản Groq Free.
3. Nhập `bot get API groq`, rồi dán key khi console hỏi. Không gửi key vào cuộc trò chuyện.
4. Key Gemini đang lưu được giữ nguyên. Có thể thêm/đổi bằng `bot get API gemini`.
5. Chọn AI chính và thử:

```text
bot ai use groq
bot ai status
bot ai ask Hãy kiểm tra tình trạng và túi đồ của bot
```

Console tự hiện kết quả và tên dịch vụ đã trả lời. Nếu Groq lỗi, console thông báo chuyển
sang Gemini. Để đảo thứ tự: `bot ai use gemini`. Lệnh use tự đặt dịch vụ còn lại làm dự phòng.
Tắt dự phòng: `bot ai fallback off`. Bật lại: `bot ai fallback gemini` hoặc `bot ai fallback groq`.
`bot ai result` xem lại kết quả; status/result/use/fallback/goal không gọi API.

## Key riêng cho từng dịch vụ

Key được lưu trong `run/config/minecraft-ai-bot/secrets.properties` khi dùng IntelliJ.
Khi dùng mod JAR với launcher: `<thư mục game>/config/minecraft-ai-bot/secrets.properties`.

```properties
gemini_api_key=
groq_api_key=
```

Tệp có sẵn không bị ghi đè lúc khởi động. Khi gán một key, key của dịch vụ kia được giữ lại.
Biến môi trường GEMINI_API_KEY và GROQ_API_KEY được ưu tiên riêng cho từng dịch vụ.
Sửa key trong tệp có hiệu lực lần status/ask tiếp theo. Thay biến môi trường cần chạy lại game.
Không nhúng key trong JAR, lịch sử hay nhật ký. `bot get API <key>` cũ vẫn gán key Gemini.

Nếu terminal không hỗ trợ nhập ẩn, điền tệp hoặc dùng `bot get API groq <key>` /
`bot get API gemini <key>`. Nhập một dòng có thể để lộ key trên màn hình terminal.
Không gán key qua `bot ai ask` hay chat Minecraft. Không đổi cấu hình khi API đang chạy.

## Cấu hình mô hình

Tệp `ai.properties` trong cùng thư mục config. Cấu hình mới:

```properties
provider=groq
fallback=gemini
profile=bot-main
groq.model=openai/gpt-oss-20b
gemini.model=gemini-3.8-flash
session_revision=1
```

Cấu hình Gemini cũ được giữ nguyên: `model=` vẫn được dùng cho Gemini nếu chưa có
`gemini.model=`. Groq dùng model mặc định ở trên nếu thiếu `groq.model=`. Không cần SDK mới;
kết nối bằng Java HttpClient sẵn có. `openai/gpt-oss-20b` ở đây chạy qua Groq, không cần key OpenAI.
Không tự đăng ký/nâng gói trả phí. Hạn mức và quyền dùng mô hình tùy tài khoản.
Đổi model ngay trong console: `bot ai model groq openai/gpt-oss-20b` hoặc
`bot ai model gemini gemini-3.8-flash`. Lệnh chỉ lưu cấu hình, không gọi API kiểm tra.

## Bộ nhớ và khởi tạo prompt

`shared-memory.json` lưu sáu cặp yêu cầu/phản hồi hợp lệ gần nhất và mục tiêu dài hạn.
Khi đổi dịch vụ/model/key hoặc chạy lại game, bộ nhớ chung vẫn được giữ. Không chuyển
nguyên chữ ký thought/tool-call của Gemini sang Groq; chỉ chia sẻ văn bản đã kiểm tra.
Mỗi ask kèm quan sát game/túi đồ mới nhất. Lịch sử chứa đề xuất, không phải bằng chứng
hành động đã thực hiện. Quan sát mới nhất được ưu tiên nếu lịch sử mâu thuẫn.

Để đặt mục tiêu giữ lại dù lịch sử bị rút gọn:

```text
bot ai goal Thu thêm 16 khối gỗ sồi
bot ai ask Hãy đề xuất bước tiếp theo dựa trên mục tiêu đã lưu
```

Lệnh goal chỉ lưu mục tiêu, không gọi AI và không tự bắt đầu đào. Mục tiêu giữ tới khi bạn
đổi bằng goal khác. Chưa có sổ tiến độ tác vụ có cấu trúc, agent loop hoặc xác nhận done tự động.
Bộ nhớ chung mới bắt đầu từ bản này; các phiên Gemini cũ vẫn còn trong sessions nhưng
không tự nhập vào bộ nhớ chung. Khi nâng cấp, dùng goal để ghi mục tiêu đang tiếp tục.

Prompt đóng gói được chụp một lần cho mỗi provider/profile/model/key/session_revision.
Chỉ đánh dấu khởi tạo thành công sau phản hồi hợp lệ; đổi cấu hình tạo phiên riêng mới,
không xóa bộ nhớ chung. Prompt vẫn gửi mỗi lần gọi API vì các endpoint không tự nhớ nó.
Ứng dụng thêm revision công cụ `tools-v4` để khởi tạo prompt mới cho khả năng rương,
thực thi từng bước và chuẩn bị cúp local trước khi thu đá cuội (cobblestone); bộ nhớ chung vẫn giữ. Đổi session_revision để dùng bản prompt
mới trong các lần sửa tiếp theo. Không phải huấn luyện mô hình.

## Lỗi và giới hạn

HTTP 500/502/503/504 tự thử lại tối đa hai lần, sau 2 và 4 giây, rồi xét dịch vụ dự phòng.
HTTP 400/401/403/404/429 không thử lại cùng dịch vụ trong yêu cầu đó; xét dự phòng ngay.
Nếu tất cả đều lỗi, hiển thị lỗi cuối và không thêm lượt vào bộ nhớ chung. Nếu thiếu key,
bỏ qua dịch vụ. Mỗi dịch vụ được xét một lần, không quay vòng gọi vô hạn.
Đóng game sẽ ngắt yêu cầu/thời gian chờ; không chuyển AI sau khi bị ngắt.
Một yêu cầu chạy luồng nền; lệnh pause/stop vẫn dùng được. Khi công tắc auto ON, `ask` và `run` đều tự thực thi.

## Kiểm tra và nguồn

Đã kiểm tra giả lập: chuyển khi lỗi 429/503 hoặc JSON sai, thiếu key, không gọi dự phòng
khi thành công, giữ mục tiêu/lịch sử sau khởi động lại, giới hạn lịch sử, không ghi key vào
bộ nhớ/nhật ký, key hai dịch vụ không đè nhau, ưu tiên môi trường và chuyển đổi payload Groq.
Chưa gọi Groq bằng key thật. Gemini đã được người dùng kiểm tra ở bản trước.

- API Groq: https://console.groq.com/docs/api-reference
- JSON Groq: https://console.groq.com/docs/structured-outputs
- Hạn mức miễn phí: https://console.groq.com/docs/rate-limits
- Gemini: https://ai.google.dev/api/generate-content

## Thêm OpenAI API

Provider `openai` dùng Responses API tại https://api.openai.com/v1/responses, nhận JSON object và dùng
cùng bộ kiểm tra hành động/bộ nhớ local với Groq và Gemini. Model mặc định `gpt-4.1-mini`; có thể đổi bằng console.
Không tự thêm OpenAI vào thứ tự gọi hiện có, không đọc key của các provider khác để dùng cho OpenAI.

Khởi động lại Minecraft và console để nạp bản mới, sau đó:

```text
bot get API openai
bot ai use openai
bot ai fallback groq,gemini
bot ai status
bot ai ask Hãy kiểm tra tình trạng và túi đồ của bot
```

Nếu Terminal hỗ trợ nhập ẩn, dán key tại lời nhắc. Nếu không hỗ trợ, dùng tệp riêng
config/minecraft-ai-bot/secrets.properties (trong IDE là run/config/...) với `openai_api_key=...`.
Không dán key thật vào chat hay ảnh chụp. Biến OPENAI_API_KEY vẫn được ưu tiên nếu đã đặt.
Các key Gemini/Groq hiện có và bộ nhớ chung được giữ nguyên khi lưu thêm OpenAI key.

Đổi model: `bot ai model openai <model-id>` (tài khoản phải có quyền dùng model đó).
Thứ tự có thể là `provider=openai` / `fallback=groq,gemini`. Các provider trùng được bỏ qua.
Muốn Groq chính và OpenAI dự phòng: `bot ai use groq`, rồi `bot ai fallback openai,gemini`.
`bot ai fallback off` tắt dự phòng. Chỉ gọi AI tiếp theo sau lỗi/thiếu key; không gọi đồng thời cả ba.
Thêm key không tự gửi yêu cầu; chỉ ask/run mới gọi API. Chưa thử API thật trong quá trình tích hợp.

Phản hồi OpenAI phải completed, có output_text và hợp lệ với action/args/message. Phản hồi incomplete,
refusal hoặc công cụ ngoài giao thức bị từ chối; không đánh dấu prompt khởi tạo/không lưu vào bộ nhớ chung.
Prompt vẫn khởi tạo theo provider, model, key và revision; dữ liệu lịch sử dùng chung chỉ là văn bản đã kiểm tra.

Tài liệu chính thức:
- https://developers.openai.com/api/docs/quickstart
- https://developers.openai.com/api/docs/guides/structured-outputs
- https://developers.openai.com/api/docs/models/gpt-4.1-mini

## Công tắc thực thi AI

Mỗi lần mở game, công tắc mặc định OFF. Dùng bot ai auto on để bật, bot ai auto status để xem và bot ai auto off để tắt. Tắt sẽ hủy hành động của nhiệm vụ AI và bỏ qua phản hồi API đang chờ. Bật lại không khôi phục nhiệm vụ cũ; hãy giao yêu cầu mới. Bật công tắc không tự chạy đề xuất đã nhận trước đó. Khi có nhiệm vụ đang chạy, yêu cầu ask/run mới được từ chối cho tới khi nhiệm vụ kết thúc hoặc bạn dùng bot ai cancel.
