package com.aifilm.automation.account;

import com.aifilm.automation.project.Provider;
import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.DesktopWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    static final String FLOW_URL = "https://labs.google/fx/tools/flow";

    private final AccountRepository accounts;

    public record AccountForm(String email, Provider provider, String proxy, Integer priority, String notes,
                              String credentialFile, String gcpProject, String gcpLocation) {}

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
        // Trang đăng nhập Google điền sẵn email; đăng nhập xong tự chuyển sang Flow. Mật khẩu do người dùng tự gõ.
        String loginUrl = "https://accounts.google.com/AccountChooser?Email="
                + URLEncoder.encode(a.getEmail(), StandardCharsets.UTF_8)
                + "&continue=" + URLEncoder.encode(FLOW_URL, StandardCharsets.UTF_8);
        // Gọi thẳng msedge.exe (không qua "cmd /c start": cmd cắt URL ở ký tự &)
        new ProcessBuilder(edge.toString(), "--user-data-dir=" + a.getProfileDir(),
                "--no-first-run", "--no-default-browser-check", loginUrl).start();
        return a;
    }

    /** Người dùng xác nhận đã đăng nhập xong và đã ĐÓNG cửa sổ Edge của tài khoản. */
    @PostMapping("/{id}/confirm-login")
    @Transactional
    public AccountEntity confirmLogin(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        a.releaseOk();
        a.setLastErrorMessage(null);
        return a;
    }

    @PostMapping("/{id}/enable")
    @Transactional
    public AccountEntity enable(@PathVariable long id) {
        AccountEntity a = accounts.findById(id).orElseThrow();
        if (a.getStatus() != AccountStatus.LEASED) {
            a.releaseOk();
            a.setCooldownUntil(null);
            a.setLastErrorMessage(null);
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
        a.setProxy(blankToNull(f.proxy()));
        a.setNotes(blankToNull(f.notes()));
        a.setCredentialFile(blankToNull(f.credentialFile()));
        a.setGcpProject(blankToNull(f.gcpProject()));
        a.setGcpLocation(blankToNull(f.gcpLocation()));
        if (f.priority() != null) {
            a.setPriority(f.priority());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
