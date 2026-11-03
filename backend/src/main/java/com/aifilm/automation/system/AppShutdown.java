package com.aifilm.automation.system;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Tắt hẳn ứng dụng (Spring + worker Python + Edge con) trên một luồng riêng, để kịp trả response cho người gọi. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppShutdown {

    private final ApplicationContext context;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public void exitSoon(String reason) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.info("Tắt ứng dụng: {}", reason);
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(SpringApplication.exit(context, () -> 0));
        }, "shutdown");
        t.setDaemon(false);
        t.start();
    }
}
