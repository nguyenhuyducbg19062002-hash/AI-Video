package com.aifilm.automation.system;

import com.aifilm.automation.worker.PythonWorkerSupervisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final ApplicationContext context;
    private final PythonWorkerSupervisor supervisor;

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dataDir", AppPaths.data().toString());
        m.put("homeDir", AppPaths.home().toString());
        m.put("logsDir", AppPaths.logs().toString());
        m.put("workersAlive", supervisor.aliveCount());
        m.put("javaVersion", System.getProperty("java.version"));
        return m;
    }

    /** App chạy ngầm không có cửa sổ: nút "Thoát" trên giao diện gọi vào đây. Tắt cả worker và Edge con. */
    @PostMapping("/shutdown")
    public Map<String, String> shutdown() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);   // để kịp trả response
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context, () -> 0));
        }, "shutdown");
        t.setDaemon(false);
        t.start();
        return Map.of("message", "Đang tắt ứng dụng");
    }

    /**
     * Mở hộp thoại chọn thư mục của Windows (trình duyệt không cho web đọc đường dẫn thật trên máy).
     * Trả {"path": null} nếu người dùng bấm Hủy.
     */
    @PostMapping("/pick-folder")
    public Map<String, String> pickFolder(@RequestBody(required = false) Map<String, String> body) throws Exception {
        String initial = body == null ? "" : body.getOrDefault("initial", "");
        String script = """
                Add-Type -AssemblyName System.Windows.Forms
                $owner = New-Object System.Windows.Forms.Form -Property @{TopMost = $true; ShowInTaskbar = $false}
                $d = New-Object System.Windows.Forms.FolderBrowserDialog
                $d.ShowNewFolderButton = $true
                if ($env:AFM_INITIAL -and (Test-Path -LiteralPath $env:AFM_INITIAL)) { $d.SelectedPath = $env:AFM_INITIAL }
                if ($d.ShowDialog($owner) -eq [System.Windows.Forms.DialogResult]::OK) {
                  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
                  [Console]::Out.Write($d.SelectedPath)
                }
                """;
        Path ps1 = Files.createTempFile("afm-pick-", ".ps1");
        try {
            Files.writeString(ps1, "﻿" + script, StandardCharsets.UTF_8);   // BOM: PowerShell 5.1 đọc đúng UTF-8
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-ExecutionPolicy", "Bypass", "-File", ps1.toString());
            pb.environment().put("AFM_INITIAL", initial);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
            }
            String path = new String(out, StandardCharsets.UTF_8).trim();
            Map<String, String> m = new LinkedHashMap<>();
            m.put("path", path.isEmpty() ? null : path);
            return m;
        } finally {
            Files.deleteIfExists(ps1);
        }
    }

    /** Mở một thư mục trong Explorer (Folder Ảnh / Folder Video / log). */
    @PostMapping("/open-folder")
    public void openFolder(@RequestBody Map<String, String> body) throws IOException {
        Path p = Path.of(body.get("path"));
        if (!Files.isDirectory(p)) {
            throw new IllegalArgumentException("Thư mục không tồn tại: " + p);
        }
        new ProcessBuilder("explorer.exe", p.toString()).start();
    }
}
