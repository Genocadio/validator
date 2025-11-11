package com.gocavgo.validator.nfc

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag


import android.os.Bundle
import com.gocavgo.validator.util.Logging

class NFCReaderHelper(
    private val context: Context,
    private val onTagRead: (Tag) -> Unit,
    private val onError: (String) -> Unit
) {
    private val nfcAdapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    fun isNfcSupported(): Boolean = nfcAdapter != null

    fun isNfcEnabled(): Boolean = nfcAdapter?.isEnabled == true

    fun enableNfcReader(activity: Activity) {
        if (nfcAdapter != null && isNfcEnabled()) {
            nfcAdapter.enableReaderMode(
                activity,
                NfcAdapter.ReaderCallback { tag ->
                    tag?.let { onTagRead(it) } ?: onError("Tag not found.")
                },
                NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                Bundle()
            )
        } else {
            onError("NFC is not enabled or supported.")
        }
    }

    fun disableNfcReader(activity: Activity) {
        try {
            nfcAdapter?.disableReaderMode(activity)
        } catch (e: Exception) {
            // Activity might be destroyed, ignore the error
            Logging.w("NFCReaderHelper", "Error disabling NFC reader: ${e.message}")
        }
    }
}