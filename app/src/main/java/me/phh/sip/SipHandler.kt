//SPDX-License-Identifier: GPL-2.0
package me.phh.sip

import android.annotation.SuppressLint
import android.content.Context
import android.media.*
import android.net.*
import android.os.Handler
import android.os.HandlerThread
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.PhoneNumberUtils
import android.telephony.Rlog
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.phh.ims.Rnnoise
import java.io.*
import java.net.*
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private data class smsHeaders(
    val dest: String,
    val callId: String,
    val cseq: String,
)

class SipHandler(val ctxt: Context) {
    companion object {
        private const val TAG = "PHH SipHandler"
    }

    val myHandler = Handler(HandlerThread("PhhMmTelFeature").apply { start() }.looper)
    val myExecutor = Executor { p0 -> myHandler.post(p0) }

    private val subscriptionManager: SubscriptionManager
    private val telephonyManager: TelephonyManager
    private val connectivityManager: ConnectivityManager
    private val ipSecManager: IpSecManager
    init {
        subscriptionManager = ctxt.getSystemService(SubscriptionManager::class.java)
        telephonyManager = ctxt.getSystemService(TelephonyManager::class.java)
        connectivityManager = ctxt.getSystemService(ConnectivityManager::class.java)
        ipSecManager = ctxt.getSystemService(IpSecManager::class.java)
    }

    @SuppressLint("MissingPermission")
    private val activeSubscription = subscriptionManager.activeSubscriptionInfoList[0]
    private val imei = telephonyManager.getDeviceId(activeSubscription.simSlotIndex)
    private val subId = activeSubscription.subscriptionId
    private val mcc = telephonyManager.simOperator.substring(0 until 3)
    private var mnc =
        telephonyManager.simOperator.substring(3).let { if (it.length == 2) "0$it" else it }
    private val imsi = telephonyManager.subscriberId

    /* Carrier specific settings
     */
    val isControlSocketUdp = when(mcc + mnc) {
        "450006" -> true // LG U+ can only do UDP
        "450008" -> true // KT terminals register over UDP
        "450005" -> true // SKT terminals register over UDP
        "208010" -> true // 20810 can do TCP and UDP. use this for testing
        else -> false
    }
    val forceSmsc = when(mcc + mnc) {
        "450006" -> "821080010585" // LG U+
        else -> null
    }
    // KT P-CSCFs take the terminals on 5080, the stock terminals send the TTA user agent
    val pcscfPort = when(mcc + mnc) {
        "450008" -> 5080
        else -> 5060
    }
    init {
        // the device may set the user agent of its stock firmware
        val ua = android.os.SystemProperties.get("persist.vendor.ims.user_agent", "")
        // Default to the user agents of the stock ef63 firmwares (the property is limited to 92 bytes)
        val carrierUa = when (mcc + mnc) {
            "450008" -> "KT-client/VoLTE+PSVT;IM-A910K;Device_Type=Android_Phone;Net_Type=LTE;SW_Ver=1.31"
            // the TTA user agent of the later stock SKT firmwares, the network takes it
            "450005" -> "TTA-VoLTE/2.0 IM-A910S/S0224129 Device_Type/Android_Phone SKT"
            "450006" -> "LGUPlus-client/LTE_AT1.0+LTE_VT2.0+LTE_MSG1.0;IM-A910L;ver127;Device_Type=Android_Phone;305_use-proxy;md=1"
            else -> null
        }
        if (ua.isNotEmpty()) sipUserAgent = ua
        else if (carrierUa != null) sipUserAgent = carrierUa
        // The Korean carriers register their terminals without IPsec
        sipCarrierNoIpsec = carrierUa != null
        if (carrierUa != null) enableVolteForCarrier()
    }
    val unregisterAllFirst = android.os.SystemProperties.getBoolean("persist.vendor.ims.unregister_all", false)
    /*
     * The carrier configs of AOSP do not enable VoLTE for the Korean carriers.
     * Enable it for this subscription, like the stock firmware does.
     */
    private fun enableVolteForCarrier() {
        try {
            val ccm = ctxt.getSystemService(android.telephony.CarrierConfigManager::class.java)
            val key = android.telephony.CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL
            if (ccm.getConfigForSubId(subId)?.getBoolean(key) != true) {
                Rlog.d(TAG, "Enabling $key for sub $subId")
                ccm.overrideConfig(subId, android.os.PersistableBundle().apply { putBoolean(key, true) }, true)
            }
            val mmTel = android.telephony.ims.ImsMmTelManager.createForSubscriptionId(subId)
            if (!mmTel.isAdvancedCallingSettingEnabled) {
                Rlog.d(TAG, "Enabling the advanced calling setting for sub $subId")
                mmTel.setAdvancedCallingSettingEnabled(true)
            }
        } catch (t: Throwable) {
            Rlog.w(TAG, "Could not enable VoLTE in the carrier config", t)
        }
    }

    // Sess is more secure so default to it
    val requireNonsessAka = when(mcc + mnc) {
        "450006" -> true
        else -> false
    }

    //private val realm = "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    // The ISIM identities when the card has them: some carriers (KT) only know those
    private val isimTm = telephonyManager.createForSubscriptionId(subId)
    private val isimImpi = try { isimTm.isimImpi?.takeIf { it.isNotEmpty() } } catch (t: Throwable) { null }
    private val isimImpu = try {
        isimTm.isimImpu?.let { impus ->
            // The emergency identity may come first
            impus.firstOrNull { !it.isNullOrEmpty() && !it.contains("@sos.") } ?: impus.firstOrNull { !it.isNullOrEmpty() }
        }
    } catch (t: Throwable) { null }
    private val isimDomain = try { isimTm.isimDomain?.takeIf { it.isNotEmpty() } } catch (t: Throwable) { null }
    // a home domain set by the device wins over the ISIM one (e.g. ims.kt.com for the
    // terminals KT provisioned before TTA)
    // Home domain of the stock terminals (VT.apk of the stock ef63 firmwares), LG U+ uses the ISIM
    private val domainOverride = android.os.SystemProperties.get("persist.vendor.ims.domain", "").takeIf { it.isNotEmpty() }
        ?: when (mcc + mnc) {
            "450008" -> "ims.kt.com"
            "450005" -> "sktims.net"
            else -> null
        }
    private val realm = domainOverride ?: isimDomain ?: "ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
    private val user = if (domainOverride != null) "$imsi@$realm" else isimImpi ?: "$imsi@$realm"
    // public identity, without the sip: scheme
    private val publicUser =
        if (domainOverride != null && isimImpu != null)
            isimImpu.removePrefix("sip:").substringBefore("@") + "@" + realm
        else isimImpu?.removePrefix("sip:") ?: user
    // user part of the contact: the public one when it comes from the ISIM, like the stock terminals
    private val contactUser = if (isimImpu != null) publicUser.substringBefore("@") else imsi
    init {
        useIsimForAka = isimImpi != null
        Rlog.d(TAG, "Identities from the ${if (isimImpi != null) "ISIM" else "IMSI"}")
    }
    private val emptyAkaDigest =
        """Digest username="$user",realm="$realm",nonce="",uri="sip:$realm",response="",algorithm=AKAv1-MD5"""
    private var akaDigest = emptyAkaDigest

    // Answer a 401 with a fresh AKA digest
    private fun akaDigestFor(challenge: SipResponse): String {
        val (_, params) = challenge.headers["www-authenticate"]!![0].getAuthValues()
        val nonce = params["nonce"]!!
        val aka = sipAkaChallenge(telephonyManager, nonce)
        // Without qop in the challenge, the response has no cnonce/nc (RFC 2617, SKT)
        return if (requireNonsessAka || params["qop"] == null)
            SipAkaDigest(user = user, realm = realm, uri = "sip:$realm", nonceB64 = nonce,
                opaque = params["opaque"], akaResult = aka).toString()
        else
            SipAkaDigestSess(user = user, realm = realm, uri = "sip:$realm", nonceB64 = nonce,
                opaque = params["opaque"], akaResult = aka).toString()
    }

    fun generateCallId(): SipHeadersMap {
        val callId = randomBytes(12).toHex()
        return mapOf("call-id" to listOf(callId))
    }
    private var registerCounter = 1
    private var registerHeaders =
        """
        From: <sip:$publicUser>
        To: <sip:$publicUser>
        """.toSipHeadersMap() + generateCallId()
    private var commonHeaders = "".toSipHeadersMap()
    private var contact = ""
    // P-Access-Network-Info of the last registration
    private var lastPani: String? = null
    private fun accessNetworkInfo(): String {
        val pani = try {
            currentPani(telephonyManager.getAllCellInfo())
        } catch (t: Throwable) { null }
        if (pani != null) lastPani = pani
        return lastPani ?: "3GPP-E-UTRAN-FDD"
    }

    // Access network of the serving LTE cell (TS 24.229 7.2A.4), like the stock terminals:
    // SKT locates the callers of 114 and the like with it, and some networks only
    // terminate the calls over IMS for terminals they know are on LTE
    @SuppressLint("MissingPermission")
    private fun currentPani(cellInfoList: List<android.telephony.CellInfo>?): String? {
        val lteCells = cellInfoList.orEmpty().filterIsInstance<CellInfoLte>()
        // the legacy RIL does not flag the registered cell
        val id = (lteCells.firstOrNull { it.isRegistered } ?: lteCells.firstOrNull())?.cellIdentity ?: return null
        if (id.tac == Int.MAX_VALUE || id.ci == Int.MAX_VALUE) return null
        // the legacy RIL does not report the PLMN of the cell, use the one of the SIM;
        // phh pads the MNC to 3 digits (ims.mnc005...), the cell id keeps its own length
        val mccStr = id.mccString ?: mcc
        val mncStr = id.mncString ?: mnc.trimStart('0').padStart(2, '0')
        return "3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=$mccStr$mncStr${"%04x".format(id.tac)}${"%07x".format(id.ci)}"
    }
    private var mySip = ""
    private var myTel = ""

    // too many lateinit, bad separation?
    lateinit private var localAddr: InetAddress
    lateinit private var pcscfAddr: InetAddress

    data class SipIpsecSettings(
        val clientSpiC: IpSecManager.SecurityParameterIndex,
        val clientSpiS: IpSecManager.SecurityParameterIndex,
        val serverSpiC: IpSecManager.SecurityParameterIndex? = null,
        val serverSpiS: IpSecManager.SecurityParameterIndex? = null,
    )
    lateinit var ipsecSettings: SipIpsecSettings

    lateinit private var network: Network

    lateinit private var plainSocket: SipConnection
    lateinit private var socket: SipConnection
    lateinit private var serverSocket: SipConnectionTcpServer
    lateinit private var serverSocketUdp: SipConnectionUdpServer

    // Without IPsec, the P-CSCF only accepts messages coming from the registered Contact port,
    // so send through the UDP server socket instead of the client one.
    private val sigWriter: java.io.OutputStream = object: java.io.OutputStream() {
        override fun write(p0: Int) {
            write(byteArrayOf(p0.toByte()))
        }
        override fun write(p0: ByteArray) {
            if (isControlSocketUdp && !sipUseIpsec) {
                serverSocketUdp.socket.channel.send(java.nio.ByteBuffer.wrap(p0),
                    java.net.InetSocketAddress(pcscfAddr, pcscfPort))
            } else {
                socket.gWriter().write(p0)
            }
        }
    }
    private var reliableSequenceCounter = 67

    private val cbLock = ReentrantLock()
    private var requestCallbacks: Map<SipMethod, ((SipRequest) -> Int)> = mapOf()
    private var responseCallbacks: Map<String, ((SipResponse) -> Boolean)> = mapOf()
    private var imsReady = false
    var imsReadyCallback: (() -> Unit)? = null
    var imsFailureCallback: (() -> Unit)? = null
    var onSmsReceived: ((Int, String, ByteArray) -> Unit)? = null
    var onSmsStatusReportReceived: ((Int, String, ByteArray) -> Unit)? = null
    var onIncomingCall: ((handle: Object, from: String, extras: Map<String, String>) -> Unit)? =
        null
    // Outgoing call state, for the framework call session
    var onOutgoingProgress: (() -> Unit)? = null
    var onOutgoingStarted: (() -> Unit)? = null
    var onCancelledCall: ((handle: Object, from: String, extras: Map<String, String>) -> Unit)? =
        null
    private val smsLock = ReentrantLock()
    private var smsToken = 0
    private val smsHeadersMap = mutableMapOf<Int, smsHeaders>()

    fun setRequestCallback(method: SipMethod, cb: (SipRequest) -> Int) {
        cbLock.withLock { requestCallbacks += (method to cb) }
    }
    fun setResponseCallback(callId: String, cb: (SipResponse) -> Boolean) {
        cbLock.withLock { responseCallbacks += (callId to cb) }
    }

