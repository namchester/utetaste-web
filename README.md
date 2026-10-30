# UTE Taste

## 1. Giới thiệu

UTE Taste là ứng dụng web hỗ trợ quản lý chuỗi nhà hàng và đặt món tại bàn bằng mã QR. Khách có thể xem thực đơn, chọn món và theo dõi tiến độ phục vụ; nhân viên tiếp nhận đơn, bếp cập nhật chế biến và quản lý xác nhận thanh toán. Hệ thống phân quyền theo vai trò, chi nhánh và phiên bàn, đồng thời cung cấp quản trị thực đơn, nhân sự và báo cáo doanh thu. Tài khoản khách hàng có xác minh email, hồ sơ, món yêu thích và lịch sử đơn riêng. Dự án sử dụng PostgreSQL và Docker Compose để triển khai nhất quán.

## 2. Công nghệ sử dụng

- Java 25, Spring Boot 3.5.16, Maven 3.9.16 qua Maven Wrapper.
- Thymeleaf, Bootstrap 5.3.3, JavaScript.
- PostgreSQL 16, Spring Data JPA, Flyway.
- Spring Security, JWT cookie HttpOnly, BCrypt, CSRF.
- Docker Compose, WebSocket, Cloudinary, SMTP email.

## 3. Chức năng chính

- **GUEST:** quét QR bàn đang mở, thực đơn/giỏ hàng, gọi món và yêu cầu hỗ trợ/thanh toán.
- **USER:** đăng ký/xác minh email bằng OTP, hồ sơ, đổi/quên mật khẩu, email khôi phục, món yêu thích và lịch sử đơn do mình tạo khi đăng nhập.
- **ADMIN:** quản trị chi nhánh, danh mục/món/ảnh, nhân sự/phân quyền, bàn/QR, đơn toàn hệ thống và nhật ký.
- **MANAGER:** quản lý chi nhánh, tình trạng món, thu tiền/giảm giá, báo cáo doanh thu và CSV.
- **WAITER / CHEF:** xác nhận đơn → chế biến → món xong → phục vụ; cập nhật trạng thái qua WebSocket.
- Thanh toán tiền mặt/chuyển khoản do nhân viên xác nhận, phiếu thanh toán và báo cáo theo hóa đơn đã thu.

Điểm thành viên hiện có sổ điểm và giao diện đọc; chưa bật cộng điểm tự động vì hóa đơn chung chưa xác định người trả/phân bổ tiền. Chưa tích hợp cổng ngân hàng hoặc quy đổi điểm.

## 4. Yêu cầu hệ thống

- Windows 11, Git, Docker Desktop với Docker Compose; Docker Engine phải hoạt động.
- Python 3 nếu dùng script tạo cấu hình; có thể copy template và điền thủ công.
- Chạy Java trực tiếp hoặc tests: **JDK 25**. Maven Wrapper đi kèm tải Maven, không cần cài Maven riêng.

## 5. Hướng dẫn cài đặt và chạy

Áp dụng cho bản clone mới. Với database đã có dữ liệu, sao lưu và kiểm tra migration trước startup; giữ đúng Compose project và volume hiện có.

1. **Clone repository PRIVATE** bằng tài khoản được cấp quyền:

   ```powershell
   git clone https://github.com/namchester/utetaste-web.git
   cd utetaste-web
   ```

2. **Tạo .env** từ template sạch:

   ```powershell
   py scripts/init-env.py
   ```

   Script sinh secret/password ngẫu nhiên độc lập, không in giá trị và không ghi đè .env sẵn có. Hoặc dùng `Copy-Item .env.example .env` khi chưa có .env, rồi tự thay mọi CHANGE_ME bằng giá trị riêng.

