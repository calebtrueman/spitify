package com.localfy.app.data

import android.app.backup.BackupAgentHelper
import android.app.backup.FullBackupDataOutput
import com.localfy.app.data.social.PeerIdentity
import java.io.File

/** Settings travel through Android's own backup service; no Spitify account or server. */
class DeviceBackupAgent : BackupAgentHelper() {
    override fun onFullBackup(data: FullBackupDataOutput) {
        val identity = File(filesDir, "os_backup/friend_identity")
        val secure = data.transportFlags and (FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED or FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0
        try {
            if (secure) {
                identity.parentFile?.mkdirs()
                identity.writeText(PeerIdentity.load(this).secretKey().toHex())
            }
            super.onFullBackup(data)
        } finally { identity.delete(); identity.parentFile?.delete() }
    }
    override fun onRestoreFinished() {
        val identity = File(filesDir, "os_backup/friend_identity")
        if (identity.isFile) {
            try { PeerIdentity.restore(this, identity.readText().trim()) }
            finally { identity.delete(); identity.parentFile?.delete() }
        }
        // Saved folder access belongs to the old install. Ask for access again when needed.
        android.app.backup.BackupManager(this).dataChanged()
    }
}
