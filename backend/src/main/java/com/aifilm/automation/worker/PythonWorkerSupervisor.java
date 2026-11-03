package com.aifilm.automation.worker;

import com.aifilm.automation.AiFlowAutomationApplication;
import com.aifilm.automation.service.AppSettingsService;
import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.DesktopWindow;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bật N tiến trình Python worker khi app khởi động, bật lại khi một tiến trình chết, tắt hết (kể cả Edge con) khi app tắt.
 * Python: ưu tiên bản embeddable đóng gói kèm (<home>/python/python.exe); khi chạy dev thì dùng "python" trên PATH.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PythonWorkerSupervisor {

    private final WorkerToken token;
    private final AppSettingsService settings;
    private final DesktopWindow desktopWindow;
    private final List<Process> workers = new ArrayList<>();
    private volatile boolean stopping;

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)   // sau JobResultService.recoverAfterRestart
    public synchronized void start() {
        if (Boolean.getBoolean("afm.noWorkers")) {
            log.info("afm.noWorkers=true: không bật worker Python (chạy worker tay để debug)");
        } else {
            int n = Math.max(1, settings.getIntSetting("worker_processes", 3));
            for (int i = 0; i < n; i++) {
                workers.add(spawn("py-" + i));
            }
            log.info("Đã bật {} worker Python", n);
        }
        desktopWindow.openOnStartup("http://127.0.0.1:" + AiFlowAutomationApplication.PORT);
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    public synchronized void watchdog() {
        if (stopping) {
            return;
        }
        for (int i = 0; i < workers.size(); i++) {
            Process p = workers.get(i);
            if (p != null && !p.isAlive()) {
                log.warn("Worker py-{} đã dừng (exit {}), bật lại", i, p.exitValue());
                workers.set(i, spawn("py-" + i));
            }
        }
    }

    public synchronized int aliveCount() {
        return (int) workers.stream().filter(p -> p != null && p.isAlive()).count();
    }

    /** Đổi số worker lúc đang chạy (khi người dùng lưu cài đặt). */
    public synchronized void resize(int n) {
        n = Math.max(1, n);
        while (workers.size() < n) {
            workers.add(spawn("py-" + workers.size()));
        }
        while (workers.size() > n) {
            kill(workers.remove(workers.size() - 1));
        }
    }

    @PreDestroy
    public synchronized void stop() {
        stopping = true;
        workers.forEach(this::kill);
        workers.clear();
    }

    private void kill(Process p) {
        if (p == null) {
            return;
        }
        p.descendants().forEach(ProcessHandle::destroy);   // Edge do Playwright mở
        p.destroy();
    }

    private Process spawn(String id) {
        Path home = AppPaths.home();
        Path bundled = home.resolve("python").resolve("python.exe");
        Path script = home.resolve("engine").resolve("worker_main.py");
        String python = Files.exists(bundled) ? bundled.toString() : System.getProperty("afm.python", "python");
        List<String> cmd = List.of(python, "-u", script.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(home.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(AppPaths.logs().resolve("worker-" + id + ".log").toFile()));
        Map<String, String> env = pb.environment();
        env.put("AFM_API", "http://127.0.0.1:" + AiFlowAutomationApplication.PORT + "/api/worker");
        env.put("AFM_WORKER_TOKEN", token.value());
        env.put("AFM_WORKER_ID", id);
        env.put("PYTHONUTF8", "1");
        env.put("PYTHONIOENCODING", "utf-8");
        try {
            return pb.start();
        } catch (IOException e) {
            log.error("Không chạy được worker Python {}: {}", cmd, e.getMessage());
            return null;   // watchdog bỏ qua; trang Hệ thống báo 0 worker
        }
    }
}