3. **Kiểm tra biến cấu hình trên máy**, không đưa secret vào Git:

   | Biến | Ý nghĩa |
   |---|---|
   | DB_PASSWORD | Mật khẩu PostgreSQL |
   | JWT_SECRET, OTP_SECRET | Hai secret riêng, mỗi giá trị ít nhất 32 byte |
   | SEED_DEMO, DEMO_PASSWORD, ADMIN_PASSWORD | Seed database mới: chế độ demo dùng DEMO_PASSWORD; chế độ không demo dùng ADMIN_PASSWORD |
   | APP_BASE_URL, PORT, COOKIE_SECURE | Mặc định web local http://localhost:8080, cookie HTTP local không secure |

   Compose hiện cố định cổng 8080; PORT chỉ áp dụng khi chạy Java local. Docker app dùng hostname **db:5432**; DB_URL localhost trong .env dành cho Java trên host. Seed chỉ chạy khi chưa có user. Tài khoản demo gồm admin, manager, waiter, chef và các tài khoản chi nhánh 2; mật khẩu lấy từ .env riêng. Đổi biến seed không đổi mật khẩu đã lưu trong DB.

4. **Mở Docker Desktop**, chờ engine sẵn sàng. Nếu chưa cài, xem [hướng dẫn Docker Desktop cho Windows](https://docs.docker.com/desktop/setup/install/windows-install/).

5. **Khởi động:**

   ```powershell
   docker compose up -d --build
   ```

6. **Truy cập:** http://localhost:8080.

7. **Kiểm tra app và db:**

   ```powershell
   docker compose ps
   ```

8. **Xem log khi gặp lỗi:**

   ```powershell
   docker compose logs --tail 100 app
   ```

**IntelliJ / Maven local:** mở pom.xml, chọn JDK 25 cho Project/Importer/Runner; main class `vn.utetaste.UtetasteApplication`. Chọn cấu hình **UTE Taste - Local Spring Boot**, nạp .env tại Environment variables. Java không tự nạp dotenv từ working directory.

Cấu hình local trỏ DB Docker ở 127.0.0.1:5433. Sau khi sao lưu/phê duyệt nếu dùng DB hiện có, dừng app Docker và publish DB bằng `docker compose -f compose.yml -f compose.intellij.yml up -d --no-deps db`; giữ project/volume cũ và bảo đảm cổng 5433 rảnh. Chỉ chạy một app trên 8080. Với PostgreSQL host đã cấu hình DB_URL đúng, có thể chạy `.\scripts\start.ps1`; script nạp .env trước Maven.

## 6. Chạy kiểm thử

Dùng JDK 25. Tests mặc định dùng H2/mocks, không cần SMTP/Cloudinary thật:

```powershell
.\mvnw.cmd test
```

Bật Docker để chạy thêm PostgreSQL/Flyway tests trên Testcontainers độc lập:

```powershell
.\mvnw.cmd -B '-DpostgresIT=true' package
```

Kiểm script tạo env: `py scripts/check-init-env.py`. Kiểm ánh xạ Compose bằng dữ liệu giả: `node scripts/check-config-mapping.mjs` (cần Node.js). Các kiểm tra này không ghi vào PostgreSQL hiện tại.

## 7. Lưu ý

- Không upload .env, password, token, log riêng hoặc database backup. Không dùng tài khoản/secret demo trong production.
- SMTP mặc định tắt. Để đăng ký/khôi phục OTP thật, tự cấu hình MAIL_ENABLED, MAIL_FROM, SMTP_HOST/PORT/USERNAME/PASSWORD; Gmail dùng App Password hợp lệ với STARTTLS. Không chia sẻ OTP/App Password.
- Cloudinary là tùy chọn: tự cấu hình CLOUDINARY_CLOUD_NAME/API_KEY/API_SECRET để upload ảnh thật. Chưa cấu hình vẫn dùng ảnh cục bộ; mock/preview không chứng minh upload dịch vụ thật.
- Flyway tự chạy khi startup, Hibernate validate schema; không sửa migration đã áp dụng hoặc reset DB để vượt lỗi. **Không dùng docker compose down -v khi cần giữ dữ liệu PostgreSQL.**
- Ảnh minh họa có nguồn và giấy phép riêng; giữ trang [ghi công ảnh](src/main/resources/static/images/food/credits.html) và metadata đi kèm. Có thể xem tại /images/food/credits.html khi web chạy.
