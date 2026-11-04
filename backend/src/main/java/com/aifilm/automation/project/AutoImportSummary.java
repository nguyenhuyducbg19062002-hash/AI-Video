package com.aifilm.automation.project;

import com.aifilm.automation.project.OmniImportService.FolderResult;
import com.aifilm.automation.project.OmniImportService.ImportResult;

import java.util.ArrayList;
import java.util.List;

/** Tóm tắt (từng dòng, tiếng Việt) những gì đã tự nạp khi tạo phim, để hiện cho người dùng. */
final class AutoImportSummary {

    private AutoImportSummary() {}

    static List<String> lines(FolderResult r) {
        List<String> out = new ArrayList<>();
        ImportResult last = null;
        for (ImportResult f : r.files()) {
            out.add("✓ " + f.file() + ": " + f.rows() + ("IMAGE".equals(f.kind()) ? " ảnh" : " video"));
            last = f;
        }
        if (r.assets() != null) {
            int n = r.assets().added() + r.assets().replaced();
            if (n > 0) {
                out.add("✓ " + n + " ảnh có sẵn đã gán vào phim"
                        + (r.assets().skippedJobs() > 0 ? " (" + r.assets().skippedJobs() + " ảnh khỏi phải tạo)" : ""));
            }
        }
        if (last != null && last.missingRefs() != null && !last.missingRefs().isEmpty()) {
            List<String> m = last.missingRefs();
            out.add("Còn thiếu " + m.size() + " ảnh gốc chưa có prompt tạo và chưa có ảnh: "
                    + String.join(", ", m.subList(0, Math.min(5, m.size()))) + (m.size() > 5 ? "…" : ""));
        }
        return out;
    }
}