    fun parseMessage(reader: SipReader, writer: OutputStream): Boolean {
        val msg =
            try {
                reader.parseMessage()
            } catch (e: SocketException) {
                Rlog.d(TAG, "Got exception $e")
                if ("$e" == "java.net.SocketException: Try again") {
                    // we sometimes seem to get EAGAIN
                    return true
                }
                throw e
            }
        Rlog.d(TAG, "RObject() message $msg")
        if (msg is SipResponse) {
            return handleResponse(msg)
        }
        if (msg !is SipRequest) {
            // invalid message, stop trying
            Rlog.d(TAG, "Got invalid message! Closing socket (except main)")
            return false
        }

        val requestCb = cbLock.withLock { requestCallbacks[msg.method] }
        var status = 200
        // XXX default requestCb = notification?
        if (requestCb != null) {
            status = requestCb(msg)
        }
        if(status == 0) return true
        val reply =
            SipResponse(
                statusCode = status,
                statusString = if (status == 200) "OK" else if (status == 100) "Trying" else "ERROR",
                headersParam =
                    msg.headers.filter { (k, _) ->
                        k in listOf("cseq", "via", "from", "to", "call-id")
                    }
            )
        Rlog.d(TAG, "Replying back with $reply")
        synchronized(writer) { writer.write(reply.toByteArray()) }

        return true
    }

    fun handleResponse(response: SipResponse): Boolean {
        val callId = response.headers["call-id"]?.get(0)
        if (callId == null) {
            // message without call-id should never happen, close connection
            return false
        }
        val responseCb = cbLock.withLock { responseCallbacks[callId] }
        if (responseCb == null) {
            // nothing to do
            return true
        }

        if (responseCb(response)) {
            // remove callback if done
            cbLock.withLock { responseCallbacks -= callId }
        }
        return true
    }

