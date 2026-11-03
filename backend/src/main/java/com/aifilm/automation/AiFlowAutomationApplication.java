package com.aifilm.automation;

import com.aifilm.automation.system.AppPaths;
import com.aifilm.automation.system.Browser;
import com.aifilm.automation.system.DesktopWindow;
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

    /** Cổng của app; đổi bằng -Dafm.port=... (ví dụ chạy thử song song với một bản đang chạy). */
    public static final int PORT = Integer.getInteger("afm.port", 8080);

    public static void main(String[] args) throws IOException {
        AppPaths.init();
        System.setProperty("server.port", String.valueOf(PORT));
        if (isRunning(PORT)) {
            if (Boolean.getBoolean("afm.noBrowser")) {
                System.err.println("Cổng " + PORT + " đang được một bản AI Film Maker khác dùng. Tắt bản đó hoặc chạy với -Dafm.port=<cổng khác>.");
                System.exit(1);
            }
            // Bấm đúp lần thứ hai: app đã chạy, chỉ mở lại cửa sổ (Edge đưa cửa sổ của cùng profile lên trước).
            String url = "http://127.0.0.1:" + PORT;
            var edge = DesktopWindow.findEdge();
            if (edge.isPresent()) {
                DesktopWindow.launch(edge.get(), url);
            } else {
                Browser.open(url);
            }
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
