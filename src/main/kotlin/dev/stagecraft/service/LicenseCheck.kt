package dev.stagecraft.service

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.ui.LicensingFacade

/**
 * The IDE half of the paid licence (§8.3): read the confirmation stamp the IDE holds for
 * [PRODUCT_CODE] and let [LicenseVerifier] decide whether JetBrains issued it. The trial is a licence
 * too, so this is true during the 30-day trial and false once it ends without a purchase.
 *
 * `null` means the IDE's licensing facade is not initialised yet - the first seconds after start.
 * That is "unknown", and Stagecraft does not lock the user out for it.
 */
object LicenseCheck {

    /** Must equal `productDescriptor.code` in build.gradle.kts. */
    const val PRODUCT_CODE = "PSTAGECRAFT"

    fun isLicensed(): Boolean? {
        val facade = LicensingFacade.getInstance() ?: return null
        return LicenseVerifier.isValid(facade.getConfirmationStamp(PRODUCT_CODE))
    }

    /** Open the IDE's own registration dialog with Stagecraft pre-selected, from the EDT. */
    fun requestLicense(message: String) {
        ApplicationManager.getApplication().invokeLater({
            val actions = ActionManager.getInstance()
            // "RegisterPlugins" in the open-source distribution, "Register" in the commercial one.
            val register = actions.getAction("RegisterPlugins") ?: actions.getAction("Register") ?: return@invokeLater
            val context = DataContext { dataId ->
                when (dataId) {
                    "register.product-descriptor.code" -> PRODUCT_CODE
                    "register.message" -> message
                    else -> null
                }
            }
            ActionUtil.performAction(
                register,
                AnActionEvent.createEvent(context, Presentation(), "", ActionUiKind.NONE, null),
            )
        }, ModalityState.nonModal())
    }
}
