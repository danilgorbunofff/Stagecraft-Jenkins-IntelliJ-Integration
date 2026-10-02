package dev.stagecraft.ui.settings

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import dev.stagecraft.jenkins.JenkinsException
import dev.stagecraft.jenkins.JenkinsUrls
import dev.stagecraft.service.CredentialPlan
import dev.stagecraft.service.JenkinsService
import dev.stagecraft.service.StagecraftProjectSettings
import dev.stagecraft.service.credentialPlan
import javax.swing.JComponent

/**
 * *Settings > Tools > Stagecraft*.
 *
 * The server address, the user name and the three switches describe the server, so they are project
 * settings and live in the project's own `stagecraft.xml`. The secret authenticates them, so it is
 * not written there at all: it goes to the IDE's password safe through [JenkinsService.credentials].
 * That split is §7.3 rule 5 - what a project needs to find its server survives a restart, the thing
 * that proves who is asking never does.
 *
 * [apply] validates before it writes, so an address that cannot become a Jenkins URL is reported in
 * the settings dialog instead of being stored and discovered later as a mysterious failure in the
 * tool window.
 *
 * The token itself never crosses the EDT. [reset] asks [JenkinsService] for the stored token and
 * fills the field when the answer arrives, [apply] hands the write to the same I/O thread, and
 * [isModified] compares the field with the copy that read left behind - because the platform calls
 * [isModified] on the EDT on every keystroke, and `PasswordSafe` guards both `get` and `set` with
 * `SlowOperations` (§7.3 rule 2).
 */
class StagecraftConfigurable(private val project: Project) : Configurable {

    private val settings = StagecraftProjectSettings.getInstance(project)
    private val service = JenkinsService.getInstance(project)

    /**
     * The token the password safe holds for the configured server, as of the last read. Empty until
     * that read lands, which is why a blank field has to mean "keep what is stored".
     */
    private var savedToken = ""

    /** Whether [savedToken] is what the safe really holds, so the help text can say so. */
    private var savedTokenKnown = false

    private val serverUrl = JBTextField()
    private val user = JBTextField()
    private val secret = JBPasswordField()
    private val secretIsPassword = JBCheckBox("The secret above is a password, not an API token")
    private val trustCertificate = JBCheckBox("Trust this server's HTTPS certificate without asking the IDE")
    private val useProxy = JBCheckBox("Use the IDE's proxy settings")
    private val tokenHelp = JBLabel()
    private val tokenLink = HyperlinkLabel()

    private var component: JComponent? = null

    override fun getDisplayName(): String = "Stagecraft"

    override fun getPreferredFocusedComponent(): JComponent = serverUrl

    override fun createComponent(): JComponent {
        var built = component
        if (built == null) {
            built = buildPanel()
            component = built
            reset()
        }
        return built
    }

    /**
     * Fills the form from the project settings, and asks for the stored token rather than reading it:
     * a read here would run on the EDT, where the password safe refuses to be touched. The field is
     * filled when the answer arrives, and a user who starts typing before that is never overwritten.
     */
    override fun reset() {
        val state = settings.state
        serverUrl.text = state.serverUrl
        user.text = state.user
        secret.text = ""
        secretIsPassword.isSelected = state.secretIsPassword
        trustCertificate.isSelected = state.trustCertificate
        useProxy.isSelected = state.useProxy
        savedToken = ""
        savedTokenKnown = false
        updateTokenHelp()
        if (state.serverUrl.isNotEmpty()) {
            service.readToken(state.serverUrl) { token ->
                savedToken = token
                savedTokenKnown = true
                if (secret.password.isEmpty()) secret.text = token
                updateTokenHelp()
            }
        }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        val enteredSecret = String(secret.password)
        return serverUrl.text.trim() != state.serverUrl ||
            user.text.trim() != state.user ||
            // A blank field means "keep the stored token", so it is not a change - and the field is
            // blank until the read in [reset] lands, so comparing it with the stored token instead
            // would light Apply up on its own, and the comparison would decrypt the safe on the EDT.
            (enteredSecret.isNotEmpty() && enteredSecret != savedToken) ||
            secretIsPassword.isSelected != state.secretIsPassword ||
            trustCertificate.isSelected != state.trustCertificate ||
            useProxy.isSelected != state.useProxy
    }

