import com.formdev.flatlaf.FlatDarkLaf
import kotlinx.coroutines.*
import java.awt.*
import java.awt.image.BufferedImage
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.swing.JOptionPane
import kotlin.system.exitProcess

// 全局状态
var currentPhoneIp: String? = null
var proxyPort = 7890
lateinit var trayIcon: TrayIcon
lateinit var statusMenuItem: MenuItem

val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

fun main() {
    FlatDarkLaf.setup()
    if (!SystemTray.isSupported()) {
        println("当前系统不支持系统托盘！")
        return
    }

    // 监听 Windows 关机/注销/程序退出信号，安全清理代理
    Runtime.getRuntime().addShutdownHook(Thread {
        setWindowsProxy(false)
    })

    initSystemTray()

    appScope.launch {
        while (isActive) {
            checkCurrentIpStatus()
            delay(3000)
        }
    }

    triggerRelocate()
}

fun initSystemTray() {
    val tray = SystemTray.getSystemTray()
    val popup = PopupMenu()

    statusMenuItem = MenuItem("状态: 初始化中...")
    statusMenuItem.isEnabled = false

    val relocateItem = MenuItem("重新定位手机 (Relocate)")
    val setPortItem = MenuItem("设置端口 (Set Port)")
    val exitItem = MenuItem("退出 (Exit)")

    relocateItem.addActionListener { triggerRelocate() }

    setPortItem.addActionListener {
        val input = JOptionPane.showInputDialog(
            null,
            "请输入手机代理端口号：",
            "设置端口",
            JOptionPane.QUESTION_MESSAGE,
            null,
            null,
            proxyPort.toString()
        ) as String?

        if (!input.isNullOrBlank()) {
            val parsedPort = input.toIntOrNull()
            if (parsedPort != null && parsedPort in 1..65535) {
                proxyPort = parsedPort
                setWindowsProxy(false)
                currentPhoneIp = null
                triggerRelocate()
            } else {
                JOptionPane.showMessageDialog(null, "请输入正确的端口范围 (1-65535)！", "错误", JOptionPane.ERROR_MESSAGE)
            }
        }
    }

    exitItem.addActionListener {
        setWindowsProxy(false)
        exitProcess(0)
    }

    popup.add(statusMenuItem)
    popup.add(relocateItem)
    popup.add(setPortItem)
    popup.addSeparator()
    popup.add(exitItem)

    trayIcon = TrayIcon(createDefaultIcon(Color.GRAY), "代理助手 (未定位)", popup)
    trayIcon.isImageAutoSize = true
    trayIcon.toolTip = "代理助手 (端口: $proxyPort)"

    tray.add(trayIcon)
}

fun updateTrayStatus(status: String, color: Color, toolTipText: String, notifyTitle: String? = null, notifyMsg: String? = null) {
    statusMenuItem.label = "状态: $status"
    trayIcon.image = createDefaultIcon(color)
    trayIcon.toolTip = toolTipText
    if (notifyTitle != null && notifyMsg != null) {
        trayIcon.displayMessage(notifyTitle, notifyMsg, TrayIcon.MessageType.INFO)
    }
}

fun triggerRelocate() {
    trayIcon.toolTip = "正在全网并发扫描 (端口: $proxyPort)..."
    trayIcon.displayMessage("正在定位", "启动并发扫描寻找手机端口: $proxyPort...", TrayIcon.MessageType.INFO)

    appScope.launch {
        val prefix = getLocalSubnetPrefix()
        val foundIp = findFlclashIpParallel(prefix)

        if (foundIp != null) {
            currentPhoneIp = foundIp
            trayIcon.image = createDefaultIcon(Color.GREEN)
            trayIcon.toolTip = "已锁定: $foundIp:$proxyPort"
            trayIcon.displayMessage("定位成功", "已找到手机: $foundIp，接管网络。", TrayIcon.MessageType.INFO)
            checkCurrentIpStatus()
        } else {
            currentPhoneIp = null
            trayIcon.image = createDefaultIcon(Color.RED)
            trayIcon.toolTip = "代理助手 (未定位, 端口: $proxyPort)"
            trayIcon.displayMessage("定位失败", "局域网内未发现开放 $proxyPort 端口的设备。", TrayIcon.MessageType.ERROR)
            setWindowsProxy(false)
        }
    }
}

fun checkCurrentIpStatus() {
    val ip = currentPhoneIp

    // 情况1：完全没定位过，无需检查
    if (ip == null) return

    // 情况2：定位过，尝试连接
    val isPortOpen = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, proxyPort), 500)
            true
        }
    } catch (e: Exception) {
        false
    }

    if (isPortOpen) {
        setWindowsProxy(true, ip)
        trayIcon.image = createDefaultIcon(Color.GREEN)
        updateTrayStatus("已连接 ($ip)", Color.GREEN, "已连接至 $ip:$proxyPort")
    } else {
        setWindowsProxy(false)
        trayIcon.image = createDefaultIcon(Color.YELLOW)
        trayIcon.toolTip = "手机代理已断开，正在等待重连: $ip:$proxyPort"
        val isFirstTimeDisconnect = trayIcon.toolTip.contains("已连接")
        println("尝试重连手机: $ip:$proxyPort ...")
        updateTrayStatus("断开，重连中...", Color.YELLOW, "正在等待重连: $ip:$proxyPort",
            if (isFirstTimeDisconnect) "代理已断开" else null,
            if (isFirstTimeDisconnect) "检测到手机代理丢失，正在静默重试..." else null
        )
    }
}

suspend fun findFlclashIpParallel(subnetPrefix: String): String? = coroutineScope {
    val deferreds = (2..254).map { i ->
        async(Dispatchers.IO) {
            val testIp = "$subnetPrefix.$i"
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(testIp, proxyPort), 200)
                }
                testIp
            } catch (e: Exception) {
                null
            }
        }
    }
    deferreds.awaitAll().firstOrNull { it != null }
}

fun setWindowsProxy(enable: Boolean, ip: String? = null) {
    val regPath = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    try {
        if (enable && ip != null) {
            Runtime.getRuntime().exec("reg add \"$regPath\" /v ProxyServer /t REG_SZ /d \"$ip:$proxyPort\" /f").waitFor()
            Runtime.getRuntime().exec("reg add \"$regPath\" /v ProxyEnable /t REG_DWORD /d 1 /f").waitFor()
        } else {
            Runtime.getRuntime().exec("reg add \"$regPath\" /v ProxyEnable /t REG_DWORD /d 0 /f").waitFor()
        }
        val refreshCmd = """powershell -WindowStyle Hidden -Command "${'$'}sig = '[DllImport(\"wininet.dll\")] public static extern bool InternetSetOption(IntPtr h, int o, IntPtr b, int l);'; ${'$'}w = Add-Type -MemberDefinition ${'$'}sig -Name 'WinInet' -PassThru; ${'$'}w::InternetSetOption(0, 39, 0, 0); ${'$'}w::InternetSetOption(0, 37, 0, 0);""""
        Runtime.getRuntime().exec(refreshCmd).waitFor()
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun getLocalSubnetPrefix(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (netInterface in interfaces) {
            if (netInterface.isUp && !netInterface.isLoopback && !netInterface.isVirtual) {
                for (address in netInterface.inetAddresses) {
                    if (address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))) {
                            return ip.substringBeforeLast(".")
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "192.168.1"
}

fun createDefaultIcon(color: Color): Image {
    val img = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    val g = img.graphics
    g.color = color
    g.fillOval(2, 2, 12, 12)
    g.dispose()
    return img
}