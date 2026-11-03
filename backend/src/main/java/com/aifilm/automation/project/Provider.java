package com.aifilm.automation.project;

public enum Provider {
    /** Google Flow qua Playwright + Edge. */
    FLOW_BROWSER,
    /** Imagen / Veo qua Vertex AI (API chính thức). */
    VERTEX_API,
    /** Driver giả để chạy thử toàn bộ vòng duyệt mà không gọi nền tảng nào. */
    FAKE
}
