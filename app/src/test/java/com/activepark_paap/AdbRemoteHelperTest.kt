package com.activepark_paap

import org.junit.Assert.*
import org.junit.Test

class AdbRemoteHelperTest {

    // --- isValidIp: valid ---

    @Test fun `valid IP - standard private`() {
        assertTrue(AdbRemoteHelper.isValidIp("192.168.1.1"))
    }

    @Test fun `valid IP - class A private`() {
        assertTrue(AdbRemoteHelper.isValidIp("10.0.0.1"))
    }

    @Test fun `valid IP - max octets`() {
        assertTrue(AdbRemoteHelper.isValidIp("255.255.255.255"))
    }

    @Test fun `valid IP - all zeros`() {
        assertTrue(AdbRemoteHelper.isValidIp("0.0.0.0"))
    }

    // --- isValidIp: invalid ---

    @Test fun `invalid IP - alpha`() {
        assertFalse(AdbRemoteHelper.isValidIp("abc"))
    }

    @Test fun `invalid IP - three octets`() {
        assertFalse(AdbRemoteHelper.isValidIp("192.168.1"))
    }

    @Test fun `invalid IP - five octets`() {
        assertFalse(AdbRemoteHelper.isValidIp("192.168.1.1.1"))
    }

    @Test fun `invalid IP - empty`() {
        assertFalse(AdbRemoteHelper.isValidIp(""))
    }

    @Test fun `invalid IP - trailing dot`() {
        assertFalse(AdbRemoteHelper.isValidIp("192.168.1."))
    }

    @Test fun `invalid IP - octet over 255`() {
        assertFalse(AdbRemoteHelper.isValidIp("1234.1.1.1"))
    }

    @Test fun `invalid IP - octet 256`() {
        assertFalse(AdbRemoteHelper.isValidIp("256.0.0.1"))
    }

    // --- buildFirewallCommands ---

    @Test fun `firewall commands contain IP and port`() {
        val cmds = AdbRemoteHelper.buildFirewallCommands("10.0.0.5", 5555)
        assertEquals(4, cmds.size)
        assertTrue(cmds.all { "5555" in it })
        assertTrue(cmds.filter { "10.0.0.5" in it }.size >= 2)
        assertTrue(cmds[2].startsWith("iptables -I INPUT"))
        assertTrue(cmds[3].startsWith("iptables -A INPUT"))
    }

    @Test(expected = AssertionError::class)
    fun `firewall commands reject invalid IP`() {
        AdbRemoteHelper.buildFirewallCommands("bad", 5555)
    }

    // --- buildAdbEnableCommands ---

    @Test fun `enable commands - setprop stop start with port`() {
        val cmds = AdbRemoteHelper.buildAdbEnableCommands(5555)
        assertEquals(3, cmds.size)
        assertEquals("setprop service.adb.tcp.port 5555", cmds[0])
        assertEquals("stop adbd", cmds[1])
        assertEquals("start adbd", cmds[2])
    }

    // --- buildAdbDisableCommands ---

    @Test fun `disable commands - setprop -1 stop start`() {
        val cmds = AdbRemoteHelper.buildAdbDisableCommands(5555)
        assertEquals(3, cmds.size)
        assertEquals("setprop service.adb.tcp.port -1", cmds[0])
        assertEquals("stop adbd", cmds[1])
        assertEquals("start adbd", cmds[2])
    }

    // --- buildPersistCommand ---

    @Test fun `persist command - remount sed remount`() {
        val cmd = AdbRemoteHelper.buildPersistCommand(5555)
        assertTrue(cmd.contains("mount -o remount,rw /system"))
        assertTrue(cmd.contains("sed -i"))
        assertTrue(cmd.contains("service.adb.tcp.port=5555"))
        assertTrue(cmd.contains("mount -o remount,ro /system"))
    }
}
