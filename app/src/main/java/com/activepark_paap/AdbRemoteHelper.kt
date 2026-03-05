package com.activepark_paap

import android.content.Context
import android.util.Log

/**
 * Enables adb TCP on port 5555 and restricts access via iptables to a single server IP.
 * Requires root.
 */
object AdbRemoteHelper {

    private const val TAG = "AdbRemoteHelper"
    private const val ADB_PORT = 5555
    private const val PREFS = "adb_remote"
    private const val KEY_SERVER_IP = "server_ip"

    private val IP_REGEX = Regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")
    private val OCTET_RANGE = 0..255

    /** Pure: validate IPv4 address format + octet range */
    fun isValidIp(ip: String): Boolean {
        if (!ip.matches(IP_REGEX)) return false
        return ip.split(".").all { it.toIntOrNull() in OCTET_RANGE }
    }

    /** Pure: iptables commands to lock adb port to a single IP */
    fun buildFirewallCommands(ip: String, port: Int = ADB_PORT): List<String> {
        assert(isValidIp(ip)) { "invalid IP: $ip" }
        return listOf(
            "iptables -D INPUT -p tcp --dport $port -j DROP 2>/dev/null; true",
            "iptables -D INPUT -p tcp --dport $port -s $ip -j ACCEPT 2>/dev/null; true",
            "iptables -I INPUT -p tcp --dport $port -s $ip -j ACCEPT",
            "iptables -A INPUT -p tcp --dport $port -j DROP"
        )
    }

    /** Pure: commands to enable adb TCP on given port */
    fun buildAdbEnableCommands(port: Int = ADB_PORT): List<String> {
        return listOf(
            "setprop service.adb.tcp.port $port",
            "stop adbd",
            "start adbd"
        )
    }

    /** Pure: commands to persistently disable adb TCP */
    fun buildAdbDisableCommands(port: Int = ADB_PORT): List<String> {
        return listOf(
            "setprop service.adb.tcp.port -1",
            "stop adbd",
            "start adbd"
        )
    }

    fun getSavedIp(ctx: Context): String {
        val ip = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_IP, "") ?: ""
        assert(ip.isEmpty() || isValidIp(ip)) { "invalid saved IP: $ip" }
        return ip
    }

    fun saveIp(ctx: Context, ip: String) {
        assert(isValidIp(ip)) { "invalid IP format: $ip" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_IP, ip).commit()
    }

    fun enableAdbWithFirewall(ctx: Context): Boolean {
        val ip = getSavedIp(ctx)
        if (ip.isEmpty()) {
            Log.e(TAG, "no server IP configured, skipping adb enable")
            return false
        }
        return enableAdbWithFirewall(ip)
    }

    fun enableAdbWithFirewall(ip: String): Boolean {
        assert(isValidIp(ip)) { "invalid IP: $ip" }
        return try {
            if (!isAdbEnabled()) {
                buildAdbEnableCommands().forEach { exec(it) }
            }
            buildFirewallCommands(ip).forEach { exec(it) }
            Log.e(TAG, "adb TCP enabled, locked to $ip")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to enable adb", e)
            false
        }
    }

    fun disableAdb(): Boolean {
        return try {
            buildAdbDisableCommands().forEach { exec(it) }
            exec("iptables -D INPUT -p tcp --dport $ADB_PORT -j DROP 2>/dev/null; true")
            exec("iptables -D INPUT -p tcp --dport $ADB_PORT -j ACCEPT 2>/dev/null; true")
            Log.e(TAG, "adb TCP disabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to disable adb", e)
            false
        }
    }

    fun isAdbEnabled(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "getprop service.adb.tcp.port"))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output == "$ADB_PORT"
        } catch (e: Exception) {
            false
        }
    }

    fun isFirewallApplied(ip: String): Boolean {
        assert(isValidIp(ip)) { "invalid IP: $ip" }
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "iptables -L INPUT -n"))
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            output.contains(ip) && output.contains("DROP") && output.contains("$ADB_PORT")
        } catch (e: Exception) {
            false
        }
    }

    private const val BUILD_PROP = "/system/build.prop"
    private const val ADB_PORT_PROP = "service.adb.tcp.port"

    /** Pure: build sed command to set adb port in build.prop */
    fun buildPersistCommand(port: Int = ADB_PORT): String {
        return "mount -o remount,rw /system && " +
            "busybox sed -i 's/^$ADB_PORT_PROP=.*/$ADB_PORT_PROP=$port/' $BUILD_PROP && " +
            "mount -o remount,ro /system"
    }

    /** Read current adb port value from build.prop */
    fun getBuildPropAdbPort(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c",
                "grep '^$ADB_PORT_PROP=' $BUILD_PROP"))
            val output = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            output.substringAfter("=", "")
        } catch (e: Exception) {
            ""
        }
    }

    /** Write adb port to build.prop permanently + setprop + restart adbd for immediate effect */
    fun persistAdbPort(): List<String> {
        val logs = mutableListOf<String>()
        return try {
            val current = getBuildPropAdbPort()
            if (current == "$ADB_PORT") {
                logs.add("ADB: build.prop already has port $ADB_PORT")
            } else {
                exec(buildPersistCommand())
                logs.add("ADB: build.prop updated $ADB_PORT_PROP=$ADB_PORT (was $current)")
            }
            // Also apply immediately via setprop
            if (!isAdbEnabled()) {
                buildAdbEnableCommands().forEach { exec(it) }
                logs.add("ADB: setprop + adbd restarted")
            } else {
                logs.add("ADB: adbd already on port $ADB_PORT")
            }
            logs
        } catch (e: Exception) {
            logs.add("ADB: persist error — ${e.message}")
            logs
        }
    }

    /** Boot-time check: only reapply iptables (build.prop handles adb port) */
    fun ensureAdbConfigured(ctx: Context): List<String> {
        val ip = getSavedIp(ctx)
        if (ip.isEmpty()) return listOf("ADB: no IP configured, skip")
        val logs = mutableListOf<String>()
        return try {
            // Check runtime port first — never restart adbd if already on correct port
            val adbAlive = isAdbEnabled()
            val propPort = getBuildPropAdbPort()
            if (propPort != "$ADB_PORT") {
                exec(buildPersistCommand())
                logs.add("ADB: build.prop was '$propPort', fixed to $ADB_PORT")
            } else {
                logs.add("ADB: build.prop port=$ADB_PORT OK")
            }
            if (adbAlive) {
                logs.add("ADB: adbd already on port $ADB_PORT, skip restart")
            } else {
                buildAdbEnableCommands().forEach { exec(it) }
                logs.add("ADB: adbd not on $ADB_PORT, setprop + restarted")
            }
            // Iptables don't survive reboot — always reapply
            if (isFirewallApplied(ip)) {
                logs.add("ADB: iptables already applied for $ip")
            } else {
                buildFirewallCommands(ip).forEach { exec(it) }
                logs.add("ADB: iptables applied for $ip")
            }
            logs
        } catch (e: Exception) {
            logs.add("ADB: error — ${e.message}")
            logs
        }
    }

    private fun exec(cmd: String) {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        proc.waitFor()
        val err = proc.errorStream.bufferedReader().readText()
        if (err.isNotBlank()) Log.e(TAG, "cmd='$cmd' stderr=$err")
    }
}
