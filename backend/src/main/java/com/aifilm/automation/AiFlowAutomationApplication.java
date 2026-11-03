package com.aifilm.automation;

import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.Browser;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

@SpringBootApplication
@EnableScheduling
public class AiFlowAutomationApplication {

    public static final int PORT = 8080;

    public static void main(String[] args) throws IOException {
        AppPaths.init();
        if (isRunning(PORT)) {
            // Bấm đúp lần thứ hai: app đã chạy ngầm, chỉ mở lại giao diện.
            Browser.open("http://127.0.0.1:" + PORT);
            return;
        }
        SpringApplication.run(AiFlowAutomationApplication.class, args);
    }

    private static boolean isRunning(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
