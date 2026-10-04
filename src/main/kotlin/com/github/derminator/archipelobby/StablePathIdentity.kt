package com.github.derminator.archipelobby

import com.sun.jna.Native
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

data class StableFileIdentity(
    val volumeSerialNumber: Int,
    val fileIndexHigh: Int,
    val fileIndexLow: Int,
)

/** Returns a stable identity for an existing path, failing closed when the provider cannot supply one. */
fun stableFileIdentity(path: Path): Any {
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        .fileKey()
        ?.let { return it }

    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        throw SecurityException("File provider does not expose stable identity for $path")
    }

    val handle = Kernel32.INSTANCE.CreateFile(
        path.toAbsolutePath().normalize().toString(),
        0,
        FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE,
        null,
        WinNT.OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS,
        null,
    )
    if (handle == null || WinBase.INVALID_HANDLE_VALUE == handle) {
        throw SecurityException("Unable to open $path for identity: Win32 error ${Native.getLastError()}")
    }
    try {
        val information = ByHandleFileInformation()
        if (!Kernel32Identity.INSTANCE.GetFileInformationByHandle(handle, information)) {
            throw SecurityException("Unable to identify $path: Win32 error ${Native.getLastError()}")
        }
        return StableFileIdentity(
            information.dwVolumeSerialNumber.toInt(),
            information.nFileIndexHigh.toInt(),
            information.nFileIndexLow.toInt(),
        )
    } finally {
        Kernel32.INSTANCE.CloseHandle(handle)
    }
}

private const val FILE_SHARE_READ = 0x00000001
private const val FILE_SHARE_WRITE = 0x00000002
private const val FILE_SHARE_DELETE = 0x00000004
private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000

internal interface Kernel32Identity : StdCallLibrary {
    fun GetFileInformationByHandle(handle: WinNT.HANDLE, information: ByHandleFileInformation): Boolean

    companion object {
        val INSTANCE: Kernel32Identity = Native.load(
            "kernel32",
            Kernel32Identity::class.java,
            W32APIOptions.DEFAULT_OPTIONS,
        )
    }
}

@Structure.FieldOrder(
    "dwFileAttributes", "ftCreationTime", "ftLastAccessTime", "ftLastWriteTime",
    "dwVolumeSerialNumber", "nFileSizeHigh", "nFileSizeLow", "nNumberOfLinks",
    "nFileIndexHigh", "nFileIndexLow",
)
internal class ByHandleFileInformation : Structure() {
    @JvmField var dwFileAttributes = WinDef.DWORD()
    @JvmField var ftCreationTime = WinBase.FILETIME()
    @JvmField var ftLastAccessTime = WinBase.FILETIME()
    @JvmField var ftLastWriteTime = WinBase.FILETIME()
    @JvmField var dwVolumeSerialNumber = WinDef.DWORD()
    @JvmField var nFileSizeHigh = WinDef.DWORD()
    @JvmField var nFileSizeLow = WinDef.DWORD()
    @JvmField var nNumberOfLinks = WinDef.DWORD()
    @JvmField var nFileIndexHigh = WinDef.DWORD()
    @JvmField var nFileIndexLow = WinDef.DWORD()
}