    override fun apply() {
        val state = settings.state
        val enteredUrl = serverUrl.text.trim()
        val url = if (enteredUrl.isEmpty()) "" else try {
            JenkinsUrls.normalizeBase(enteredUrl)
        } catch (malformed: JenkinsException.Malformed) {
            throw ConfigurationException(
                "\"$enteredUrl\" is not a Jenkins address Stagecraft can use. Give the server's base " +
                    "URL, for example https://ci.example.com/",
            )
        }
        val name = user.text.trim()
        if (url.isNotEmpty() && name.isEmpty()) {
            throw ConfigurationException("Give the user name the token belongs to.")
        }
        val previousUrl = state.serverUrl
        val enteredSecret = String(secret.password)
        val plan = credentialPlan(
            previousUrl = previousUrl,
            previousUser = state.user,
            url = url,
            user = name,
            enteredToken = enteredSecret,
        )
        val changes = when (plan) {
            is CredentialPlan.Changes -> plan
            CredentialPlan.RequiresToken ->
                throw ConfigurationException("Give an API token for $name on $url.")
        }

        state.serverUrl = url
        state.user = name
        state.secretIsPassword = secretIsPassword.isSelected
        state.trustCertificate = trustCertificate.isSelected
        state.useProxy = useProxy.isSelected

        // The password safe refuses `set` on the EDT as well, and the reload has to follow the write
        // for the token that was just stored to be the one the next request uses.
        service.updateCredentials { write(changes) }

        // The field only ever holds what the user typed, so it is emptied rather than refilled from
        // the safe: apply must not read it either.
        serverUrl.text = url
        user.text = name
        secret.text = ""
        savedToken = when {
            enteredSecret.isNotEmpty() -> enteredSecret
            url == previousUrl -> savedToken
            else -> ""
        }
        savedTokenKnown = true
        updateTokenHelp()
    }

    /**
     * Runs on [JenkinsService]'s I/O thread, where the password safe is allowed to be touched. A
     * credential that cannot be written must not take the dialog down after it has already closed,
     * so the failure is logged and the reload that follows reports what the server actually says.
     */
    private fun write(changes: CredentialPlan.Changes) {
        changes.forget?.let { service.credentials.clear(it) }
        changes.store?.let { service.credentials.save(it.serverUrl, it.user, it.token) }
    }

    override fun disposeUIResources() {
        component = null
        // The dialog is gone, so do not keep a copy of the token alive in it.
        savedToken = ""
        savedTokenKnown = false
    }

    private fun buildPanel(): JComponent {
        tokenHelp.setAllowAutoWrapping(true)
        tokenHelp.foreground = UIUtil.getInactiveTextColor()
        tokenLink.addHyperlinkListener { openTokenPage() }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Jenkins server URL:", serverUrl)
            .addLabeledComponent("User name:", user)
            .addLabeledComponent("API token:", secret)
            .addComponent(secretIsPassword)
            .addComponent(trustCertificate)
            .addComponent(useProxy)
            .addVerticalGap(8)
            .addComponent(tokenLink)
            .addComponent(tokenHelp)
            .addComponentFillVertically(JBLabel(), 0)
            .panel
    }

    private fun updateTokenHelp() {
        val url = tokenUrl()
        tokenLink.isVisible = url != null
        if (url != null) {
            tokenLink.setHyperlinkTarget(url)
            tokenLink.setHyperlinkText("Open $url")
        }
        tokenHelp.text = if (savedTokenKnown && savedToken.isNotEmpty()) {
            "A token for this server is in the IDE's password safe. Leave the field blank to keep it, " +
                "or type a new one to replace it."
        } else {
            "Stagecraft authenticates with an API token: open your Jenkins profile, then Configure, " +
                "then API Token, then Add new Token. The token is kept in the IDE's password safe, " +
                "never in the project's settings file."
        }
    }

    private fun openTokenPage() {
        val url = tokenUrl() ?: return
        BrowserUtil.browse(url)
    }

    /** Jenkins hands out API tokens at `{base}/me/configure`. */
    private fun tokenUrl(): String? {
        val text = serverUrl.text.trim()
        if (text.isEmpty()) return null
        val base = try {
            JenkinsUrls.normalizeBase(text)
        } catch (malformed: JenkinsException.Malformed) {
            return null
        }
        return base + "me/configure"
    }
}
