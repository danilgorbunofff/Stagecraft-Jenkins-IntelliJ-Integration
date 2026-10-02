package dev.stagecraft.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * The project's settings file: server URL, user, TLS and proxy choices, and the job pins.
 *
 * Per project, not global (§7.3 rule 3): two checkouts of two products may point at two Jenkins
 * servers, and a pinned job name is meaningless in the other one. The token is deliberately *not*
 * here - it lives in `PasswordSafe` (§7.3 rule 5, see [PasswordSafeCredentialStore]), so this file
 * is safe to read, copy and commit.
 *
 * A relative [Storage] path resolves against the project's `.idea` directory, so this is
 * `.idea/stagecraft.xml`.
 */
@Service(Service.Level.PROJECT)
@State(name = "Stagecraft", storages = [Storage("stagecraft.xml")])
class StagecraftProjectSettings : PersistentStateComponent<StagecraftState> {

    private var bean = StagecraftState()

    override fun getState(): StagecraftState = bean

    override fun loadState(state: StagecraftState) {
        bean = state
    }

    companion object {

        fun getInstance(project: Project): StagecraftProjectSettings =
            project.getService(StagecraftProjectSettings::class.java)
    }
}
