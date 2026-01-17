package com.github.eisermann.codexlauncher.terminal

import com.github.eisermann.codexlauncher.settings.CodexLauncherSettings
import com.github.eisermann.codexlauncher.settings.options.WinShell
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.ui.TerminalWidget
import com.intellij.ui.content.Content
import com.intellij.openapi.util.Disposer
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import org.jetbrains.plugins.terminal.TerminalToolWindowFactory
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * Project-level service responsible for managing Codex terminals.
 * Encapsulates lookup, reuse, focus, and command execution logic so actions stay thin.
 */
@Service(Service.Level.PROJECT)
class CodexTerminalManager(private val project: Project) {

    companion object {
        private val CODEX_TERMINAL_KEY = Key.create<Boolean>("codex.launcher.codexTerminal")
        private val CODEX_TERMINAL_RUNNING_KEY = Key.create<Boolean>("codex.launcher.codexTerminal.running")
        private val CODEX_TERMINAL_CALLBACK_KEY = Key.create<Boolean>("codex.launcher.codexTerminal.callbackRegistered")
        private val CODEX_TERMINAL_SHIFT_ENTER_KEY = Key.create<Boolean>("codex.launcher.codexTerminal.shiftEnterSuppressed")
    }

    private val logger = logger<CodexTerminalManager>()
    private val scriptFactory = CommandScriptFactory(
        project = project,
        supportsPosixShell = {
            if (!SystemInfoRt.isWindows) {
                true
            } else {
                runCatching { service<CodexLauncherSettings>().state.winShell == WinShell.WSL }.getOrDefault(false)
            }
        }
    )

    private data class CodexTerminal(val widget: TerminalWidget, val content: Content)

    /**
     * Launches or reuses the Codex terminal for the given command.
     * @throws Throwable when terminal creation or command execution fails.
     */
    fun launch(baseDir: String, command: String) {
        val terminalManager = TerminalToolWindowManager.getInstance(project)
        var existingTerminal = locateCodexTerminal(terminalManager)

        existingTerminal?.let { terminal ->
            ensureTerminationCallback(terminal.widget, terminal.content)
            ensureShiftEnterIsIgnored(terminal.widget, terminal.content)
            if (isCodexRunning(terminal)) {
                logger.info("Focusing active Codex terminal")
                focusCodexTerminal(terminalManager, terminal)
                return
            }

            if (reuseCodexTerminal(terminal, command)) {
                logger.info("Reused existing Codex terminal for new Codex run")
                focusCodexTerminal(terminalManager, terminal)
                return
            } else {
                clearCodexMetadata(terminalManager, terminal.widget)
                existingTerminal = null
            }
        }

        var widget: TerminalWidget? = null
        try {
            widget = terminalManager.createShellWidget(baseDir, "Codex", true, true)
            val content = markCodexTerminal(terminalManager, widget)
            ensureShiftEnterIsIgnored(widget, content)
            if (!sendCommandToTerminal(widget, content, command)) {
                throw IllegalStateException("Failed to execute Codex command")
            }
            if (content != null) {
                focusCodexTerminal(terminalManager, CodexTerminal(widget, content))
            }
        } catch (sendError: Throwable) {
            widget?.let { clearCodexMetadata(terminalManager, it) }
            throw sendError
        }
    }