    var abandonnedBecauseOfNoPcscf = false
    fun connect() {
        abandonnedBecauseOfNoPcscf = false
        Rlog.d(TAG, "Trying to connect to SIP server")
        val lp = connectivityManager.getLinkProperties(network)
        Rlog.d(TAG, "Got link properties $lp")
        if (lp == null) {
            // The network went away meanwhile (airplane mode...), the next one restarts us
            Rlog.w(TAG, "IMS network $network is gone, waiting for the next one")
            return
        }
        val pcscfs = (lp.javaClass.getMethod("getPcscfServers").invoke(lp) as List<*>).sortedBy { if(it is Inet6Address) 0 else 1 }
        val pcscf = if (pcscfs.isNotEmpty()) {
            pcscfs[0] as InetAddress
        } else {
            Rlog.w(TAG, "Had no Pcscf Sever defined, aborting")
            // Legacy RILs cannot report the P-CSCF of the PDN: the device may read it
            // from the modem and put it in a property
            val propPcscf = android.os.SystemProperties.get("persist.vendor.ims.pcscf", "")
                .split(" ", ",").filter { it.isNotEmpty() }.mapNotNull {
                    try { InetAddress.getByName(it) } catch (t: Throwable) { null }
                }.filter { addr -> lp.linkAddresses.any { (it.address is Inet6Address) == (addr is Inet6Address) } }
            Rlog.d(TAG, "P-CSCF from the modem $propPcscf")
            // else use the ones of the ISIM
            val isimPcscf = try {
                telephonyManager.createForSubscriptionId(activeSubscription.subscriptionId).isimPcscf?.toList() ?: emptyList()
            } catch (t: Throwable) {
                Rlog.w(TAG, "Failed to read the P-CSCF of the ISIM", t)
                emptyList<String>()
            }
            Rlog.d(TAG, "ISIM P-CSCF $isimPcscf")
            val isimAddr = isimPcscf.filter { it.isNotEmpty() }.mapNotNull {
                try { network.getAllByName(it).toList() } catch (t: Throwable) { Rlog.w(TAG, "Failed to resolve $it"); null }
            }.flatten().sortedBy { if (it is Inet6Address) 0 else 1 }
                // only the families we have an address of on the IMS PDN
                .filter { addr -> lp.linkAddresses.any { (it.address is Inet6Address) == (addr is Inet6Address) } }
            Rlog.d(TAG, "ISIM P-CSCF resolved to $isimAddr")
            val t = try { InetAddress.getByName("ims.mnc${mnc}.mcc${mcc}.pub.3gppnetwork.org") } catch(t: Throwable) { null }
            val t2 = try { InetAddress.getByName("ims.mnc${mnc}.mcc${mcc}.3gppnetwork.org") } catch(t: Throwable) { null }
            Rlog.d(TAG, "Resolved $t and $t2")
            //imsFailureCallback?.invoke()
            //abandonnedBecauseOfNoPcscf = true
            //return
            if (propPcscf.isNotEmpty()) {
                propPcscf[0]
            } else if (isimAddr.isNotEmpty()) {
                isimAddr[0]
            } else {
            // For annoying broken Vince's RIL that can't report Pcscf
            InetAddress.getByName("2001:4c48:400:100::2") //,/2001:4c48:400::3:2
            }
        }

        localAddr = lp.linkAddresses.map { it.address }.sortedBy { if(it is Inet6Address) 0 else 1 }.first()
        pcscfAddr = pcscf

        Rlog.w(TAG, "Connecting with address $localAddr to $pcscfAddr")

        val clientSpiC = ipSecManager.allocateSecurityParameterIndex(localAddr)
        val clientSpiS = ipSecManager.allocateSecurityParameterIndex(localAddr, clientSpiC.spi + 1)
        ipsecSettings = SipIpsecSettings(
            clientSpiS = clientSpiS,
            clientSpiC = clientSpiC)

        plainSocket = if (isControlSocketUdp)
            SipConnectionUdp(network, pcscfAddr, localAddr)
        else
            SipConnectionTcp(network, pcscfAddr, localAddr)
        plainSocket.connect(pcscfPort)
        socket = if(plainSocket is SipConnectionTcp)
                SipConnectionTcp(network, pcscfAddr, plainSocket.gLocalAddr())
            else
                SipConnectionUdp(network, pcscfAddr, plainSocket.gLocalAddr())
        serverSocket =
            SipConnectionTcpServer(network, pcscfAddr, plainSocket.gLocalAddr(), socket.gLocalPort() + 1)
        serverSocketUdp =
            SipConnectionUdpServer(network, pcscfAddr, plainSocket.gLocalAddr(), socket.gLocalPort() + 1)

        Rlog.d(TAG, "Src port is ${socket.gLocalPort()}, TCP server port is ${serverSocket.localPort}, UDP server port is ${serverSocketUdp.localPort}")
        updateCommonHeaders(plainSocket)
        register(plainSocket.gWriter())
        val plainRegReply =
            if (plainSocket is SipConnectionTcp) {
                plainSocket.gReader().parseMessage()
            } else {
                // In some IMS servers, in UDP send mode, message might come back to plainSocket or to serverSocketUdp
                if (select(listOf(serverSocketUdp.getChannel(), plainSocket.getChannel())) == 0)
                    serverSocketUdp.gReader().parseMessage()
                else
                    plainSocket.gReader().parseMessage()

            }
        Rlog.d(TAG, "Received $plainRegReply")
        plainSocket.close()
        if (plainRegReply !is SipResponse || plainRegReply.statusCode != 401) {
            Rlog.w(TAG, "Didn't get expected response from initial register, aborting")
            imsFailureCallback?.invoke()
            return
        }

        val (wwwAuthenticateType, wwwAuthenticateParams) =
            plainRegReply.headers["www-authenticate"]!![0].getAuthValues()
        require(wwwAuthenticateType == "Digest")
        val nonceB64 = wwwAuthenticateParams["nonce"]!!

        Rlog.d(TAG, "Requesting AKA challenge")
        val akaResult = sipAkaChallenge(telephonyManager, nonceB64)
        akaDigest =
            if(requireNonsessAka || wwwAuthenticateParams["qop"] == null)
                SipAkaDigest(
                    user = user,
                    realm = realm,
                    uri = "sip:$realm",
                    nonceB64 = nonceB64,
                    opaque = wwwAuthenticateParams["opaque"],
                    akaResult = akaResult
                )
                .toString()
            else
            SipAkaDigestSess(
                    user = user,
                    realm = realm,
                    uri = "sip:$realm",
                    nonceB64 = nonceB64,
                    opaque = wwwAuthenticateParams["opaque"],
                    akaResult = akaResult
                )
                .toString()

        var portS = pcscfPort
        // Check if there is a security-server header in the reply
        if(plainRegReply.headers.containsKey("security-server")) {
            val securityServer = plainRegReply.headers["security-server"]!!
            commonHeaders += ("security-verify" to securityServer)
            registerHeaders += ("security-verify" to securityServer)
            val supported_alg = listOf("hmac-sha-1-96", "hmac-md5-96")
            val supported_ealg = listOf("aes-cbc", "null")
            val (securityServerType, securityServerParams) =
                securityServer
                    .map { it.getParams() }
                    .filter {
                        val thisEAlg = it.component2()["ealg"] ?: "null"
                        supported_ealg.contains(thisEAlg)
                    }
                    .filter { supported_alg.contains(it.component2()["alg"]) }
                    .sortedByDescending { it.component2()["q"]?.toFloat() ?: 0.toFloat() }[0]
            require(securityServerType == "ipsec-3gpp")

            portS = securityServerParams["port-s"]!!.toInt()
            // spi string is 32 bit unsigned, but ipSecManager wants an int...
            val spiS = securityServerParams["spi-s"]!!.toUInt().toInt()
            val serverSpiS = ipSecManager.allocateSecurityParameterIndex(pcscfAddr, spiS)

            val spiC = securityServerParams["spi-c"]!!.toUInt().toInt()
            val serverSpiC = ipSecManager.allocateSecurityParameterIndex(pcscfAddr, spiC)

            ipsecSettings = SipIpsecSettings(
                clientSpiS = clientSpiS,
                clientSpiC = clientSpiC,
                serverSpiC = serverSpiC,
                serverSpiS = serverSpiS)

            val ealg = securityServerParams["ealg"] ?: "null"
            val (alg, hmac_key) = if (securityServerParams["alg"] == "hmac-sha-1-96") {
                // sha-1-96 mac key must be 160 bits, pad ik
                IpSecAlgorithm.AUTH_HMAC_SHA1 to akaResult.ik + ByteArray(4)
            } else {
                IpSecAlgorithm.AUTH_HMAC_MD5 to akaResult.ik
            }
            val ipSecBuilder =
                IpSecTransform.Builder(ctxt)
                    .setAuthentication(IpSecAlgorithm(alg, hmac_key, 96))
                    .also {
                        if (ealg == "aes-cbc") {
                            it.setEncryption(IpSecAlgorithm(IpSecAlgorithm.CRYPT_AES_CBC, akaResult.ck))
                        }
                    }

            val serverInTransform = ipSecBuilder.buildTransportModeTransform(pcscfAddr, clientSpiS)
            val serverOutTransform = ipSecBuilder.buildTransportModeTransform(localAddr, serverSpiC)
            socket.enableIpsec(ipSecBuilder, ipSecManager, clientSpiC, serverSpiS)
            serverSocket.enableIpsec(ipSecManager, serverInTransform, serverOutTransform)
            serverSocketUdp.enableIpsec(ipSecManager, serverInTransform, serverOutTransform)
        }
        socket.connect(portS)
        updateCommonHeaders(socket)
        val regStream =
            if (socket is SipConnectionTcp) socket.gReader()
            // without IPsec the REGISTER goes out of the Contact port, and so come the replies
            else if (socket is SipConnectionUdp && !sipUseIpsec) serverSocketUdp.gReader()
            else if (socket is SipConnectionUdp) serverSocketUdp.gReader()
            else socket.gReader()
        // The network may already send requests (MESSAGE...) while we wait for the reply,
        // skip them, they are retransmitted once we listen
        val regReader = object {
            fun parseMessage(): SipMessage? {
                for (i in 0 until 8) {
                    val m = regStream.parseMessage()
                    if (m !is SipRequest) return m
                    Rlog.d(TAG, "Ignoring ${m.firstLine} while registering")
                }
                return null
            }
        }
        // The address changes with every IMS PDN on IPv6 networks, and the network
        // may keep terminating calls on a stale binding: drop all of them first
        if (unregisterAllFirst) {
            register(unregisterAll = true)
            val unregReply = regReader.parseMessage()
            Rlog.d(TAG, "Unregister all bindings: ${(unregReply as? SipResponse)?.statusCode}")
            // The network does not take the same credentials twice: get a new challenge
            akaDigest = emptyAkaDigest
            register()
            val challenge = regReader.parseMessage()
            if (challenge is SipResponse && challenge.statusCode == 401)
                akaDigest = akaDigestFor(challenge)
            else
                Rlog.w(TAG, "No new challenge after the unregister: $challenge")
        }
        register()
        val regReply = regReader.parseMessage()!!
        Rlog.d(TAG, "Received $regReply")

        if (regReply !is SipResponse || regReply.statusCode != 200) {
            Rlog.w(TAG, "Could not connect, aborting SIP")
            imsFailureCallback?.invoke()
            return
        }

        setResponseCallback(registerHeaders["call-id"]!![0], ::registerCallback)
        setRequestCallback(SipMethod.MESSAGE, ::handleSms)
        setRequestCallback(SipMethod.INVITE, ::handleCall)
        setRequestCallback(SipMethod.PRACK, ::handlePrack)
        setRequestCallback(SipMethod.CANCEL, ::handleCancel)
        setRequestCallback(SipMethod.BYE, ::handleCancel)
        setRequestCallback(SipMethod.ACK, ::handleAck)
        setRequestCallback(SipMethod.UPDATE, ::handleUpdate)
        handleResponse(regReply)

        // two ways we'll get incoming messages:
        // - reply to normal socket (just read forever)
        // - connection to server socket
        // start both in threads as we're only called here from network
        // callback from which it's better to return
        CoroutineScope(Dispatchers.IO).launch {
            // XXX catch and reconnect on 'java.net.SocketException: Software caused connection
            // abort' ?
            try {
                while (true) {
                    parseMessage(socket.gReader(), socket.gWriter())
                }
            } catch(t: Throwable) {
                Rlog.d(TAG, "Got exception in main/control socket", t)
            }
            socket.close()
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                while (true) {
                    // XXX catch and reconnect on 'java.net.SocketException: Socket closed' ?
                    val client = serverSocket.serverSocket.accept()
                    // there can only be a single client at a time because
                    // both source and destination ports are fixed
                    val reader = client.getInputStream().sipReader()
                    val writer = client.getOutputStream()
                    while (parseMessage(reader, writer)) { }
                    client.close()
                }
            } catch(t: Throwable) {
                Rlog.d(TAG, "Got exception in TCP server socket", t)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bufferIn = ByteArray(128 * 1024)
                val dgramPacketIn = DatagramPacket(bufferIn, bufferIn.size)
                val writer = ByteArrayOutputStream()
                while (true) {
                    dgramPacketIn.length = bufferIn.size
                    serverSocketUdp.socket.receive(dgramPacketIn)
                    Rlog.d(TAG, "Received dgram packet")
                    val baIs = ByteArrayInputStream(dgramPacketIn.data, dgramPacketIn.offset, dgramPacketIn.length)
                    val reader = baIs.sipReader()
                    while (parseMessage(reader, writer)) { }
                    val writerOut = writer.toByteArray()
                    if (writerOut.isNotEmpty()) {
                        val dgramPacketOut = DatagramPacket(writerOut, writerOut.size, dgramPacketIn.address, dgramPacketIn.port)
                        serverSocketUdp.socket.send(dgramPacketOut)
                    }
                    writer.reset()
                }
            } catch(t: Throwable) {
                Rlog.d(TAG, "Got exception in UDP server socket", t)
            }
        }
    }

    fun getVolteNetwork() {
        // TODO add something similar for VoWifi ipsec tunnel?
        Rlog.d(TAG, "Requesting IMS network")
        connectivityManager.requestNetwork(NetworkRequest.Builder()
            //.addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            //.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            //.setNetworkSpecifier(subId.toString())
            .addCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            //.addCapability(NetworkCapabilities.NET_CAPABILITY_MMTEL)
            .build(),
            object : ConnectivityManager.NetworkCallback() {
                override fun onUnavailable() {
                    Rlog.d(TAG, "IMS network unavailable")
                }

                override fun onLost(network: Network) {
                    Rlog.d(TAG, "IMS network lost")
                }

                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    Rlog.d(TAG, "IMS network blocked status changed $blocked")
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    Rlog.d(TAG, "IMS network capabilities changed $networkCapabilities")
                }

                override fun onLosing(network: Network, maxMsToLive: Int) {
                    Rlog.d(TAG, "IMS network losing")
                }

                override fun onLinkPropertiesChanged(
                    _network: Network,
                    linkProperties: LinkProperties
                ) {
                    Rlog.d(TAG, "IMS network link properties changed $linkProperties")
                    val pcscfs = linkProperties!!.javaClass.getMethod("getPcscfServers").invoke(linkProperties) as List<*>
                    Rlog.d(TAG, "Got pcscfs $pcscfs")
                    if(pcscfs.isNotEmpty() && abandonnedBecauseOfNoPcscf) {
                        connect()
                    }
                }

                override fun onAvailable(_network: Network) {
                    Rlog.d(TAG, "Got IMS network.")
                    if (!this@SipHandler::network.isInitialized) {
                        network = _network
                        thread {
                            Thread.sleep(4000)
                            try {
                                connect()
                            } catch (t: Throwable) {
                                // e.g. the network went away while registering
                                Rlog.e(TAG, "Failed to connect to the IMS network", t)
                            }
                        }
                    } else if (_network != network) {
                        // The sockets, timers and dialogs are all bound to the old
                        // network: start again from scratch, telephony binds the
                        // service again
                        Rlog.w(TAG, "IMS network changed from $network to $_network, restarting")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    } else {
                        Rlog.d(TAG, "... don't try anything")
                    }
                }
            }
        )
    }

    fun updateCommonHeaders(socket: SipConnection) {
        // Note: we are giving serverSocket (TCP) port, but TCP and UDP servers use the same port
        val local = if(socket.gLocalAddr() is Inet6Address)
            "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
        else
            "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"

        val sipInstance = "<urn:gsma:imei:${imei.substring(0,8)}-${imei.substring(8,14)}-0>"
        val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
        // SKT terminals register the mmtel service with video (stock firmware)
        val mediaTags = if (mcc + mnc == "450005") "audio;video" else "audio"
        contact =
            """<sip:${contactUser}@$local;transport=$transport>;expires=600000;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;$mediaTags"""
        val newHeaders =
            (if(socket is SipConnectionTcp) {
                """
                Via: SIP/2.0/TCP $local;rport
                """
            } else {
                """
                Via: SIP/2.0/UDP $local;rport
                """
            }).toSipHeadersMap()
        registerHeaders += newHeaders
        commonHeaders += newHeaders
    }

    @SuppressLint("MissingPermission")
    fun register(_writer: OutputStream? = null, unregisterAll: Boolean = false) {
        val tm = ctxt.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        val cellInfoList = tm.getAllCellInfo()
        val pani = currentPani(cellInfoList)
        if (pani != null) lastPani = pani
        for(cell in cellInfoList) {
            if(cell is CellInfoLte) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(TAG, "LTE cell: ${cellIdentity.ci}, ${cellIdentity.pci}, ${cellIdentity.tac}, ${cellIdentity.mcc}, ${cellIdentity.mnc}, ${cellSignalStrength.dbm}")
            } else if(cell is CellInfoNr) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(TAG, "NR cell: ${cellIdentity.operatorAlphaLong}, ${cellIdentity.operatorAlphaShort}, ${cellIdentity}")
            } else if(cell is CellInfoWcdma) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(TAG, "WCDMA cell: ${cellIdentity.cid}, ${cellIdentity.lac}, ${cellIdentity.mcc}, ${cellIdentity.mnc}, ${cellSignalStrength.dbm}")
            } else if(cell is CellInfoGsm) {
                val cellIdentity = cell.cellIdentity
                val cellSignalStrength = cell.cellSignalStrength
                Rlog.d(TAG, "GSM cell: ${cellIdentity.cid}, ${cellIdentity.lac}, ${cellIdentity.mcc}, ${cellIdentity.mnc}, ${cellSignalStrength.dbm}")
            }
        }

        // XXX samsung rom apparently regenerates local SPIC/SPIS every register,
        // this doesn't affect current connections but possibly affects new incoming
        // connections ? Just keep it constant for now
        // XXX samsung doesn't increment cnonce but it would be better to avoid replays?
        // well that'd only matter if the server refused replays, so keep as is.
        // XXX timeout/retry? notification on fail? receive on thread?

        // Without IPsec, register from the Contact port: some networks (LG U+) send the
        // terminating requests to the port the REGISTER came from
        val writer = _writer ?: if (isControlSocketUdp && !sipUseIpsec) sigWriter else socket.gWriter()

        fun secClient(alg: String, ealg: String) =
            "ipsec-3gpp;prot=esp;mod=trans;spi-c=${ipsecSettings.clientSpiC.spi};spi-s=${ipsecSettings.clientSpiS.spi};port-c=${socket.gLocalPort()};port-s=${serverSocket.localPort};ealg=${ealg};alg=${alg}"

        val algs = listOf("hmac-sha-1-96", "hmac-md5-96")
        val ealgs = listOf("null", "aes-cbc")
        val secClients = algs.flatMap { alg -> ealgs.map { ealg -> secClient(alg, ealg) }}
        val secClientLine =
            "Security-Client: ${secClients.joinToString(", ")}"

                    //P-Access-Network-Info: 3GPP-E-UTRAN-FDD;utran-cell-id-3gpp=216302ee2003a107
        val msg =
            SipRequest(
                SipMethod.REGISTER,
                "sip:$realm",
                //"sip:lte-lguplus.co.kr",
                registerHeaders +
                    """
                    Expires: ${if (unregisterAll) 0 else 600000}
                    Cseq: $registerCounter REGISTER
                    Contact: ${if (unregisterAll) "*" else contact}
                    Supported: path, gruu, sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                    Authorization: $akaDigest
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    $secClientLine
                    """.toSipHeadersMap() +
                    (if (pani != null) mapOf("p-access-network-info" to listOf(pani)) else emptyMap())
            ) // route present on all calls except this
        Rlog.d(TAG, "Sending $msg")
        synchronized(writer) { writer.write(msg.toByteArray()) }
        registerCounter += 1
    }

    fun registerCallback(response: SipResponse): Boolean {
        // once we get there all register must be successful
        // on failure just abort thread, ims will restart
        require(response.statusCode == 200)

        val r =  Regex("lr;[^>]*")
        val route =
            (response.headers.getOrDefault("service-route", emptyList()) +
                    response.headers.getOrDefault("path", emptyList()))
                .toSet() // remove duplicates
                .toList()
                .map {
                    r.replace(it, "lr")
                }

        val associatedUri =
            response.headers["p-associated-uri"]!!
                .flatMap { it.split(",") }
                .map { it.trimStart('<').trimEnd('>').split(':') }
        val preSip = associatedUri.first { it[0] == "sip" }[1]

        mySip = "sip:" + preSip
        myTel = associatedUri.firstOrNull { it[0] == "tel" }?.get(1) ?: preSip.split("@")[0]
        commonHeaders +=
            mapOf(
                "route" to route,
                "from" to listOf("<$mySip>"),
                "to" to listOf("<$mySip>"),
            )

        subscribe()
        // always keep callback
        return false
    }

    fun subscribe() {
        val local =
            if(socket.gLocalAddr() is Inet6Address)
                "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
            else
                "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
        val sipInstance = "<urn:gsma:imei:${imei.substring(0,8)}-${imei.substring(8,14)}-0>"
        val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
        val contactTel =
            """<sip:$myTel@$local;transport=$transport>;expires=600000;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
        val msg =
            SipRequest(
                SipMethod.SUBSCRIBE,
                "$mySip",
                commonHeaders +
                    """
                    Contact: $contactTel
                    P-Preferred-Identity: <$mySip>
                    Event: reg
                    Expires: 600000
                    Supported: sec-agree
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, INFO, MESSAGE, PRACK, OPTIONS
                    Accept: application/reginfo+xml
                    P-Access-Network-Info: ${accessNetworkInfo()}
                    """.toSipHeadersMap()
            )
        if (!imsReady) {
            setResponseCallback(msg.headers["call-id"]!![0], ::subscribeCallback)
        }
        Rlog.d(TAG, "Sending $msg")
        synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
    }

    fun subscribeCallback(response: SipResponse): Boolean {
        /*if (response.statusCode != 200) {
            imsFailureCallback?.invoke()
            return true
        }*/
        imsReadyCallback?.invoke()
        imsReady = true
        return true
    }

    fun waitPrack(v: Int) {
        synchronized(prAckWaitLock) {
            while (prAckWait.contains(v)) {
                prAckWaitLock.wait(1000)
            }
        }
    }

    fun handlePrack(request: SipRequest): Int {
        Rlog.d(TAG, "Received PRACK for ${request.headers["rack"]!![0]}")
        synchronized(prAckWaitLock) {
            val id = request.headers["rack"]!![0].split(" ")[0].toInt()
            prAckWait.remove(id)
            prAckWaitLock.notifyAll()
        }
        return 200
    }

    fun handleUpdate(request: SipRequest): Int {
        val call = currentCall!!
        val ipType = if(call.rtpRemoteAddr is Inet6Address) "IP6" else "IP4"
        val allTracks = listOf(call.amrTrack, call.dtmfTrack).sorted()
        val mySdp = """
v=0
o=${sdpOrigin()} IN $ipType ${socket.gLocalAddr().hostAddress}
s=phh voice call
c=IN $ipType ${socket.gLocalAddr().hostAddress}
b=AS:38
b=RS:0
b=RR:0
t=0 0
m=audio ${call.rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}
b=AS:38
b=RS:0
b=RR:0
a=rtpmap:${call.amrTrack} AMR/8000/1
a=rtpmap:${call.dtmfTrack} telephone-event/8000
a=${call.amrTrackDesc}
a=ptime:20
a=maxptime:240
a=${call.dtmfTrackDesc}
a=curr:qos local sendrecv
a=curr:qos remote sendrecv
a=des:qos mandatory local sendrecv
a=des:qos mandatory remote sendrecv
a=sendrecv
                       """.trim().sdpBytes()

        currentCall = Call(
            outgoing =  call.outgoing,
            amrTrack = call.amrTrack,
            amrTrackDesc = call.amrTrackDesc,
            dtmfTrack = call.dtmfTrack,
            dtmfTrackDesc = call.dtmfTrackDesc,
            callHeaders = call.callHeaders,
            rtpRemoteAddr = call.rtpRemoteAddr,
            rtpRemotePort = call.rtpRemotePort,
            rtpSocket = call.rtpSocket,
            sdp = request.body,
            hasEarlyMedia = call.hasEarlyMedia,
            )

        val reply =
            SipResponse(
                statusCode = 200,
                statusString = "OK",
                headersParam =
                request.headers.filter { (k, _) ->
                    k in listOf("cseq", "via", "from", "to", "call-id")
                } + """
                    Content-Type: application/sdp
                    Supported: 100rel, replaces, timer
                    Require: precondition
                    Call-ID: ${currentCall!!.callHeaders["call-id"]!![0]}
                """.toSipHeadersMap(),
                body = mySdp
            )
        Rlog.d(TAG, "Replying back with $reply")
        synchronized(sigWriter) { sigWriter.write(reply.toByteArray()) }

        if(call?.outgoing == false) {
            val myHeaders2 = call.callHeaders - "rseq" - "content-type" - "require"
            val msg2 =
                SipResponse(
                    statusCode = 180,
                    statusString = "Ringing",
                    headersParam = myHeaders2
                )
            Rlog.d(TAG, "Sending $msg2")
            synchronized(sigWriter) { sigWriter.write(msg2.toByteArray()) }
        }

        return 0
    }

    val ackReceived = AtomicBoolean(false)
    fun handleAck(request: SipRequest): Int {
        Rlog.d(TAG, "Received ACK for ${request.headers["call-id"]?.get(0)}")
        ackReceived.set(true)
        return 0
    }

    // Uplink IP fragments are dropped by some networks, keep UDP messages under the MTU
    val maxUdpMessage: Int
        get() {
            // MTU of the IMS interface minus the IP and UDP headers
            val mtu = try {
                java.net.NetworkInterface.getByInetAddress(localAddr)?.mtu ?: 0
            } catch (t: Throwable) { 0 }
            val overhead = if (localAddr is Inet6Address) 48 else 28
            return if (mtu > overhead + 576) mtu - overhead else 1400
        }

    fun handleCancel(request: SipRequest): Int {
        val callId = request.headers["call-id"]!![0]
        Rlog.d(TAG, "Cancelled call $callId")
        val call = currentCall
        if (request.method == SipMethod.CANCEL && call != null && !call.outgoing && !callStarted.get() &&
                call.callHeaders["call-id"]?.get(0) == callId) {
            // RFC 3261 9.2: the INVITE gets its final answer too, or the network keeps it open
            val headers = call.callHeaders.filter { (k, _) -> k in listOf("via", "from", "to", "call-id", "cseq") }
            val msg = SipResponse(statusCode = 487, statusString = "Request Terminated", headersParam = headers)
            thread {
                // after the 200 OK of the CANCEL
                Thread.sleep(50)
                Rlog.d(TAG, "Sending $msg")
                synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
            }
        }
        callStopped.set(true)
        onCancelledCall?.invoke(Object(), "", emptyMap())
        return 200
    }

    private fun pcmPeak(buf: ByteArray, len: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < len) {
            val v = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt()
            if (v > peak) peak = v else if (-v > peak) peak = -v
            i += 2
        }
        return peak
    }

    data class Call(
        val outgoing: Boolean,
        val callHeaders: SipHeadersMap,
        val sdp: ByteArray,
        val amrTrack: Int,
        val amrTrackDesc: String,
        val dtmfTrack: Int,
        val dtmfTrackDesc: String,
        val rtpRemoteAddr: InetAddress,
        val rtpRemotePort: Int,
        val rtpSocket: DatagramSocket,
        val hasEarlyMedia: Boolean,
        val octetAligned: Boolean = false,
        val sessionExpires: String? = null,
        val compactSdp: ByteArray? = null,
        // Incoming calls: remote target and route set, to send BYE
        val remoteTarget: String? = null,
        val routeSet: List<String> = emptyList(),
        val localTo: String? = null,
        val remoteFrom: String? = null,
    )


    @SuppressLint("MissingPermission")
    fun callEncodeThread() {
        val call = currentCall!!
        thread {
            var sequenceNumber = 0

            val encoder = MediaCodec.createEncoderByType("audio/3gpp")
            val mediaFormat = MediaFormat.createAudioFormat("audio/3gpp", 8000, 1)
            mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 12200)
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            while(!callStarted.get()) {
                val timestamp = sequenceNumber * 160
                Thread.sleep(20)
                val rtpHeader = listOf(
                    // RTP
                    0x80, //rtp version
                    call.amrTrack, //payload type
                    (sequenceNumber shr 8), (sequenceNumber and 0xff),
                    (timestamp shr 24), ((timestamp shr 16) and 0xff), ((timestamp shr 8) and 0xff), (timestamp and 0xff),
                    0x03, 0x00, 0xd2, 0x00, //SSRC
                )
                val amrNothing = listOf(0x77, 0xc0) // CMR = 12.2kbps, F=0, FT=15=No TX/No RX, Q=1

                val buf = (rtpHeader + amrNothing).map { it.toUByte() }.toUByteArray().toByteArray()

                val dgramPacket =
                    DatagramPacket(buf, buf.size, call.rtpRemoteAddr, call.rtpRemotePort)
                call.rtpSocket.send(dgramPacket)
                sequenceNumber++
            }

            val rnnNoise = Rnnoise()

            // DANGER: Don't open the mic before the user acknowledged opening the call!

            val minBufferSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize)

            audioRecord.startRecording()

            var firstPacket = true

            val bufferSize = ((minBufferSize + (rnnNoise.getFrameSize() - 1 )) / rnnNoise.getFrameSize()).toInt() * rnnNoise.getFrameSize()
            val buffer = ByteArray(bufferSize)
            val bufferPostRnnoise = ByteArray(bufferSize)
            var txLastLog = 0L
            var txMicPeak = 0
            var txRnnPeak = 0
            var txPackets = 0
            while (true) {
                if (callStopped.get()) break
                val nRead = audioRecord.read(buffer,0, buffer.size)
                // Convert buffer from ByteArray to ShortArray
                // rnnoise is a 48kHz model and returns silence on this 8kHz stream, feed the mic as is
                System.arraycopy(buffer, 0, bufferPostRnnoise, 0, buffer.size)
                if (nRead > 0) {
                    txMicPeak = maxOf(txMicPeak, pcmPeak(buffer, nRead))
                    txRnnPeak = maxOf(txRnnPeak, pcmPeak(bufferPostRnnoise, nRead))
                }
                val txNow = System.currentTimeMillis()
                if (txNow - txLastLog >= 1000) {
                    Rlog.d(TAG, "Audio TX: read $nRead, mic peak $txMicPeak, after rnnoise $txRnnPeak, rtp sent $txPackets")
                    txLastLog = txNow
                    txMicPeak = 0
                    txRnnPeak = 0
                    txPackets = 0
                }

                // The capture buffer can be bigger than an input buffer of the encoder
                var inOff = 0
                while (inOff < nRead) {
                    val inBufIdx = encoder.dequeueInputBuffer(-1)
                    val inBuf = encoder.getInputBuffer(inBufIdx)!!
                    inBuf.clear()
                    val inLen = minOf(inBuf.remaining(), nRead - inOff)
                    inBuf.put(bufferPostRnnoise, inOff, inLen)

                    // Fake timestamp but it is not appearing in the output stream anyway
                    encoder.queueInputBuffer(inBufIdx, 0, inLen, System.nanoTime() / 1000, 0)
                    inOff += inLen
                }

                // Send everything the encoder has, so that no latency builds up
                var outBufInfo = MediaCodec.BufferInfo()
                var outBufIdx = encoder.dequeueOutputBuffer(outBufInfo, 0)
                while (outBufIdx >= 0) {
                    val outBuf = encoder.getOutputBuffer(outBufIdx)!!

                    val encoderData = ByteArray(outBufInfo.size)
                    outBuf.get(encoderData)
                    encoder.releaseOutputBuffer(outBufIdx, false)

                    var bufPos = 0
                    while(bufPos < outBufInfo.size) {
                        val frameSize = 32 // Read from encoderData[0]

                        // Every 20ms, at 8kHz, we have 160 samples
                        val timestamp = sequenceNumber * 160
                        val rtpHeader = listOf(
                            // RTP
                            0x80, //rtp version
                            ( if(firstPacket) 0x80 else 0 ) or call.amrTrack, //payload type
                            (sequenceNumber shr 8), (sequenceNumber and 0xff),
                            (timestamp shr 24), ((timestamp shr 16) and 0xff), ((timestamp shr 8) and 0xff), (timestamp and 0xff),
                            0x03, 0x00, 0xd2, 0x00, //SSRC
                        )
                        firstPacket = false

                        val ft = (encoderData[bufPos + 0].toUInt().toInt() shr 3) and 0xf
                        val cmr = 7 // we want to announce we want the 12.2kbps profile
                        val f = 0
                        val q = 1
                        val firstByte = (cmr shl 4) or (f shl 3) or (ft shr 1)
                        val secondByte = ( (ft and 1) shl 7) or (q shl 6) or (encoderData[bufPos + 1].toUInt().toInt() shr 2)

                        val nextBytes = (1 until (frameSize - 1)).map { i ->
                            // Take 2 bits left, 6 bits right
                            val left = (encoderData[bufPos + i].toUByte().toUInt().toInt() and 0x3) shl 6
                            val right = (encoderData[bufPos + i + 1].toUByte().toUInt().toInt() shr 2) and 0x3f
                            left or right
                        }
                        // Need to know the size in **bits** to know whether we include the lastByte or not
                        // Anyway in mode = 7 = 12.2KHz, we don't.
                        //val lastByte = (encoderData[bufPos + frameSize - 1].toUByte().toUInt().toInt() and 0x3) shl 6

                        // DTMF digits replace the audio frames while they are sent
                        val dtmfBuf = nextDtmfPacket(call, sequenceNumber, timestamp)
                        val buf = dtmfBuf ?: if (call.octetAligned) {
                            // Storage format frame is already ToC + octet aligned speech bits
                            val toc = (encoderData[bufPos].toInt() and 0x78) or 0x04
                            (rtpHeader + (cmr shl 4) + toc).map { it.toByte() }.toByteArray() +
                                encoderData.copyOfRange(bufPos + 1, bufPos + frameSize)
                        } else
                            (rtpHeader + firstByte + secondByte + nextBytes /*+ lastByte*/).map { it.toUByte() }.toUByteArray().toByteArray()

                        val dgramPacket =
                            DatagramPacket(buf, buf.size, call.rtpRemoteAddr, call.rtpRemotePort)
                        call.rtpSocket.send(dgramPacket)
                        txPackets++

                        sequenceNumber++
                        bufPos += frameSize
                    }
                    outBufInfo = MediaCodec.BufferInfo()
                    outBufIdx = encoder.dequeueOutputBuffer(outBufInfo, 0)
                }
            }
            audioRecord.stop()
            audioRecord.release()
            encoder.stop()
            encoder.release()
        }
    }

    var currentCall: Call? = null
    fun acceptCall() {
        thread {

            val local =
                if(socket.gLocalAddr() is Inet6Address)
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                else
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            val sipInstance = "<urn:gsma:imei:${imei.substring(0, 8)}-${imei.substring(8, 14)}-0>"
            val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
            val evolvedContact =
                """<sip:${contactUser}@$local;transport=$transport>;expires=600000;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;+g.3gpp.mid-call;+g.3gpp.srvcc-alerting;+g.3gpp.ps2cs-srvcc-orig-pre-alerting"""

            Rlog.d(TAG, "Accepting call")
            val call = currentCall!!
            val myHeaders = call.callHeaders
            val myHeaders3 = myHeaders - "rseq" - "security-verify" + """
                Session-Expires: ${call.sessionExpires ?: "900;refresher=uas"}
                Require: timer
                P-Preferred-Identity: <$mySip>
                Contact: $evolvedContact
                Content-Type: application/sdp
                """.toSipHeadersMap()

            // Normally we shouldn't send again the SDP. With "precondition" feature flag, the SDP in 183 Session Progress (then updated in UPDATE) should be used instead
            // But for some yet unknown reason, I need to do it (even though it contradicts my pcaps)
            val msg3 =
                SipResponse(
                    statusCode = 200,
                    statusString = "OK",
                    headersParam = myHeaders3,
                    body = call.sdp
                )
            var msg3Bytes = msg3.toByteArray()
            val maxSize = maxUdpMessage
            if (isControlSocketUdp && msg3Bytes.size > maxSize) {
                val smallContact = """<sip:${contactUser}@$local;transport=$transport>;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";audio"""
                val smallBody = call.compactSdp ?: call.sdp
                // First drop the optional headers and shorten the SDP, keeping the header names
                val reducedHeaders = myHeaders3 - "max-forwards" - "p-preferred-identity" - "user-agent" -
                    "p-access-network-info" - "allow" - "supported" - "contact" - "require" - "session-expires" +
                    mapOf("contact" to listOf(smallContact))
                var small = SipResponse(
                    statusCode = 200,
                    statusString = "OK",
                    headersParam = reducedHeaders,
                    body = smallBody,
                )
                if (small.toByteArray().size > maxSize) {
                    // Last resort, compact header names (RFC 3261 7.3.3): some networks ignore them
                    val compactNames = mapOf("via" to "v", "from" to "f", "to" to "t",
                        "call-id" to "i", "content-type" to "c", "contact" to "m")
                    val compactHeaders = (reducedHeaders - "content-length").mapKeys { (k, _) -> compactNames[k] ?: k }
                    small = SipResponse(
                        statusCode = 200,
                        statusString = "OK",
                        headersParam = compactHeaders + ("l" to listOf("${smallBody.size}")),
                        body = smallBody,
                        autofill = false,
                    )
                }
                Rlog.d(TAG, "200 OK is ${msg3Bytes.size} bytes (max $maxSize), sending a smaller one of ${small.toByteArray().size} bytes")
                Rlog.d(TAG, "Sending $small")
                msg3Bytes = small.toByteArray()
            } else {
                Rlog.d(TAG, "Sending $msg3")
            }
            ackReceived.set(false)
            synchronized(sigWriter) { sigWriter.write(msg3Bytes) }

            callStarted.set(true)

            // RFC 3261 13.3.1.4: over UDP, retransmit the 2xx until the ACK comes
            if (isControlSocketUdp) {
                var delay = 500L
                var waited = 0L
                while (!ackReceived.get() && !callStopped.get() && waited < 32000L) {
                    Thread.sleep(delay)
                    waited += delay
                    if (ackReceived.get() || callStopped.get()) break
                    Rlog.d(TAG, "No ACK yet, retransmitting 200 OK")
                    synchronized(sigWriter) { sigWriter.write(msg3Bytes) }
                    delay = minOf(delay * 2, 4000L)
                }
                if (!ackReceived.get()) Rlog.w(TAG, "No ACK for our 200 OK")
            }
        }
    }

    // RFC 3261 12.1.2: the requests in a dialog we started follow its Record-Route in reverse
    // order, not the service route of the registration
    private fun dialogRoute(resp: SipResponse): SipHeadersMap {
        val routeSet = resp.headers["record-route"].orEmpty().reversed()
        return if (routeSet.isNotEmpty()) mapOf("route" to routeSet) else emptyMap()
    }

    fun prack(resp: SipResponse) {
        val who = extractDestinationFromContact(resp.headers["contact"]!![0])
        val callId = resp.headers["call-id"]!![0]
        val rseq = resp.headers["rseq"]!![0]
        val whatToPrack = "$rseq ${resp.headers["cseq"]!![0]}"
        val msg =
            SipRequest(
                SipMethod.PRACK,
                who,
                headersParam = commonHeaders - "route" + """
                    RAck: $whatToPrack
                    Require: sec-agree
                    To: ${resp.headers["to"]!![0]}
                    From: ${resp.headers["from"]!![0]}
                    Call-Id: $callId
                    """.toSipHeadersMap() + dialogRoute(resp)
            )
        Rlog.d(TAG, "Sending $msg")
        synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
    }

    fun rejectCall() {
        thread {
            val call = currentCall!!
            val headers = call.callHeaders
            // A final answer, no RSeq. 603 is what the stock terminals send when the user
            // rejects the call ("cannot take the call"), 486 would tell the caller we are busy
            val myHeaders = headers - "rseq" - "require" - "content-type"
            val msg =
                SipResponse(
                    statusCode = 603,
                    statusString = "Decline",
                    headersParam = myHeaders
                )
            Rlog.d(TAG, "Sending $msg")
            synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }

            callStopped.set(true)
            onCancelledCall?.invoke(Object(), "", emptyMap())
        }
    }

    // The INVITE of the outgoing call until it gets a final answer, to CANCEL it
    @Volatile var pendingInvite: SipRequest? = null
    val cancelRequested = AtomicBoolean(false)

    private fun sendBye(call: Call) {
        if (call.remoteTarget == null || call.localTo == null || call.remoteFrom == null) return
        // Our side of the dialog is the From of the BYE, the route set is already in the right order
        val byeHeaders = commonHeaders +
            mapOf(
                "from" to listOf(call.localTo),
                "to" to listOf(call.remoteFrom),
                "call-id" to call.callHeaders["call-id"]!!,
            ) +
            (if (call.routeSet.isNotEmpty()) mapOf("route" to call.routeSet) else emptyMap())
        val msg = SipRequest(SipMethod.BYE, call.remoteTarget, byeHeaders)
        Rlog.d(TAG, "Sending $msg")
        thread { synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) } }
    }

    fun terminateCall() {
        val call = currentCall
        val invite = pendingInvite
        if (!callStarted.get() && invite != null) {
            // RFC 3261 9.1: same Request-URI, Call-ID, From, To, CSeq number and Via as the INVITE
            cancelRequested.set(true)
            val cancelHeaders = mapOf(
                "via" to invite.headers["via"]!!,
                "max-forwards" to listOf("70"),
                "from" to invite.headers["from"]!!,
                "to" to invite.headers["to"]!!,
                "call-id" to invite.headers["call-id"]!!,
                "cseq" to listOf("${invite.headers["cseq"]!![0].split(" ")[0]} CANCEL"),
            ) + (invite.headers["route"]?.let { mapOf("route" to it) } ?: emptyMap())
            val msg = SipRequest(SipMethod.CANCEL, invite.destination, cancelHeaders)
            Rlog.d(TAG, "Sending $msg")
            thread { synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) } }
        } else if (call != null && callStarted.get() && !callStopped.get()) {
            sendBye(call)
        }
        callStopped.set(true)

        onCancelledCall?.invoke(Object(), "", emptyMap())
    }

    /*
    Note: local/remote none/sendrecv are the precondition extension status.
    They basically mean that local/remote are pre-allocating resources before fulfilling the call

    Outgoing call process:
    (Note: If not specified, Requests are local => remote, response are remote => local)
    1. INVITE with SDP containing none current status, and all tracks we can support
    2. (useless) 100 Trying
    3. 183 Session Progress with SDP containing none current status, but selected one track and Rseq
    4. PRACK 183's RSeq and wait for its 200 OK PRACK
    5. UPDATE with SDP containing local sendrecv and remote none (We're starting decoding/encoding, but don't open mic)
    6. 200 OK UPDATE with SDP containing local sendrecv and remote sendrecv (precondition fullfilled)
    7. 183 Session Progress on the INVITE (no SDP, no PRACK)
    8. UPDATE from remote to local with final SDP (precondition infos can be absent)
    9. 200 OK UPDATE from local to remote with our final SDP
    10. 180 Ringing on INVITE (meaning it's actually ringing on the other side)
    11. 200 OK on INVITE (meaning the call is accepted) (opening mic)
    12. ACK (no answer?)

    Call is now running
    During call, remote will regularly send 200 OK on INVITE to keep alive (we have the timer extension enabled)
    We probably need to keep sending UPDATE-s regularly to keep alive
     */

    var respInFlight: SipResponse? = null
    val mediaStarted = AtomicBoolean(false)

    /*
     * DTMF (RFC 4733 telephone-event) in the RTP stream of the call: 5 event
     * packets of 20ms, then the end packet three times.
     */
    private val dtmfQueue = java.util.concurrent.ConcurrentLinkedQueue<Char>()
    private var dtmfEvent = -1
    private var dtmfStartTs = 0
    private var dtmfFrames = 0
    private var dtmfEndRepeats = 0

    fun queueDtmf(c: Char) {
        Rlog.d(TAG, "Queueing DTMF $c")
        dtmfQueue.add(c)
    }

    private fun nextDtmfPacket(call: Call, seq: Int, ts: Int): ByteArray? {
        if (dtmfEvent < 0) {
            val c = dtmfQueue.poll() ?: return null
            dtmfEvent = when (c) {
                in '0'..'9' -> c - '0'
                '*' -> 10
                '#' -> 11
                in 'A'..'D' -> 12 + (c - 'A')
                in 'a'..'d' -> 12 + (c - 'a')
                else -> return null
            }
            dtmfStartTs = ts
            dtmfFrames = 0
            dtmfEndRepeats = 0
        }
        val end = dtmfFrames >= 5
        if (!end) dtmfFrames++
        val duration = dtmfFrames * 160
        val marker = if (dtmfFrames == 1 && !end) 0x80 else 0
        val packet = listOf(
            0x80, marker or call.dtmfTrack,
            (seq shr 8) and 0xff, seq and 0xff,
            (dtmfStartTs shr 24) and 0xff, (dtmfStartTs shr 16) and 0xff, (dtmfStartTs shr 8) and 0xff, dtmfStartTs and 0xff,
            0x03, 0x00, 0xd2, 0x00, //SSRC
            dtmfEvent, (if (end) 0x80 else 0) or 10, //volume -10 dBm0
            (duration shr 8) and 0xff, duration and 0xff,
        ).map { it.toByte() }.toByteArray()
        if (end && ++dtmfEndRepeats >= 3) dtmfEvent = -1
        return packet
    }

    /*
     * INVITE of the stock LG U+ terminals (EF59L, Beehd): the LG U+ service headers
     * and feature tags. Without m_convert, the calls to some terminals of the other
     * carriers fail (404/503) when they are idle.
     */
    private fun lguInviteHeaders(headers: SipHeadersMap, local: String): SipHeadersMap =
        headers - "p-early-media" - "accept" - "p-preferred-service" - "expires" -
            "require" - "proxy-require" +
            mapOf(
                "from" to listOf("<$mySip;user=phone>"),
                "supported" to listOf("timer"),
                "p-lguplus-mmtel-info" to listOf("m_convert"),
                "privacy" to listOf("none"),
                "accept-contact" to listOf("*;description=\"AT\""),
                "contact" to listOf("<sip:$myTel@$local>;description=\"AT,MSG\";lgt-vt"),
                "session-expires" to listOf("3600;refresher=uac"),
                "min-se" to listOf("60"),
            )

    // SDP origin: a new session id per call (RFC 4566), the version goes up with every SDP
    private var sdpSessionId = 0L
    private var sdpVersion = 0L
    private fun newSdpSession() {
        sdpSessionId = System.currentTimeMillis()
        sdpVersion = sdpSessionId
    }
    private fun sdpOrigin(user: String = "-") = "$user $sdpSessionId ${sdpVersion++}"
    fun call(phoneNumber: String) {
        thread {
            // Fresh state for this call
            dtmfQueue.clear()
            dtmfEvent = -1
            callStopped.set(false)
            callStarted.set(false)
            mediaStarted.set(false)
            newSdpSession()

            val rtpSocket = DatagramSocket(0, localAddr)
            val fakeRtcpSocket = DatagramSocket(0, localAddr) //useless but annoying ImsMediaManager
            network.bindSocket(rtpSocket)
            //rtpSocket.connect(rtpRemoteAddr, rtpRemotePort.toInt())

            // The stock LG U+ terminals offer octet aligned AMR only (amrnb_oa_pt=96), the
            // interconnect with the other carriers does not cope well with bandwidth efficient
            val offerOctetAligned = mcc + mnc == "450006" || mcc + mnc == "450005"
            // The stock LG U+ offer has no QoS precondition, the terminating KT side does not
            // complete it and rejects the call
            val offerPrecondition = mcc + mnc != "450006" && mcc + mnc != "450005"
            val amrTrack = if (offerOctetAligned) 96 else 97
            val amrFmtp = if (offerOctetAligned) "octet-align=1;max-red=0"
                else "mode-change-capability=2;octet-align=0;max-red=0"
            val amrTrackDesc = "fmtp:$amrTrack $amrFmtp"
            val dtmfTrack = if (offerOctetAligned) 101 else 100
            val dtmfTrackDesc = "fmtp:$dtmfTrack 0-15"
            val allTracks = listOf(amrTrack,dtmfTrack).sorted()

            val ipType = if(localAddr is Inet6Address) "IP6" else "IP4"

            val sdp = """
v=0
o=${sdpOrigin()} IN $ipType ${socket.gLocalAddr().hostAddress}
s=phh voice call
c=IN $ipType ${socket.gLocalAddr().hostAddress}
b=AS:38
b=RS:0
b=RR:0
t=0 0
m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}
b=AS:38
b=RS:0
b=RR:0
a=ptime:20
a=maxptime:240
a=rtpmap:$amrTrack AMR/8000/1
a=rtpmap:$dtmfTrack telephone-event/8000
a=fmtp:$amrTrack $amrFmtp
a=fmtp:$dtmfTrack 0-15
${if (offerPrecondition) "a=curr:qos local none\na=curr:qos remote none\na=des:qos optional local sendrecv\na=des:qos optional remote sendrecv\n" else ""}a=sendrecv
                       """.trim().sdpBytes()

            // Dial plan of the stock LG U+ firmware (BeehdCall.dial):
            //   0... (domestic)  -> tel:+82-10-1234-5678
            //   00... and others -> tel:<number>;phone-context=lte-lguplus.co.kr
            val to = if (mcc + mnc == "450006") {
                val formatted = android.telephony.PhoneNumberUtils.formatNumber(phoneNumber, "KR") ?: phoneNumber
                if (phoneNumber.startsWith("0") && !phoneNumber.startsWith("00"))
                    "tel:+82-" + formatted.substring(1)
                else
                    "tel:$formatted;phone-context=lte-lguplus.co.kr"
            } else if (mcc + mnc == "450005") {
                // Dial plan of the stock SKT firmware
                val digits = phoneNumber.filter { it != '-' && it != ' ' }
                when {
                    digits.startsWith("00") -> "tel:+" + digits.substring(2)
                    digits.startsWith("0") -> "tel:+82-" + digits.substring(1)
                    digits.startsWith("+") -> "tel:$digits"
                    else -> "tel:$digits;phone-context=+82-10"
                }
            } else {
                "tel:$phoneNumber;phone-context=ims.mnc$mnc.mcc$mcc.3gppnetwork.org"
            }
            val sipInstance = "<urn:gsma:imei:${imei.substring(0, 8)}-${imei.substring(8, 14)}-0>"
            val local =
                if(socket.gLocalAddr() is Inet6Address)
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                else
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            val transport = if (socket is SipConnectionTcp) "tcp" else "udp"
            val contactTel =
                """<sip:$myTel@$local;transport=$transport>;expires=600000;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
            val baseHeaders = commonHeaders +
                """
                    From: <$mySip>
                    To: <$to>
                    P-Preferred-Identity: <$mySip>
                    P-Asserted-Identity: <$mySip>
                    Expires: 600000
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, MESSAGE, PRACK, OPTIONS
                    P-Early-Media: supported
                    Content-Type: application/sdp
                    Session-Expires: 900
                    Supported: 100rel, replaces, timer${if (offerPrecondition) ", precondition" else ""}
                    Accept: application/sdp
                    Min-SE: 90
                    Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
                    P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
                    P-Access-Network-Info: ${accessNetworkInfo()}
                    Contact: $contactTel
                    """.toSipHeadersMap() + generateCallId() - "p-asserted-identity"
            val myHeaders = if (mcc + mnc == "450006") lguInviteHeaders(baseHeaders, local) else baseHeaders
            // P-Preferred-Service: urn:urn-7:3gpp-service.ims.icsi.mmtel
            // Accept-Contact: *;+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel"
            val msg =
                SipRequest(
                    SipMethod.INVITE,
                    to,
                    myHeaders,
                    sdp
                )
            pendingInvite = msg
            cancelRequested.set(false)
            setResponseCallback(msg.headers["call-id"]!![0]) { r: SipResponse ->
                var resp = r
                var cseq = resp.headers["cseq"]!![0]

                var rseqHandled = false
                // If we stopped our process to PRACK a response, start again processing it
                if (cseq.contains("PRACK")) {
                    resp = respInFlight!!
                    respInFlight = null
                    cseq = resp.headers["cseq"]!![0]
                    rseqHandled = true
                }

                if (cseq.contains("ACK")) return@setResponseCallback  false

                if (cseq.contains("INVITE") && (resp.statusCode == 200 || resp.statusCode == 202)) {
                    // TODO Send UI that call started
                    // ACK C-Seq must be the same as INVITE C-Seq
                    // Extract C-Seq
                    val cseqLine = resp.headers["cseq"]!![0]
                    val cseq = cseqLine.split(" ")[0].toInt()
                    val newTo = resp.headers["to"]!![0]
                    val newFrom = resp.headers["from"]!![0]
                    // RFC 3261 12.1.2: as UAC, the route set is the Record-Route in reverse order
                    val routeSet = resp.headers["record-route"].orEmpty().reversed()
                    val remoteTarget = resp.headers["contact"]?.get(0)?.let { extractDestinationFromContact(it) } ?: to
                    val ackHeaders = commonHeaders +
                        mapOf(
                            "cseq" to listOf("$cseq ACK"),
                            "to" to listOf(newTo),
                            "from" to listOf(newFrom),
                            "call-id" to resp.headers["call-id"]!!,
                        ) +
                        (if (routeSet.isNotEmpty()) mapOf("route" to routeSet) else emptyMap())
                    val msg2 =
                        SipRequest(
                            SipMethod.ACK,
                            remoteTarget,
                            ackHeaders
                        )
                    Rlog.d(TAG, "Sending $msg2")
                    synchronized(sigWriter) { sigWriter.write(msg2.toByteArray()) }
                    pendingInvite = null
                    currentCall?.let {
                        currentCall = it.copy(remoteTarget = remoteTarget, routeSet = routeSet,
                            localTo = newFrom, remoteFrom = newTo)
                    }
                    if (cancelRequested.get()) {
                        // The 200 OK crossed our CANCEL: end the call that we already dropped
                        Rlog.d(TAG, "Call answered after our CANCEL, sending BYE")
                        currentCall?.let { sendBye(it) }
                        return@setResponseCallback true
                    }
                    if (!callStarted.get()) onOutgoingStarted?.invoke()
                    callStarted.set(true)
                    Rlog.d(TAG, "Invite got SUCCESS")
                } else {
                    Rlog.d(TAG, "Invite got status ${resp.statusCode} = ${resp.statusString}")
                    if (resp.statusCode == 180 || resp.statusCode == 183) onOutgoingProgress?.invoke()
                    if(resp.statusCode >= 300) {
                        pendingInvite = null
                        // RFC 3261 17.1.1.3: ACK a failure in the INVITE transaction (same branch and route)
                        val failAck = SipRequest(
                            SipMethod.ACK,
                            to,
                            mapOf(
                                "via" to msg.headers["via"]!!,
                                "max-forwards" to listOf("70"),
                                "from" to resp.headers["from"]!!,
                                "to" to resp.headers["to"]!!,
                                "call-id" to resp.headers["call-id"]!!,
                                "cseq" to listOf("${resp.headers["cseq"]!![0].split(" ")[0]} ACK"),
                            ) + (msg.headers["route"]?.let { mapOf("route" to it) } ?: emptyMap())
                        )
                        Rlog.d(TAG, "Sending $failAck")
                        synchronized(sigWriter) { sigWriter.write(failAck.toByteArray()) }
                    }
                    if(resp.statusCode >= 300) {
                        // Stop the media threads started on 183
                        callStopped.set(true)
                        onCancelledCall?.invoke(Object(), "",
                            mapOf(
                                "statusCode" to resp.statusCode.toString(),
                                "statusString" to resp.statusString))
                        // The whole call failed, so drop that call-id
                        return@setResponseCallback true
                    }
                }

                if(resp.headers["rseq"]?.isNotEmpty() == true && !rseqHandled) {
                    prack(resp)
                    respInFlight = resp
                    return@setResponseCallback false
                }

                val isSdp = resp.headers["content-type"]?.get(0) == "application/sdp"
                val isPrecondition = resp.headers["require"]?.find { it.contains("precondition") } != null

                if (!isSdp) return@setResponseCallback false

                val respSdp = resp.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()

                fun sdpElement(command: String): String? {
                    val v = respSdp.firstOrNull { it.startsWith("$command=")} ?: return null
                    return v.substring(2)
                }
                val rtpRemotePort = sdpElement("m")!!.split(" ")[1]
                val rtpRemoteAddr = InetAddress.getByName(sdpElement("c")!!.split(" ")[2])
                currentCall = Call(
                    outgoing = true,
                    amrTrack = amrTrack,
                    amrTrackDesc = amrTrackDesc,
                    dtmfTrack = dtmfTrack,
                    dtmfTrackDesc = dtmfTrackDesc,
                    // Update from/to/call-id based on the response we got to include the remote tag
                    callHeaders = myHeaders - "require" - "content-type" + ("from" to resp.headers["from"]!!) + ("to" to resp.headers["to"]!!) + ("call-id" to resp.headers["call-id"]!!),
                    rtpRemoteAddr = rtpRemoteAddr,
                    rtpRemotePort = rtpRemotePort.toInt(),
                    rtpSocket = rtpSocket,
                    sdp = resp.body,
                    hasEarlyMedia = resp.headers["p-early-media"]?.isNotEmpty() == true,
                    // The answer tells which AMR payload format the remote picked
                    octetAligned = offerOctetAligned ||
                        respSdp.any { it.startsWith("a=fmtp:$amrTrack") && it.contains("octet-align=1") },
                )
                if (resp.statusCode == 200 && resp.headers["cseq"]!![0].contains("INVITE")) {
                    currentCall = currentCall!!.copy(
                        remoteTarget = resp.headers["contact"]?.get(0)?.let { extractDestinationFromContact(it) },
                        routeSet = resp.headers["record-route"].orEmpty().reversed(),
                        localTo = resp.headers["from"]!![0],
                        remoteFrom = resp.headers["to"]!![0])
                }

                // This isn't the answer to our INVITE, but to our later precondition UPDATE
                // TODO Actually check cseq
                if(resp.headers["cseq"]?.get(0)?.contains("UPDATE") == true) {
                    if(isSdp && resp.statusCode == 200) {
                        // Nothing to do here, we've already upgraded the call with the new SDP, everything's fine
                        return@setResponseCallback false
                    }
                }

                if(isPrecondition && resp.statusCode == 183) {
                    Rlog.d(TAG, "Handling precondition...")
                    val currLocal = respSdp.first { it.startsWith("a=curr:qos local")}
                    // No resource has been allocated at either side
                    val localNone = currLocal.contains("none")
                    Rlog.d(TAG, "precondition: Curr is $currLocal $localNone")
                    val currRemote = respSdp.first { it.startsWith("a=curr:qos remote")}
                    val remoteNone = currRemote.contains("none")

                    if (localNone) {
                        // "Allocating our local resource" and update the call
                        if (mediaStarted.compareAndSet(false, true)) {
                            callDecodeThread()
                            callEncodeThread()
                        }

                        val newSdp = respSdp.map { line ->
                            if (line.startsWith("a=curr:qos local")) {
                                "a=curr:qos local sendrecv"
                            } else if (line.startsWith("a=des:qos mandatory local")) {
                                "a=des:qos mandatory local sendrecv"
                            } else {
                                line
                            }
                        }.joinToString("\r\n").toByteArray()

                        val msg2 =
                            SipRequest(
                                SipMethod.UPDATE,
                                resp.headers["contact"]?.get(0)?.let { extractDestinationFromContact(it) } ?: to,
                                currentCall!!.callHeaders - "route" + ("content-type" to listOf("application/sdp")) +
                                    dialogRoute(resp),
                                newSdp
                            )
                        Rlog.d(TAG, "Sending $msg2")
                        synchronized(sigWriter) { sigWriter.write(msg2.toByteArray()) }
                    }

                    return@setResponseCallback false
                }

                if(!isPrecondition && resp.statusCode == 183) {
                    if (mediaStarted.compareAndSet(false, true)) {
                        callDecodeThread()
                        callEncodeThread()
                    }
                }

                // No 183 (180 then 200 OK with the answer): start the media now
                if (resp.statusCode == 200 && resp.headers["cseq"]!![0].contains("INVITE") &&
                        mediaStarted.compareAndSet(false, true)) {
                    Rlog.d(TAG, "Starting media on 200 OK")
                    callDecodeThread()
                    callEncodeThread()
                }

                   /* } else {
                        imsMediaManager.openSession(
                            rtpSocket, fakeRtcpSocket,
                            ImsMediaSession.SESSION_TYPE_AUDIO,
                            AudioConfig.Builder()
                                .setCodecType(AudioConfig.CODEC_AMR)
                                .setRxPayloadTypeNumber(amrTrack.toByte())
                                .setTxPayloadTypeNumber(amrTrack.toByte())
                                .setRemoteRtpAddress(InetSocketAddress(rtpRemoteAddr, rtpRemotePort.toInt()))
                                .setSamplingRateKHz(8)
                                .setAmrParams(AmrParams.Builder()
                                    .setOctetAligned(false)
                                    .setAmrMode(AmrParams.AMR_MODE_7)
                                    .build())
                                .setMediaDirection(RtpConfig.MEDIA_DIRECTION_SEND_RECEIVE)
                                .build(),
                            myExecutor,
                            object: AudioSessionCallback() {
                                override fun onOpenSessionSuccess(session: ImsMediaSession) {
                                    Rlog.d(TAG, "Opened session $session")
                                    currentCall = Call(
                                        amrTrack = amrTrack,
                                        amrTrackDesc = amrTrackDesc,
                                        dtmfTrack = dtmfTrack,
                                        dtmfTrackDesc = dtmfTrackDesc,
                                        callHeaders = myHeaders - "require" - "content-type" + "Supported: precondition, 100rel, replaces, timer".toSipHeadersMap(),
                                        rtpRemoteAddr = rtpRemoteAddr,
                                        rtpRemotePort = rtpRemotePort.toInt(),
                                        rtpSocket = rtpSocket,
                                        sdp = resp.body,
                                        imsMediaSession = session)
                                }

                                override fun onOpenSessionFailure(error: Int) {
                                    Rlog.d(TAG, "Failed to open session $error")
                                }
                                override fun onSessionClosed() {
                                    Rlog.d(TAG, "Session closed")
                                }
                            }
                        )
                    }*/
                false // Return true when we want to stop receiving messages for that call
            }
            Rlog.d(TAG, "Sending $msg")
            synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
        }
    }

    fun callDecodeThread() {
        // Receiving thread
        thread {
            val minBufferSize = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioTrack = AudioTrack(AudioManager.STREAM_VOICE_CALL, 8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize, AudioTrack.MODE_STREAM)
            audioTrack.play()
            var rxLastLog = 0L
            val rxGain = android.os.SystemProperties.get("persist.vendor.ims.rx_gain", "2.0").toFloatOrNull() ?: 1.0f
            Rlog.d(TAG, "Audio RX gain $rxGain")
            var rxPackets = 0
            var rxDecoded = 0
            var rxNoOutput = 0
            var rxPeak = 0

            val decoder = MediaCodec.createDecoderByType("audio/3gpp")
            val mediaFormat = MediaFormat.createAudioFormat("audio/3gpp", 8000, 1)
            decoder.configure(mediaFormat, null, null, 0)
            decoder.start()

            while(true) {
                if(callStopped.get()) break
                val dgramBuf = ByteArray(2048)
                val dgram = DatagramPacket(dgramBuf, dgramBuf.size)
                currentCall!!.rtpSocket.receive(dgram)

                // Check RTP payload type
                val pt = dgramBuf[1].toUByte().toInt() and 0x7f
                Rlog.d(TAG, "Received RTP data is length ${dgram.length} pt is $pt")

                val octetAligned = currentCall!!.octetAligned
                val ft = if (octetAligned)
                        (dgramBuf[13].toUByte().toUInt() shr 3) and (0xf).toUInt()
                    else
                        (dgramBuf[13].toUByte().toUInt() shr 7) or ((dgramBuf[12].toUByte().toUInt() and (7).toUInt()) shl 1)
                Rlog.d(TAG, "Received RTP data (expecting AMR) ft is $ft")

                if(ft.toInt() != 7) continue
                if (octetAligned && dgram.length < 14 + 31) continue

                // RTP header 12 byte
                // AMR in RTP header 10 bits
                // Packet size 32, FT=7
                val baOs = ByteArrayOutputStream()

                baOs.write( ft.toInt() shl 3)

                if (octetAligned) {
                    // Skip RTP header, CMR and ToC; 31 bytes of speech for 12.2kbps
                    baOs.write(dgramBuf, 14, 31)
                }
                var m = 0
                // Warning: we should take good care counting the **bits** of the packet based on FT
                if (!octetAligned) for(i in 13 until dgram.length ) {
                    // Take 6 bits left, 2 bits right
                    val left = (dgramBuf[i].toUByte().toUInt().toInt() and 0x3f)  shl 2
                    val right = (dgramBuf[i + 1 ].toUByte().toUInt().toInt() shr 6) and 0x3
                    m++
                    baOs.write(left or right)
                }
                //Rlog.d(TAG, "Received RTP data of length ${dgram.length} $m")

                val inBufIndex = decoder.dequeueInputBuffer(-1)
                //Rlog.d(TAG, "Got decoding input buffer $inBufIndex")
                val inBuf = decoder.getInputBuffer(inBufIndex)!!
                val data = baOs.toByteArray()
                inBuf.clear()
                inBuf.put(data)
                decoder.queueInputBuffer(inBufIndex, 0, data.size, 0, 0)

                //TODO: Support DTX (comfort noise frames that don't repeat)
                //TODO: Can we receive multiple outs per in?
                val outBufInfo = MediaCodec.BufferInfo()
                val outBufIndex = decoder.dequeueOutputBuffer(outBufInfo, 0)
                //Rlog.d(TAG, "Got decoding output buffer $outBufIndex")
                if (outBufIndex >= 0) {
                    val outBuf = decoder.getOutputBuffer(outBufIndex)!!
                    val pcm = ByteArray(outBufInfo.size)
                    outBuf.duplicate().get(pcm)
                    rxPeak = maxOf(rxPeak, pcmPeak(pcm, pcm.size))
                    rxDecoded += outBufInfo.size
                    // App playback does not get the voice RX gain of the modem path, add it here
                    if (rxGain != 1.0f) {
                        var i = 0
                        while (i + 1 < pcm.size) {
                            val v = ((pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                            val g = (v * rxGain).toInt().coerceIn(-32768, 32767)
                            pcm[i] = (g and 0xff).toByte()
                            pcm[i + 1] = ((g shr 8) and 0xff).toByte()
                            i += 2
                        }
                    }
                    val written = audioTrack.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) Rlog.w(TAG, "AudioTrack write error $written")
                    decoder.releaseOutputBuffer(outBufIndex, false)
                } else {
                    rxNoOutput++
                }
                rxPackets++
                val rxNow = System.currentTimeMillis()
                if (rxNow - rxLastLog >= 1000) {
                    Rlog.d(TAG, "Audio RX: speech packets $rxPackets, decoded bytes $rxDecoded, no output $rxNoOutput, peak $rxPeak, track state ${audioTrack.playState}")
                    rxLastLog = rxNow
                    rxPackets = 0
                    rxDecoded = 0
                    rxNoOutput = 0
                    rxPeak = 0
                }
            }
            audioTrack.stop()
            audioTrack.release()
            decoder.stop()
            decoder.release()
        }
    }

    fun extractDestinationFromContact(contact: String): String {
        val r = Regex(".*<(sip:[^>]*)>.*")
        return r.find(contact)!!.groups[1]!!.value
    }

    val callStopped = AtomicBoolean(false)
    val callStarted = AtomicBoolean(false)
    val updateReceived = AtomicBoolean(false)

    val prAckWaitLock = Object()
    var prAckWait = mutableSetOf<Int>()
    fun handleCall(request: SipRequest): Int {
        val contentType = request.headers["content-type"]?.get(0)
        if (contentType != "application/sdp") return 404
        callStopped.set(false)
        callStarted.set(false)

        val f = request.headers["from"]
        val r = Regex(".*(sip|tel):([^@]*).*")
        val m = r.find(f!![0]!!)!!.groups[2]!!.value
        Rlog.d(TAG, "Incoming call from $m")
        onIncomingCall?.invoke(Object(), m, mapOf("call-id" to request.headers["call-id"]!![0]))

        // We'll have three states:
        // - 100 Trying (this will be done by returning 100 in this function)
        // - 183 Session Progress network-wise we're ready to receive data
        // - 180 Ringing Notification's AudioTrack is playing, the user can hear its phone -- Note: Ringing doesn't give SDP
        // - 200 User has accepted the call

        val sdp = request.body.toString(Charsets.UTF_8).split("[\r\n]+".toRegex()).toList()
        Rlog.d(TAG, "Split SDP into $sdp")
        fun sdpElement(command: String): String? {
            val v = sdp.firstOrNull { it.startsWith("$command=")} ?: return null
            return v.substring(2)
        }
        val sdpConnectionData = sdpElement("c")
        val sdpOrigin = sdpElement("o")
        val sdpSessionName = sdpElement("s")
        val sdpTiming = sdpElement("t")
        val sdpBandwidth = sdpElement("b")
        val sdpMedia = sdpElement("m")

        Rlog.d(TAG, "Got sdpTiming $sdpTiming")

        if (sdpTiming != "0 0")
            Rlog.d(TAG, "Uh-oh, unknown timing mode")


        val rtpRemote = sdpConnectionData!!.split(" ")[2] //c=IN IP6 xxx
        val rtpRemoteAddr = InetAddress.getByName(rtpRemote)
        val rtpRemotePort = sdpMedia!!.split(" ")[1] //m=audio 30798 RTP/AVP 96 97 98 8 18 101 100 99

        val attributes = sdp.filter { it.startsWith("a=") }.map { it.substring(2)}

        fun lookTrackMatching(codec: String, additional: String = "", notAdditional: String = ""): Pair<Int,String>? {
            //TODO: also match on fmtp
            val maps = attributes.filter { it.startsWith("rtpmap") && it.contains(codec) }
            val matches = maps.map { m ->
                val track = m.split("[: ]+".toRegex())[1].toInt()
                val desc = m
                Pair(track, desc)
            }
            Rlog.d(TAG, "Matching $codec, got $matches")
            val matches2 = if(matches.size > 1) {
                matches.sortedBy { m ->
                    val fmtp = attributes.filter { it.startsWith("fmtp:${m.first}") }[0]
                    Rlog.d(TAG, "Matching $codec, for match $m got fmtp $fmtp")
                    if(fmtp.contains(additional))
                        0
                    else if (notAdditional.isNotEmpty() && !fmtp.contains(notAdditional))
                        1
                    else
                        2
                }
            } else {
                matches
            }
            Rlog.d(TAG, "Matching2 $codec, got $matches2")
            return matches2.firstOrNull()
        }

        fun trackRequirements(track: Int): String? {
            return attributes.firstOrNull() { it.startsWith("fmtp:$track") }
        }

        val peerOptions = (request.headers["supported"].orEmpty() + request.headers["require"].orEmpty())
            .flatMap { it.split(",") }.map { it.trim().toLowerCase() }
        val peerPrecondition = "precondition" in peerOptions
        val peer100rel = "100rel" in peerOptions
        // A reliable 183 is only allowed if the caller supports 100rel
        val hasEarlyMedia = request.headers["p-early-media"]?.isNotEmpty() == true && peer100rel
        Rlog.d(TAG, "Caller options $peerOptions, precondition $peerPrecondition, 100rel $peer100rel")

        // Look for an AMR/8000 mode
        // TODO: Select which one? SFR has two, one with mode-set=7 one without it. This would require reading the fmtp lines
        val (amrTrack, amrTrackDesc) = lookTrackMatching("AMR/8000", "octet-align=0", "octet-align=1")!!
        val amrTrackRequirements = trackRequirements(amrTrack)
        val octetAligned = amrTrackRequirements?.contains("octet-align=1") == true

        // Look for a DTMF track, use the 8000Hz-based one to match AMR timestamps
        val (dtmfTrack, dtmfTrackDesc) = lookTrackMatching("telephone-event/8000")!!

        val allTracks = listOf(amrTrack, dtmfTrack).sorted()
        // destination is sip:<owner>@realm, extract owner
        val owner = request.destination.substringAfter("sip:").substringBefore("@")

        thread {
            // Need to sleep a bit so that our 100 Trying is sent first. Kinda weird.
            Thread.sleep(500)
            newSdpSession()
            val rtpSocket = DatagramSocket(0, localAddr)
            network.bindSocket(rtpSocket)
            rtpSocket.connect(rtpRemoteAddr, rtpRemotePort.toInt())

            val local =
                if(socket.gLocalAddr() is Inet6Address)
                    "[${socket.gLocalAddr().hostAddress}]:${serverSocket.localPort}"
                else
                    "${socket.gLocalAddr().hostAddress}:${serverSocket.localPort}"
            val sipInstance = "<urn:gsma:imei:${imei.substring(0,8)}-${imei.substring(8,14)}-0>"
            val contactTel =
                """<sip:$myTel@$local;transport=${if (socket is SipConnectionTcp) "tcp" else "udp"}>;expires=600000;+sip.instance="$sipInstance";+g.3gpp.icsi-ref="urn%3Aurn-7%3A3gpp-service.ims.icsi.mmtel";+g.3gpp.smsip;audio"""
            val mySeqCounter = reliableSequenceCounter++
            val ipType = if(socket.gLocalAddr() is Inet6Address) "IP6" else "IP4"
            val qosNone = listOf(
                "a=curr:qos local none",
                "a=curr:qos remote none",
                "a=des:qos mandatory local sendrecv",
                "a=des:qos mandatory remote sendrecv",
                "a=conf:qos remote sendrecv",
            ).joinToString("\n", postfix = "\n")
            val mySdp = """
v=0
o=${sdpOrigin(owner)} IN $ipType ${socket.gLocalAddr().hostAddress}
s=phh voice call
c=IN $ipType ${socket.gLocalAddr().hostAddress}
b=AS:38
b=RS:0
b=RR:0
t=0 0
m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}
b=AS:38
b=RS:0
b=RR:0
a=$amrTrackDesc
a=ptime:20
a=maxptime:240
a=$dtmfTrackDesc
a=fmtp:$amrTrack mode-set=7;octet-align=${if (octetAligned) 1 else 0};max-red=0
a=fmtp:$dtmfTrack 0-15
${if (peerPrecondition) qosNone else ""}a=sendrecv
                       """.trim().sdpBytes()

            val compactSdp = """
v=0
o=${sdpOrigin()} IN $ipType ${socket.gLocalAddr().hostAddress}
s=-
c=IN $ipType ${socket.gLocalAddr().hostAddress}
t=0 0
m=audio ${rtpSocket.localPort} RTP/AVP ${allTracks.joinToString(" ")}
a=$amrTrackDesc
a=fmtp:$amrTrack mode-set=7;octet-align=${if (octetAligned) 1 else 0}
a=$dtmfTrackDesc
${if (peerPrecondition) qosNone else ""}a=sendrecv
                       """.trim().sdpBytes()

            // Same To tag in every response, or the caller sees different dialogs
            val localTo = request.headers["to"]!![0].let {
                if (it.contains(";tag=")) it else "$it;tag=${randomBytes(6).toHex()}"
            }
            val requires = listOfNotNull(if (hasEarlyMedia) "100rel" else null,
                if (hasEarlyMedia && peerPrecondition) "precondition" else null)

            val myHeaders = commonHeaders + //Require: precondition
                """
                        Contact: $contactTel
                        Allow: INVITE, ACK, CANCEL, BYE, UPDATE, REFER, NOTIFY, INFO, MESSAGE, PRACK, OPTIONS
                        Content-Type: application/sdp
                        P-Access-Network-Info: ${accessNetworkInfo()}
                        """.toSipHeadersMap() +
                (if (requires.isNotEmpty())
                    mapOf("require" to listOf(requires.joinToString(", ")), "rseq" to listOf("$mySeqCounter"))
                else emptyMap()) +
                            request.headers.filter { (k, _) -> k in listOf("cseq", "via", "from", "call-id", "record-route") } +
                ("to" to listOf(localTo)) -
                "route" - "security-verify"

            currentCall = Call(
                outgoing = false,
                amrTrack = amrTrack,
                amrTrackDesc = amrTrackDesc,
                dtmfTrack = dtmfTrack,
                dtmfTrackDesc = dtmfTrackDesc,
                callHeaders = myHeaders - "require" - "content-type" + "Supported: 100rel, replaces, timer".toSipHeadersMap(),
                rtpRemoteAddr = rtpRemoteAddr,
                rtpRemotePort = rtpRemotePort.toInt(),
                rtpSocket =  rtpSocket,
                sdp = mySdp,
                hasEarlyMedia = hasEarlyMedia,
                octetAligned = octetAligned,
                remoteTarget = request.headers["contact"]?.get(0)?.let { extractDestinationFromContact(it) },
                routeSet = request.headers["record-route"].orEmpty(),
                localTo = localTo,
                remoteFrom = request.headers["from"]!![0],
                sessionExpires = request.headers["session-expires"]?.get(0),
                compactSdp = compactSdp,
            )

            callDecodeThread()
            callEncodeThread()


            synchronized(prAckWaitLock) {
                prAckWait.add(mySeqCounter)
            }
            if (hasEarlyMedia) {
                val msg =
                    SipResponse(
                        statusCode = 183,
                        statusString = "Session Progress",
                        headersParam = myHeaders,
                        body = mySdp
                    )
                Rlog.d(TAG, "Sending $msg")
                synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
                waitPrack(mySeqCounter)
            }
            if (!hasEarlyMedia) {
                val myHeaders2 = myHeaders - "rseq" - "content-type" - "require" +
                    """
Supported: 100rel, replaces, timer
P-Access-Network-Info: ${accessNetworkInfo()}

""".toSipHeadersMap()
                val msg2 =
                    SipResponse(
                        statusCode = 180,
                        statusString = "Ringing",
                        headersParam = myHeaders2
                    )
                Rlog.d(TAG, "Sending $msg2")
                synchronized(sigWriter) { sigWriter.write(msg2.toByteArray()) }
            }
        }

        // Next step is 180 Ringing or 183, handled in the thread
        return 100
    }

    fun handleSms(request: SipRequest): Int {
        val sms = request.body.SipSmsDecode()
        if (sms == null) {
            Rlog.w(TAG, "Could not decode sms pdu")
            return 500
        }
        Rlog.d(TAG, "Decoded SMS type ${sms.type}, ${sms.pdu?.toString()}")
        when (sms.type) {
            SmsType.RP_DATA_FROM_NETWORK -> {
                val receivedCb = onSmsReceived
                if (receivedCb == null) {
                    Rlog.d(TAG, "No onSmsReceived callback!")
                    return 500
                }

                val token = smsLock.withLock { smsToken++ }
                val dest =
                    request.headers["from"]!![0]
                        .getParams()
                        .component1()
                        .trimStart('<')
                        .trimEnd('>')
                val callId = request.headers["call-id"]!![0]
                val cseq = request.headers["cseq"]!![0]
                smsHeadersMap[token] = smsHeaders(dest, callId, cseq)
                try {
                    receivedCb(token, "3gpp", sms.pdu!!)
                } catch(t: Throwable) {
                    Rlog.d(TAG, "Failed sending SMS to framework", t);
                }
            }
            SmsType.RP_ACK_FROM_NETWORK -> {
                try {
                    onSmsStatusReportReceived?.invoke(sms.ref.toInt(), "3gpp", ByteArray(2))
                } catch(t: Throwable) {
                    Rlog.d(TAG, "Failed sending SMS ACK to framework", t)
                }
            }
            SmsType.RP_ERROR_FROM_NETWORK -> {
                Rlog.d(TAG, "SMS error from network")
            }
            else -> return 500
        }
        return 200
    }

    fun sendSms(
        smsSmsc: String?,
        pdu: ByteArray,
        ref: Int,
        successCb: (() -> Unit),
        failCb: (() -> Unit)
    ) {
        val decodableSmsc = try {
            PhoneNumberUtils.numberToCalledPartyBCD(smsSmsc, PhoneNumberUtils.BCD_EXTENDED_TYPE_CALLED_PARTY); true
        } catch (t:Throwable) { false }

        val smsManager =
            ctxt.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
        val smscIdentity = try {
            val i = smsManager
                .javaClass.getMethod("getSmscIdentity")
                .invoke(smsManager) as Uri
            if (i.host == null) null else i
        } catch (t: Throwable) { null }
        Rlog.d(TAG, "Got smscIdentity $smscIdentity")
        // make ref up?
        val smsc =
            if (smsSmsc != null && decodableSmsc) smsSmsc
            else if (forceSmsc != null) forceSmsc
            else {
                try {
                    Rlog.d(TAG, "Got smsc $smscIdentity // host is ${smscIdentity?.host} // ${smscIdentity?.scheme} // ${smscIdentity?.path}")
                    smscIdentity!!.host!!
                } catch(t: Throwable) {
                    try {
                        Rlog.d(TAG, "getSmscIdentity failed", t)
                        val smscStr = smsManager.smscAddress
                        val smscMatchRegex = Regex("([0-9]+)")
                        Rlog.d(TAG, "Got smsc $smscStr, match ${smscMatchRegex.find(smscStr!!)}")
                        val match = smscMatchRegex.find(smscStr!!)!!
                        match.groupValues[1]
                    } catch(t: Throwable) {
                        Rlog.d(TAG, "smscAddress failed", t)
                        null
                    }
                }
            }

        // smsc
        val data = SipSmsEncodeSms(ref.toByte(), if(smsc == null) "" else "+$smsc", pdu)
        Rlog.d(TAG, "sending sms ${data.toHex()} to smsc $smsc")
        val dest =
            if(smscIdentity != null)
                "sip:$smscIdentity"
            else
                "sip:+$smsc@$realm"

        // "sip:ipsmgw.lte-lguplus.co.kr",
        val msg =
            SipRequest(
                SipMethod.MESSAGE,
                "sip:${smscIdentity ?: realm}",
                commonHeaders +
                    """
                    From: <$mySip>
                    To: <$dest>
                    P-Preferred-Identity: <$mySip>
                    P-Asserted-Identity: <$mySip>
                    Expires: 600000
                    Content-Type: application/vnd.3gpp.sms
                    Supported: sec-agree, path
                    Require: sec-agree
                    Proxy-Require: sec-agree
                    Allow: MESSAGE
                    Accept-Contact: *;+g.3gpp.smsip;require;explicit
                    Request-Disposition: no-fork
                    """.toSipHeadersMap(),
                data
            )
        setResponseCallback(
            msg.headers["call-id"]!![0],
            { resp: SipResponse ->
                if (resp.statusCode == 200 || resp.statusCode == 202) {
                    successCb()
                } else {
                    failCb()
                }
                true
            }
        )
        Rlog.d(TAG, "Sending $msg")
        synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
    }

    fun sendSmsAck(token: Int, ref: Int, error: Boolean): Unit {
        Rlog.d(TAG, "sending sms ack")
        val body = SipSmsEncodeAck(ref.toByte())
        val headers = smsHeadersMap.remove(token)
        if (headers == null) {
            // XXX return error?
            return
        }
        // do not send ack on error
        // Should we send an error report?
        if (error) {
            return
        }
        val msg =
            SipRequest(
                SipMethod.MESSAGE,
                headers.dest,
                commonHeaders +
                    """
                    Cseq: ${headers.cseq}
                    In-Reply-To: ${headers.callId}
                    Content-Type: application/vnd.3gpp.sms
                    Proxy-Require: sec-agree
                    Require: sec-agree
                    Allow: MESSAGE
                    Supported: path, gruu, sec-agree
                    Request-Disposition: no-fork
                    Accept-Contact: *;+g.3gpp.smsip
                    """.toSipHeadersMap(),
                body
            )
        // ignore response
        setResponseCallback(msg.headers["call-id"]!![0], { true })
        Rlog.d(TAG, "Sending $msg")
        synchronized(sigWriter) { sigWriter.write(msg.toByteArray()) }
    }
}
