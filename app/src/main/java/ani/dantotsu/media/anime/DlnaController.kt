package ani.dantotsu.media.anime

import ani.dantotsu.okHttpClient
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Node
import org.xml.sax.InputSource

data class DlnaDevice(
    val friendlyName: String,
    val controlUrl: String,
    val serviceType: String,
)

object DlnaController {
    private const val DISCOVERY_ADDRESS = "239.255.255.250"
    private const val DISCOVERY_PORT = 1900
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    suspend fun discoverDevices(
        enableIPv6: Boolean,
        disableUpnp: Boolean,
    ): List<DlnaDevice> =
        withContext(Dispatchers.IO) {
            if (disableUpnp) return@withContext emptyList()
            val mSearch =
                """
                M-SEARCH * HTTP/1.1
                HOST: 239.255.255.250:1900
                MAN: "ssdp:discover"
                MX: 2
                ST: $AV_TRANSPORT

                """.trimIndent().replace("\n", "\r\n")

            val locations = linkedSetOf<String>()

            fun discover(targetHost: String) {
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 2200
                        val requestBytes = mSearch.toByteArray(StandardCharsets.UTF_8)
                        val requestPacket =
                            DatagramPacket(
                                requestBytes,
                                requestBytes.size,
                                InetAddress.getByName(targetHost),
                                DISCOVERY_PORT,
                            )
                        repeat(3) { socket.send(requestPacket) }
                        val buffer = ByteArray(8192)
                        while (true) {
                            val responsePacket = DatagramPacket(buffer, buffer.size)
                            socket.receive(responsePacket)
                            val response =
                                String(responsePacket.data, 0, responsePacket.length, StandardCharsets.UTF_8)
                            parseLocation(response)?.let { locations += it }
                        }
                    }
                }
            }

            discover(DISCOVERY_ADDRESS)
            if (enableIPv6) {
                discover("FF02::C")
            }

            locations.mapNotNull { parseDeviceDescription(it) }
        }

    suspend fun startPlayback(
        device: DlnaDevice,
        mediaUrl: String,
        title: String,
        controllerName: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val metadata =
                """
                &lt;DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"&gt;
                  &lt;item id="0" parentID="-1" restricted="1"&gt;
                    &lt;dc:title&gt;${escapeXml(title)}&lt;/dc:title&gt;
                    &lt;dc:creator&gt;${escapeXml(controllerName)}&lt;/dc:creator&gt;
                    &lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;
                    &lt;res protocolInfo="http-get:*:video/*:*"&gt;${escapeXml(mediaUrl)}&lt;/res&gt;
                  &lt;/item&gt;
                &lt;/DIDL-Lite&gt;
                """.trimIndent()

            val setUriBody =
                """
                <InstanceID>0</InstanceID>
                <CurrentURI>${escapeXml(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>$metadata</CurrentURIMetaData>
                """.trimIndent()
            val playBody =
                """
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
                """.trimIndent()

            val setOk = sendSoap(device, "SetAVTransportURI", setUriBody)
            val playOk = sendSoap(device, "Play", playBody)
            setOk && playOk
        }

    private fun parseLocation(ssdpResponse: String): String? =
        ssdpResponse
            .lineSequence()
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun parseDeviceDescription(location: String): DlnaDevice? {
        val request =
            Request
                .Builder()
                .url(location)
                .get()
                .build()
        val xml =
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string() ?: return null
            }

        val documentBuilder = newSecureDocumentBuilderFactory().newDocumentBuilder()
        val document = documentBuilder.parse(InputSource(StringReader(xml)))
        val friendlyName =
            document
                .getElementsByTagName("friendlyName")
                .item(0)
                ?.textContent
                ?.trim()
                .orEmpty()
                .ifEmpty { "DLNA Device" }

        val services = document.getElementsByTagName("service")
        for (index in 0 until services.length) {
            val node = services.item(index)
            val serviceType = getChildText(node, "serviceType") ?: continue
            if (!serviceType.contains("AVTransport", ignoreCase = true)) continue
            val rawControlUrl = getChildText(node, "controlURL") ?: continue
            val controlUrl = URI(location).resolve(rawControlUrl).toString()
            return DlnaDevice(friendlyName = friendlyName, controlUrl = controlUrl, serviceType = serviceType)
        }
        return null
    }

    private fun getChildText(parent: Node, name: String): String? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeName == name) return child.textContent?.trim()
        }
        return null
    }

    private fun sendSoap(
        device: DlnaDevice,
        action: String,
        actionBody: String,
    ): Boolean {
        val envelope =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="${device.serviceType}">
                  $actionBody
                </u:$action>
              </s:Body>
            </s:Envelope>
            """.trimIndent()
        val requestBody = envelope.toRequestBody("text/xml; charset=utf-8".toMediaType())
        val request =
            Request
                .Builder()
                .url(device.controlUrl)
                .post(requestBody)
                .addHeader("SOAPACTION", "\"${device.serviceType}#$action\"")
                .build()
        return okHttpClient.newCall(request).execute().use { it.isSuccessful }
    }

    private fun escapeXml(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun newSecureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory
            .newInstance()
            .apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
}
