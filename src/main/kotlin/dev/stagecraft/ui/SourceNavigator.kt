package dev.stagecraft.ui

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import dev.stagecraft.jenkins.StackFrame
import dev.stagecraft.service.SuffixSourceResolver

/**
 * The IDE half of §9.6's hyperlinks: turn a [StackFrame] into an open file at a line, or do nothing.
 *
 * The decision of *whether* to link is headless ([SuffixSourceResolver]): this class only supplies
 * the project's candidate files by name and opens the winner. A frame whose file is not in the
 * project returns false and the caller leaves it as plain text - a dead hyperlink is worse than no
 * hyperlink.
 */
object SourceNavigator {

    @Suppress("DEPRECATION") // FilenameIndex.getFilesByName still has no direct replacement for a bare name.
    fun navigate(project: Project, frame: StackFrame): Boolean {
        val name = frame.file.replace('\\', '/').substringAfterLast('/')
        if (name.isBlank()) return false
        val files = try {
            FilenameIndex.getFilesByName(project, name, GlobalSearchScope.projectScope(project))
                .mapNotNull { it.virtualFile }
        } catch (_: Exception) {
            return false
        }
        if (files.isEmpty()) return false

        val byPath = files.associateBy { it.path }
        val location = SuffixSourceResolver(byPath.keys.toList()).resolve(frame) ?: return false
        val file = byPath[location.path] ?: return false
        open(project, file, location.line, location.column)
        return true
    }

    fun open(project: Project, file: VirtualFile, line: Int, column: Int?) {
        OpenFileDescriptor(
            project,
            file,
            (line - 1).coerceAtLeast(0),
            ((column ?: 1) - 1).coerceAtLeast(0),
        ).navigate(true)
    }
}
