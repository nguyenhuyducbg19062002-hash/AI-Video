package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.DesktopWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Quản lý tài khoản xoay vòng. Không lưu mật khẩu: với Flow, cookie nằm trong profile Edge riêng của từng tài khoản,
 * người dùng tự đăng nhập một lần qua nút "Đăng nhập".
 */
@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    static final String FLOW_URL = "https://flow.google.com/";

    private final AccountRepository accounts;
    private final AccountLoginService login;

    public record AccountForm(String email, Provider provider, String proxy, Integer priority, String notes,
                              String credentialFile, String gcpProject, String gcpLocation, Integer maxConcurrent) {}

    @GetMapping
    public List<AccountEntity> list() {
        return accounts.findAllByOrderByPriorityAscIdAsc();
    }

    @PostMapping
    @Transactional
    public AccountEntity create(@RequestBody AccountForm f) throws IOException {
        if (f.email() == null || f.email().isBlank()) {
            throw new IllegalArgumentException("Email không được để trống");
        }
        String email = f.email().trim();
        if (accounts.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("Email " + email + " đã có trong danh sách");
        }
        AccountEntity a = new AccountEntity();
        a.setEmail(email);
        a.setProvider(f.provider() == null ? Provider.FLOW_BROWSER : f.provider());
        applyForm(a, f);
        // FLOW_BROWSER phải đăng nhập trước; VERTEX_API / FAKE dùng được ngay
        a.setStatus(a.getProvider() == Provider.FLOW_BROWSER ? AccountStatus.NEED_LOGIN : AccountStatus.READY);
        accounts.save(a);
        Path profile = AppPaths.profiles().resolve("acc_" + a.getId());
        Files.createDirectories(profile);
        a.setProfileDir(profile.toString());
        return a;
    }

    @PutMapping("/{id}")
    @Transactional
    public AccountEntity update(@PathVariable long id, @RequestBody AccountForm f) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        applyForm(a, f);
        return a;
    }

    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi xóa");
        }
        accounts.delete(a);   // thư mục profile giữ lại trên đĩa để không mất cookie nếu xóa nhầm
    }

    /** Mở Edge THƯỜNG (không phải Playwright) với profile của tài khoản để người dùng tự đăng nhập Google. */
    @PostMapping("/{id}/open-login")
    @Transactional
    public AccountEntity openLogin(@PathVariable long id) throws IOException {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getProvider() != Provider.FLOW_BROWSER) {
            throw new IllegalStateException("Chỉ tài khoản FLOW_BROWSER cần đăng nhập trên trình duyệt");
        }
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi đăng nhập");
        }
        Path edge = DesktopWindow.findEdge()
                .orElseThrow(() -> new IllegalStateException("Không tìm thấy Microsoft Edge trên máy"));
        a.needLogin("đang đăng nhập");   // không READY ⇒ không bị giao job trong lúc profile đang mở
        Files.createDirectories(Path.of(a.getProfileDir()));
        // Mở thẳng trang Google Flow; người dùng bấm "Sign in" trên Flow và tự đăng nhập.
        // (Không dùng accounts.google.com/AccountChooser?Email=…: Google trả lỗi 400 Bad Request cho link này.)
        // --disable-background-mode: đóng cửa sổ là Edge thoát hẳn, nhả profile (không thì Edge còn chạy nền giữ khóa profile,
        // job và nút Kiểm tra đăng nhập sau đó báo "profile đang mở ở cửa sổ khác")
        new ProcessBuilder(edge.toString(), "--user-data-dir=" + a.getProfileDir(),
                "--no-first-run", "--no-default-browser-check", "--disable-background-mode", FLOW_URL).start();
        return a;
    }

    /**
     * "Kiểm tra đăng nhập" (nút Kiểm tra lại): tự mở Edge ẩn với profile của tài khoản, xem đã đăng nhập chưa rồi cập nhật trạng thái
     * (Cần đăng nhập → Sẵn sàng khi đã đăng nhập đúng tài khoản). Không @Transactional: xem AccountLoginService.check.
     */
    @PostMapping("/{id}/check-login")
    public AccountLoginService.CheckResponse checkLogin(@PathVariable long id) {
        return login.check(id);
    }

    /** Người dùng xác nhận đã đăng nhập xong và đã ĐÓNG cửa sổ Edge của tài khoản. */
    @PostMapping("/{id}/confirm-login")
    @Transactional
    public AccountEntity confirmLogin(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job");
        }
        a.markReady();
        return a;
    }

    @PostMapping("/{id}/enable")
    @Transactional
    public AccountEntity enable(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() != AccountStatus.LEASED) {
            a.markReady();
        }
        return a;
    }

    @PostMapping("/{id}/disable")
    @Transactional
    public AccountEntity disable(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() == AccountStatus.LEASED) {
            throw new IllegalStateException("Tài khoản đang chạy job, chờ job xong rồi tắt");
        }
        a.setStatus(AccountStatus.DISABLED);
        return a;
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (AccountStatus s : AccountStatus.values()) {
            m.put(s.name(), 0L);
        }
        accounts.findAll().forEach(a -> m.merge(a.getStatus().name(), 1L, Long::sum));
        return m;
    }

    private static void applyForm(AccountEntity a, AccountForm f) {
        a.setProxy(validateProxy(blankToNull(f.proxy())));
        a.setNotes(blankToNull(f.notes()));
        a.setCredentialFile(blankToNull(f.credentialFile()));
        a.setGcpProject(blankToNull(f.gcpProject()));
        a.setGcpLocation(blankToNull(f.gcpLocation()));
        if (f.priority() != null) {
            a.setPriority(f.priority());
        }
        if (f.maxConcurrent() != null) {
            if (f.maxConcurrent() < 1 || f.maxConcurrent() > 5) {
                throw new IllegalArgumentException("Số lệnh chạy cùng lúc phải từ 1 đến 5");
            }
            a.setMaxConcurrent(f.maxConcurrent());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** ip:port | ip:port:user:pass | http(s)/socks5://[user:pass@]host:port */
    private static final java.util.regex.Pattern PROXY_PLAIN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9.-]+:\\d{1,5}(:[^:\\s]+:[^:\\s]+)?$");
    private static final java.util.regex.Pattern PROXY_URL =
            java.util.regex.Pattern.compile("^(https?|socks5)://([^:@\\s/]+(:[^@\\s/]*)?@)?[A-Za-z0-9.-]+:\\d{1,5}/?$");

    /** Proxy sai dạng làm mọi lần mở Edge thất bại (ERR_PROXY_CONNECTION_FAILED): chặn ngay khi lưu. */
    static String validateProxy(String proxy) {
        if (proxy == null) {
            return null;
        }
        if (!PROXY_PLAIN.matcher(proxy).matches() && !PROXY_URL.matcher(proxy).matches()) {
            throw new IllegalArgumentException("Proxy \"" + (proxy.length() > 40 ? proxy.substring(0, 40) + "…" : proxy)
                    + "\" không đúng dạng. Dùng ip:port, ip:port:user:pass hoặc http://user:pass@ip:port. Không dùng proxy thì để trống.");
        }
        String portPart = proxy.replaceFirst("^.*?([A-Za-z0-9.-]+):(\\d{1,5}).*$", "$2");
        int port = Integer.parseInt(portPart);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Cổng proxy " + port + " không hợp lệ (1–65535)");
        }
        return proxy;
    }
}
