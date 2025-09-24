package com.gocavgo.validator

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment

class MapDownloadProgressDialog : DialogFragment() {
    
    companion object {
        private const val TAG = "MapDownloadProgressDialog"
        
        fun newInstance(): MapDownloadProgressDialog {
            return MapDownloadProgressDialog()
        }
    }
    
    // UI Elements
    var statusText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var progressText: TextView? = null
    private var sizeText: TextView? = null
    private var detailsSection: LinearLayout? = null
    var detailsText: TextView? = null
    private var cancelButton: Button? = null
    private var retryButton: Button? = null
    private var okButton: Button? = null
    
    // State
    private var isCancellable = false
    private var onCancelListener: (() -> Unit)? = null
    private var onRetryListener: (() -> Unit)? = null
    private var onOkListener: (() -> Unit)? = null
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_map_download_progress, null)
        
        setupViews(view)
        setupListeners()
        
        builder.setView(view)
        val dialog = builder.create()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        return dialog
    }
    
    private fun setupViews(view: View) {
        statusText = view.findViewById(R.id.statusText)
        progressBar = view.findViewById(R.id.progressBar)
        progressText = view.findViewById(R.id.progressText)
        sizeText = view.findViewById(R.id.sizeText)
        detailsSection = view.findViewById(R.id.detailsSection)
        detailsText = view.findViewById(R.id.detailsText)
        cancelButton = view.findViewById(R.id.cancelButton)
        retryButton = view.findViewById(R.id.retryButton)
        okButton = view.findViewById(R.id.okButton)
    }
    
    private fun setupListeners() {
        cancelButton?.setOnClickListener {
            onCancelListener?.invoke()
            dismiss()
        }
        
        retryButton?.setOnClickListener {
            onRetryListener?.invoke()
        }
        
        okButton?.setOnClickListener {
            onOkListener?.invoke()
            dismiss()
        }
    }
    
    fun setOnCancelListener(listener: () -> Unit) {
        onCancelListener = listener
    }
    
    fun setOnRetryListener(listener: () -> Unit) {
        onRetryListener = listener
    }
    
    fun setOnOkListener(listener: () -> Unit) {
        onOkListener = listener
    }
    
    fun showCheckingForUpdates() {
        statusText?.text = "Checking for map updates..."
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.GONE
        hideAllButtons()
    }
    
    fun showDownloading(progress: Int, totalSizeMB: Int) {
        statusText?.text = "Downloading map data..."
        progressBar?.visibility = View.VISIBLE
        progressText?.visibility = View.VISIBLE
        sizeText?.visibility = View.VISIBLE
        detailsSection?.visibility = View.VISIBLE
        
        progressBar?.progress = progress
        progressText?.text = "$progress%"
        sizeText?.text = "${totalSizeMB} MB"
        detailsText?.text = "Downloading Rwanda map data for offline navigation..."
        
        if (isCancellable) {
            cancelButton?.visibility = View.VISIBLE
        }
    }
    
    fun showRetrying(attempt: Int, maxAttempts: Int) {
        statusText?.text = "Retrying download... (Attempt $attempt/$maxAttempts)"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Previous download failed. Retrying in a few seconds..."
        hideAllButtons()
    }
    
    fun showNetworkWaiting() {
        statusText?.text = "Waiting for network connection..."
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "No internet connection available. Will retry when network is restored."
        hideAllButtons()
    }
    
    fun showError(errorMessage: String) {
        statusText?.text = "Download failed"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = errorMessage
        retryButton?.visibility = View.VISIBLE
        okButton?.visibility = View.VISIBLE
    }
    
    fun showSuccess() {
        statusText?.text = "Map data ready!"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Rwanda map data has been downloaded successfully. Offline navigation is now available."
        okButton?.visibility = View.VISIBLE
    }
    
    fun showAlreadyReady() {
        statusText?.text = "Map data ready"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Rwanda map data is already available. Offline navigation is ready."
        okButton?.visibility = View.VISIBLE
    }
    
    fun showRepairing(attempt: Int, maxAttempts: Int) {
        statusText?.text = "Repairing map data... (Attempt $attempt/$maxAttempts)"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Detected corrupted map data. Attempting to repair..."
        hideAllButtons()
    }
    
    fun showRepairSuccess() {
        statusText?.text = "Map data repaired!"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Map data has been successfully repaired. Offline navigation is now available."
        okButton?.visibility = View.VISIBLE
    }
    
    fun showRepairFailed(errorMessage: String) {
        statusText?.text = "Repair failed"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = errorMessage
        retryButton?.visibility = View.VISIBLE
        okButton?.visibility = View.VISIBLE
    }
    
    fun showClearingData() {
        statusText?.text = "Clearing corrupted data..."
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Repair not possible. Clearing corrupted map data and will download fresh data."
        hideAllButtons()
    }
    
    fun showPaused() {
        statusText?.text = "Download paused (app in background)"
        progressBar?.visibility = View.GONE
        progressText?.visibility = View.GONE
        sizeText?.visibility = View.GONE
        detailsSection?.visibility = View.VISIBLE
        detailsText?.text = "Map download has been paused. Will resume when app returns to foreground."
        hideAllButtons()
    }
    
    fun setCancellable(cancellable: Boolean) {
        isCancellable = cancellable
        if (cancellable && progressBar?.visibility == View.VISIBLE) {
            cancelButton?.visibility = View.VISIBLE
        }
    }
    
    private fun hideAllButtons() {
        cancelButton?.visibility = View.GONE
        retryButton?.visibility = View.GONE
        okButton?.visibility = View.GONE
    }
    
    fun updateProgress(progress: Int, totalSizeMB: Long) {
        if (progressBar?.visibility == View.VISIBLE) {
            progressBar?.progress = progress
            progressText?.text = "$progress%"
            sizeText?.text = "${totalSizeMB} MB"
        }
    }
}