    /**
     * Returns true when the Codex terminal tab is currently selected in the terminal tool window.
     */
    fun isCodexTerminalActive(): Boolean {
        return try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            findDisplayedCodexTerminal(terminalManager) != null
        } catch (t: Throwable) {
            logger.warn("Failed to inspect Codex terminal active state", t)
            false
        }
    }

    fun typeIntoActiveCodexTerminal(text: String): Boolean {
        return try {
            val terminalManager = TerminalToolWindowManager.getInstance(project)
            val terminal = findDisplayedCodexTerminal(terminalManager) ?: return false
            val success = typeText(terminal.widget, text)
            if (success) {
                focusCodexTerminal(terminalManager, terminal)
            }
            success
        } catch (t: Throwable) {
            logger.warn("Failed to type into Codex terminal", t)
            false
        }
    }

    private fun locateCodexTerminal(manager: TerminalToolWindowManager): CodexTerminal? = try {
        manager.terminalWidgets.asSequence().mapNotNull { widget ->
            val content = manager.getContainer(widget)?.content ?: return@mapNotNull null
            val isCodex = content.getUserData(CODEX_TERMINAL_KEY) == true || content.displayName == "Codex"
            if (!isCodex) {
                return@mapNotNull null
            }
            CodexTerminal(widget, content)
        }.firstOrNull()
    } catch (t: Throwable) {
        logger.warn("Failed to inspect existing terminal widgets", t)
        null
    }

    private fun findDisplayedCodexTerminal(
        manager: TerminalToolWindowManager
    ): CodexTerminal? {
        val terminal = locateCodexTerminal(manager) ?: return null
        val toolWindow = resolveTerminalToolWindow(manager) ?: return null
        val selectedContent = toolWindow.contentManager.selectedContent ?: return null
        if (selectedContent != terminal.content) {
            return null
        }

        val isDisplayed = toolWindow.isVisible
        if (!isDisplayed) {
            return null
        }

        return terminal
    }

    private fun focusCodexTerminal(
        manager: TerminalToolWindowManager,
        terminal: CodexTerminal
    ) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) {
                return@invokeLater
            }

            try {
                val toolWindow = resolveTerminalToolWindow(manager)
                if (toolWindow == null) {
                    logger.warn("Terminal tool window is not available for focusing Codex")
                    return@invokeLater
                }

                val contentManager = toolWindow.contentManager
                if (contentManager.selectedContent != terminal.content) {
                    contentManager.setSelectedContent(terminal.content, true)
                }

                toolWindow.activate({
                    try {
                        terminal.widget.requestFocus()
                    } catch (focusError: Throwable) {
                        logger.warn("Failed to request focus for Codex terminal", focusError)
                    }
                }, true)
            } catch (focusError: Throwable) {
                logger.warn("Failed to focus existing Codex terminal", focusError)
            }
        }
    }

    private fun resolveTerminalToolWindow(
        manager: TerminalToolWindowManager
    ) = manager.getToolWindow()
        ?: ToolWindowManager.getInstance(project)
            .getToolWindow(TerminalToolWindowFactory.TOOL_WINDOW_ID)

    private fun markCodexTerminal(manager: TerminalToolWindowManager, widget: TerminalWidget): Content? {
        return try {
            manager.getContainer(widget)?.content?.also { content ->
                content.putUserData(CODEX_TERMINAL_KEY, true)
                setCodexRunning(content, false)
                ensureTerminationCallback(widget, content)
                content.displayName = "Codex"
            }
        } catch (t: Throwable) {
            logger.warn("Failed to tag Codex terminal metadata", t)
            null
        }
    }

    private fun clearCodexMetadata(manager: TerminalToolWindowManager, widget: TerminalWidget) {
        try {
            manager.getContainer(widget)?.content?.let { content ->
                clearCodexMetadata(content)
            }
        } catch (t: Throwable) {
            logger.warn("Failed to clear Codex terminal metadata", t)
        }
    }

    private fun clearCodexMetadata(content: Content) {
        content.putUserData(CODEX_TERMINAL_KEY, null)
        content.putUserData(CODEX_TERMINAL_RUNNING_KEY, null)
        content.putUserData(CODEX_TERMINAL_CALLBACK_KEY, null)
    }

    private fun reuseCodexTerminal(
        terminal: CodexTerminal,
        command: String
    ): Boolean {
        ensureTerminationCallback(terminal.widget, terminal.content)
        return sendCommandToTerminal(terminal.widget, terminal.content, command)
    }

    private fun sendCommandToTerminal(
        widget: TerminalWidget,
        content: Content?,
        command: String
    ): Boolean {
        val plan = scriptFactory.buildPlan(command) ?: return false

        return try {
            widget.sendCommandToExecute(plan.command)
            setCodexRunning(content, true)
            true
        } catch (t: Throwable) {
            logger.warn("Failed to execute Codex command", t)
            setCodexRunning(content, false)
            runCatching { plan.cleanupOnFailure() }
            false
        }
    }

    private fun isCodexRunning(terminal: CodexTerminal): Boolean {
        val liveState = invokeIsCommandRunning(terminal.widget)
        if (liveState != null) {
            setCodexRunning(terminal.content, liveState)
            return liveState
        }
        return terminal.content.getUserData(CODEX_TERMINAL_RUNNING_KEY) ?: false
    }

    private fun setCodexRunning(content: Content?, running: Boolean) {
        content?.putUserData(CODEX_TERMINAL_RUNNING_KEY, running)
    }

    private fun ensureTerminationCallback(widget: TerminalWidget, content: Content?) {
        if (content == null) return
        if (content.getUserData(CODEX_TERMINAL_CALLBACK_KEY) == true) return
        try {
            widget.addTerminationCallback({ setCodexRunning(content, false) }, content)
            content.putUserData(CODEX_TERMINAL_CALLBACK_KEY, true)
        } catch (t: Throwable) {
            logger.warn("Failed to register termination callback", t)
        }
    }

    private fun ensureShiftEnterIsIgnored(widget: TerminalWidget, content: Content?) {
        if (content == null) return
        if (content.getUserData(CODEX_TERMINAL_SHIFT_ENTER_KEY) == true) return

        val component = resolveTerminalComponent(widget) ?: return
        var suppressTyped = false
        val dispatcher = KeyEventDispatcher { event ->
            val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return@KeyEventDispatcher false
            if (!SwingUtilities.isDescendingFrom(focusOwner, component)) {
                if (event.id == KeyEvent.KEY_RELEASED && event.keyCode == KeyEvent.VK_ENTER) {
                    suppressTyped = false
                }
                return@KeyEventDispatcher false
            }

            when (event.id) {
                KeyEvent.KEY_PRESSED -> {
                    if (event.keyCode != KeyEvent.VK_ENTER || !event.isShiftDown) return@KeyEventDispatcher false
                    suppressTyped = true
                    event.consume()
                    true
                }

                KeyEvent.KEY_TYPED -> {
                    if (!suppressTyped) return@KeyEventDispatcher false
                    event.consume()
                    true
                }

                KeyEvent.KEY_RELEASED -> {
                    if (!suppressTyped) return@KeyEventDispatcher false
                    if (event.keyCode != KeyEvent.VK_ENTER) return@KeyEventDispatcher false
                    suppressTyped = false
                    event.consume()
                    true
                }

                else -> false
            }
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher)
        Disposer.register(project) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher)
        }

        content.putUserData(CODEX_TERMINAL_SHIFT_ENTER_KEY, true)
    }

    private fun resolveTerminalComponent(widget: TerminalWidget): Component? {
        val direct = widget as? Component
        if (direct != null) return direct

        return runCatching {
            val method = widget.javaClass.methods.firstOrNull { it.name == "getComponent" && it.parameterCount == 0 }
            method?.apply { isAccessible = true }?.invoke(widget) as? Component
        }.getOrNull()
    }

    private fun invokeIsCommandRunning(widget: TerminalWidget): Boolean? {
        return runCatching {
            val method = widget.javaClass.methods.firstOrNull { it.name == "isCommandRunning" && it.parameterCount == 0 }
            method?.apply { isAccessible = true }?.invoke(widget) as? Boolean
        }.getOrNull()
    }

    private fun typeText(widget: TerminalWidget, text: String): Boolean {
        val connector = runCatching { widget.ttyConnector }.getOrNull()
        if (connector != null) {
            return runCatching {
                connector.write(text)
                true
            }.getOrElse {
                logger.warn("Failed to write to Codex terminal connector", it)
                false
            }
        }

        val methods = widget.javaClass.methods
        val typeMethod = methods.firstOrNull { it.name == "typeText" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        if (typeMethod != null) {
            return runCatching {
                typeMethod.isAccessible = true
                typeMethod.invoke(widget, text)
                true
            }.getOrElse {
                logger.warn("Failed to invoke typeText on Codex terminal", it)
                false
            }
        }

        val pasteMethod = methods.firstOrNull { it.name == "pasteText" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java }
        if (pasteMethod != null) {
            return runCatching {
                pasteMethod.isAccessible = true
                pasteMethod.invoke(widget, text)
                true
            }.getOrElse {
                logger.warn("Failed to invoke pasteText on Codex terminal", it)
                false
            }
        }

        return false
    }
}
