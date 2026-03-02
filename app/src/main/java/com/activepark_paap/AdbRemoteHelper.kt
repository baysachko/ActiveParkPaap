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

    fun getSavedIp(ctx: Context): String {
        val ip = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER_IP, "") ?: ""
        assert(ip.isEmpty() || ip.matches(Regex("^[0-9.]+$"))) { "invalid saved IP: $ip" }
        return ip
    }

    fun saveIp(ctx: Context, ip: String) {
        assert(ip.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) { "invalid IP format: $ip" }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER_IP, ip).apply()
    }

    /**
     * Enable adb TCP + lock down with iptables. Call on boot or after saving IP.
     * Returns true if server IP was configured and commands executed.
     */
    fun enableAdbWithFirewall(ctx: Context): Boolean {
        val ip = getSavedIp(ctx)
        if (ip.isEmpty()) {
            Log.e(TAG, "no server IP configured, skipping adb enable")
            return false
        }
        return enableAdbWithFirewall(ip)
    }

    fun enableAdbWithFirewall(ip: String): Boolean {
        assert(ip.matches(Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$"))) { "invalid IP: $ip" }
        return try {
            // Only restart adbd if not already on TCP — avoids killing active scrcpy sessions
            if (!isAdbEnabled()) {
                exec("setprop service.adb.tcp.port $ADB_PORT")
                exec("stop adbd")
                exec("start adbd")
            }
            // Firewall: flush old rules for adb port, allow only server IP
            exec("iptables -D INPUT -p tcp --dport $ADB_PORT -j DROP 2>/dev/null; true")
            exec("iptables -D INPUT -p tcp --dport $ADB_PORT -s $ip -j ACCEPT 2>/dev/null; true")
            exec("iptables -I INPUT -p tcp --dport $ADB_PORT -s $ip -j ACCEPT")
            exec("iptables -A INPUT -p tcp --dport $ADB_PORT -j DROP")
            Log.e(TAG, "adb TCP enabled, locked to $ip")
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to enable adb", e)
            false
        }
    }

    fun disableAdb(): Boolean {
        return try {
            exec("setprop service.adb.tcp.port -1")
            exec("stop adbd")
            exec("start adbd")
            // Clean up iptables rules
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
