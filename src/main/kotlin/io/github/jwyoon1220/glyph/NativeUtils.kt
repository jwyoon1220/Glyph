package io.github.jwyoon1220.glyph

import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Component

interface User32 : StdCallLibrary {
    companion object {
        val INSTANCE = Native.load("user32", User32::class.java, W32APIOptions.DEFAULT_OPTIONS) as User32
        
        const val MB_OK = 0x00000000
        const val MB_ICONERROR = 0x00000010
        const val MB_ICONWARNING = 0x00000030
        const val MB_ICONINFORMATION = 0x00000040
    }

    fun MessageBoxW(hWnd: HWND?, lpText: String, lpCaption: String, uType: Int): Int
}

object NativeUtils {
    fun showError(parent: Component?, title: String, message: String) {
        val hwnd = parent?.let { HWND(Native.getComponentPointer(it)) }
        User32.INSTANCE.MessageBoxW(hwnd, message, title, User32.MB_ICONERROR or User32.MB_OK)
    }
    
    fun showInfo(parent: Component?, title: String, message: String) {
        val hwnd = parent?.let { HWND(Native.getComponentPointer(it)) }
        User32.INSTANCE.MessageBoxW(hwnd, message, title, User32.MB_ICONINFORMATION or User32.MB_OK)
    }
}
