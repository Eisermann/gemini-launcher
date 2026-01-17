package com.github.eisermann.codexlauncher.mcp

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile

/**
 * Virtual file that holds diff data for the Codex diff editor.
 * When opened, it triggers CodexDiffFileEditorProvider to show a diff view.
 */
internal class CodexDiffVirtualFile(
    val targetFile: VirtualFile,
    val proposedContent: String,
    val filePath: String
) : LightVirtualFile("Diff: ${targetFile.name}", targetFile.fileType, "") {

    override fun toString(): String = "CodexDiffVirtualFile($filePath)"

    override fun isWritable(): Boolean = false

    override fun getFileType(): FileType = targetFile.fileType
}
