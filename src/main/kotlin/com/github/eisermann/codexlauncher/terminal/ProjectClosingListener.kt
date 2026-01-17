package com.github.eisermann.codexlauncher.terminal

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener

/**
 * Listener that triggers cleanup of temporary Codex scripts when a project is closing.
 */
class ProjectClosingListener : ProjectManagerListener {
    override fun projectClosing(project: Project) {
        project.getService(TempScriptCleanupService::class.java)?.cleanup()
    }
}
