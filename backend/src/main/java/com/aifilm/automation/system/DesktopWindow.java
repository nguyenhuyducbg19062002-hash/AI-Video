package com.aifilm.automation.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mở giao diện như một ứng dụng desktop: cửa sổ Edge ở chế độ --app (không thanh địa chỉ, không tab,
 * biểu tượng riêng trên taskbar), dùng profile Edge riêng của app.
 *
 * Đóng cửa sổ = tắt ứng dụng. Có hai tín hiệu, vì Edge đôi khi vẫn giữ tiến trình sau khi cửa sổ đóng:
 *  1. tiến trình Edge của cửa sổ kết thúc;
 *  2. trang gửi "ui-closed" (sự kiện pagehide).
 * Trước khi tắt luôn đợi vài giây: nếu trang vẫn gửi heartbeat (người dùng chỉ tải lại trang) thì không tắt.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DesktopWindow {

    private static final int WIDTH = 1440;
    private static final int HEIGHT = 920;

    private final AppShutdown shutdown;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-window");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean desktopMode;
    private volatile long lastHeartbeat;
    private ScheduledFuture<?> pendingShutdown;

    /** Gọi lúc app khởi động xong. */
    public void openOnStartup(String url) {
        if (Boolean.getBoolean("afm.noBrowser")) {
            return;   // chạy dev (IntelliJ + npm run dev): không mở cửa sổ
        }
        Optional<Path> edge = findEdge();
        if (edge.isEmpty()) {
            log.warn("Không tìm thấy Microsoft Edge: mở giao diện bằng trình duyệt mặc định");
            Browser.open(url);
            return;
        }
        try {
            Process p = launch(edge.get(), url);
            desktopMode = true;
            Thread watcher = new Thread(() -> {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.info("Tiến trình cửa sổ ứng dụng đã kết thúc");
                scheduleShutdown(4);
            }, "ui-window-watch");
            watcher.setDaemon(true);
            watcher.start();
        } catch (IOException e) {
            log.warn("Không mở được cửa sổ ứng dụng ({}): dùng trình duyệt mặc định", e.getMessage());
            Browser.open(url);
        }
    }

    public void heartbeat() {
        lastHeartbeat = System.currentTimeMillis();
    }

    public void uiClosed() {
        if (desktopMode) {
            scheduleShutdown(8);
        }
    }

    private synchronized void scheduleShutdown(int seconds) {
        if (pendingShutdown != null) {
            pendingShutdown.cancel(false);
        }
        long mark = System.currentTimeMillis();
        pendingShutdown = timer.schedule(() -> {
            if (lastHeartbeat > mark) {
                return;   // trang vẫn sống (vừa tải lại): không tắt
            }
            shutdown.exitSoon("cửa sổ ứng dụng đã đóng");
        }, seconds, TimeUnit.SECONDS);
    }

    /** Dùng cả khi bấm đúp lần hai lúc app đã chạy: Edge chuyển yêu cầu sang cửa sổ đang mở của cùng profile. */
    public static Process launch(Path edge, String url) throws IOException {
        Path profile = AppPaths.data().resolve("ui-window");
        Files.createDirectories(profile);
        List<String> cmd = new ArrayList<>(List.of(
                edge.toString(),
                "--app=" + url,
                "--user-data-dir=" + profile,
                "--window-size=" + WIDTH + "," + HEIGHT,
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-mode",
                "--hide-crash-restore-bubble"));
        return new ProcessBuilder(cmd).start();
    }

    public static Optional<Path> findEdge() {
        List<Path> candidates = new ArrayList<>();
        for (String env : List.of("ProgramFiles(x86)", "ProgramFiles", "LOCALAPPDATA")) {
            String base = System.getenv(env);
            if (base != null) {
                candidates.add(Path.of(base, "Microsoft", "Edge", "Application", "msedge.exe"));
            }
        }
        return candidates.stream().filter(Files::isRegularFile).findFirst();
    }
}
