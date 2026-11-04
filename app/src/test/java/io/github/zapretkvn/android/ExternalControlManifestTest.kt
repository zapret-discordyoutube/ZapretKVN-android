package io.github.zapretkvn.android

import io.github.zapretkvn.android.ui.ExternalControl
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.w3c.dom.Element

/**
 * Внешнее управление VPN не должно быть видно снаружи, пока владелец его не включил:
 * приёмник отключён прямо в манифесте, а включает его приложение во время работы.
 */
class ExternalControlManifestTest {
    private val androidNamespace = "http://schemas.android.com/apk/res/android"

    private fun receivers(): List<Element> {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
            .map(::File)
            .first(File::exists)
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val nodes = factory.newDocumentBuilder().parse(manifest).getElementsByTagName("receiver")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.android(name: String): String = getAttributeNS(androidNamespace, name)

    private fun controlReceiver(): Element {
        val found = receivers().firstOrNull { it.android("name") == ".vpn.ExternalControlReceiver" }
        assertNotNull("ExternalControlReceiver must be declared in the manifest", found)
        return found!!
    }

    @Test
    fun receiverIsDisabledInTheManifestSoNothingIsExportedByDefault() {
        assertEquals("false", controlReceiver().android("enabled"))
    }

    @Test
    fun receiverListensOnlyForTheThreeControlActions() {
        val actions = controlReceiver().getElementsByTagName("action")
        val declared = (0 until actions.length).map { (actions.item(it) as Element).android("name") }.toSet()
        assertEquals(ExternalControl.ACTIONS, declared)
    }

    @Test
    fun noOtherReceiverIsExported() {
        val exported = receivers().filter { it.android("exported") == "true" }.map { it.android("name") }
        assertEquals(listOf(".vpn.ExternalControlReceiver"), exported)
    }
}
