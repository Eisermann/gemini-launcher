package com.github.eisermann.codexlauncher.mcp

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provides a custom file editor for CodexDiffVirtualFile.
 * When a CodexDiffVirtualFile is opened, this provider creates a CodexDiffFileEditor.
 */
class CodexDiffFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean = file is CodexDiffVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return CodexDiffFileEditor(project, file as CodexDiffVirtualFile)
    }

    override fun getEditorTypeId(): String = "codex-diff-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
