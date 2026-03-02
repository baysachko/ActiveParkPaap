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

    /** Pure: commands to disable adb TCP */
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
            .edit().putString(KEY_SERVER_IP, ip).apply()
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

    private fun exec(cmd: String) {
        val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        proc.waitFor()
        val err = proc.errorStream.bufferedReader().readText()
        if (err.isNotBlank()) Log.e(TAG, "cmd='$cmd' stderr=$err")
    }
}
