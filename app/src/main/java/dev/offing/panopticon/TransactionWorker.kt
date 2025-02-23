package dev.offing.panopticon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TransactionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val TAG = "TransactionWorker"
    private val electrumClient = ElectrumClient(context)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Send a notification to indicate polling
        val notificationId = System.currentTimeMillis().toInt()
        sendNotification("Polling Backend", "Checking for new transactions...", notificationId)

        // Get the address from input data
        val address = inputData.getString("address")
        
        if (address != null) {
            // Process single address from worker input
            processAddress(address, notificationId)
        } else {
            // If no specific address is provided in the input data,
            // we could implement a mechanism to check all saved addresses here
            // For now, just log a warning
            Log.w(TAG, "No address provided to worker")
            return@withContext Result.failure()
        }

        // Cancel the polling notification
        cancelNotification(notificationId)

        Result.success()
    }
    
    private suspend fun processAddress(address: String, notificationId: Int) {
        val transactionHistory = electrumClient.getTransactionHistory(address)

        if (transactionHistory != null) {
            val newMempoolTransactions = mutableListOf<String>()
            val newConfirmedTransactions = mutableListOf<String>()
            val confirmedFromMempoolTransactions = mutableListOf<String>()

            for (transaction in transactionHistory) {
                val txHash = transaction["tx_hash"] as? String
                val height = transaction["height"] as? Int ?: 0
                val previousHeight = electrumClient.transactionCache[txHash] ?: 0

                if (txHash != null) {
                    // New mempool transaction
                    if (previousHeight == 0 && height == 0) {
                        newMempoolTransactions.add(txHash)
                    }
                    // Mempool transaction got confirmed
                    if (previousHeight == 0 && height > 0) {
                        confirmedFromMempoolTransactions.add(txHash)
                    }
                    // New confirmed transaction
                    if (previousHeight == 0 && height > 0) {
                        newConfirmedTransactions.add(txHash)
                    }
                }
            }

            if (newMempoolTransactions.isNotEmpty()) {
                sendNotification(
                    "New Mempool Transactions for $address",
                    "New mempool transactions: ${newMempoolTransactions.joinToString(", ")}",
                    notificationId + 1
                )
            }

            if (confirmedFromMempoolTransactions.isNotEmpty()) {
                sendNotification(
                    "Transactions Confirmed for $address",
                    "Transactions confirmed from mempool: ${confirmedFromMempoolTransactions.joinToString(", ")}",
                    notificationId + 2
                )
            }

            if (newConfirmedTransactions.isNotEmpty()) {
                sendNotification(
                    "New Confirmed Transactions for $address",
                    "New confirmed transactions: ${newConfirmedTransactions.joinToString(", ")}",
                    notificationId + 3
                )
            }
        }
    }

    private fun sendNotification(title: String, message: String, notificationId: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "transaction_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Transaction Notifications", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setAutoCancel(true) // Make the notification dismissible
            .build()

        notificationManager.notify(notificationId, notification)
    }

    private fun cancelNotification(notificationId: Int) {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notificationId)
    }
}
