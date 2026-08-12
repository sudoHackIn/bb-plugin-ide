package dev.bb.headless;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.application.ApplicationStarter;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Keeps an IntelliJ application alive after opening the first backend project. */
public final class BbBackendStarter implements ApplicationStarter {
    @Override
    public boolean isHeadless() {
        return true;
    }

    @Override
    public int getRequiredModality() {
        return NOT_IN_EDT;
    }

    @Override
    public void main(List<String> args) {
        if (args.size() != 2) {
            throw new IllegalArgumentException("Usage: bbBackend <project-path>");
        }

        Path projectPath = Path.of(args.get(1)).toAbsolutePath().normalize();
        Project project = ProjectUtil.openOrImport(projectPath);
        if (project == null) {
            throw new IllegalStateException("Unable to open project: " + projectPath);
        }

        System.out.println("BB_BACKEND_READY project=" + project.getName() + " path=" + projectPath);
        System.out.flush();

        DumbService.getInstance(project).runWhenSmart(() -> {
            System.out.println("BB_BACKEND_SMART project=" + project.getName());
            System.out.flush();
        });

        try {
            new CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
