package com.aifilm.automation.project;

import com.aifilm.automation.project.OmniImportService.ImportResult;
import com.aifilm.automation.project.ProjectService.ProjectForm;
import com.aifilm.automation.project.ProjectService.ProjectView;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;
    private final OmniImportService importer;

    @GetMapping
    public List<ProjectView> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public ProjectView create(@RequestBody ProjectForm form) {
        return service.create(form);
    }

    @PutMapping("/{id}")
    public ProjectView update(@PathVariable long id, @RequestBody ProjectForm form) {
        return service.update(id, form);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable long id) {
        service.delete(id);
    }

    @PostMapping("/{id}/import")
    public ImportResult importPrompts(@PathVariable long id) throws IOException {
        return importer.importProject(id);
    }

    @PostMapping("/{id}/start")
    public ProjectView start(@PathVariable long id) {
        return service.start(id);
    }

    @PostMapping("/{id}/pause")
    public ProjectView pause(@PathVariable long id) {
        return service.pause(id);
    }

    @PostMapping("/{id}/retry-failed")
    public Map<String, Integer> retryFailed(@PathVariable long id) {
        return Map.of("requeued", service.retryFailed(id));
    }

    /** Quét file đã duyệt trong Folder Ảnh / Folder Video: thiếu hoặc hỏng thì đưa về hàng đợi tạo lại. */
    @PostMapping("/{id}/verify-files")
    public ProjectService.VerifyResult verifyFiles(@PathVariable long id) {
        return service.verifyFiles(id);
    }
}
