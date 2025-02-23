package dev.offing.panopticon

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.googlecode.jsonrpc4j.JsonRpcClient
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import javax.net.ssl.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.core.Utils
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL
import java.net.HttpURLConnection

data class ElectrumServer(val host: String, val port: Int, val useSSL: Boolean)

class ElectrumClient(context: Context) {
    private val TAG = "ElectrumClient"
    private val objectMapper = jacksonObjectMapper()
    
    // SharedPreferences for storing the transaction cache and exchange rate
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences("transaction_cache", Context.MODE_PRIVATE)
    
    // Exchange rate cache
    private var btcUsdRate: Double? = null
    private var lastRateUpdateTime: Long = 0
    
    // Load the transaction cache from SharedPreferences
    var transactionCache: MutableMap<String, Int> = loadTransactionCache()
        private set
    
    // Load transaction cache from SharedPreferences
    private fun loadTransactionCache(): MutableMap<String, Int> {
        val json = sharedPreferences.getString("transaction_cache", "{}")
        return objectMapper.readValue(json ?: "{}", object : TypeReference<MutableMap<String, Int>>() {})
    }

    // Save transaction cache to SharedPreferences
    private fun saveTransactionCache() {
        val editor = sharedPreferences.edit()
        val json = objectMapper.writeValueAsString(transactionCache)
        editor.putString("transaction_cache", json)
        editor.apply()
    }

    fun logTransactionCache() {
        val json = sharedPreferences.getString("transaction_cache", "{}")
        Log.d(TAG, "Transaction Cache: $json")
    }
    
    // Fetch BTC to USD exchange rate
    suspend fun getBtcToUsdRate(forceRefresh: Boolean = false): Double? {
        // Use cached rate if it's recent (less than 15 minutes old) and not forced to refresh
        val now = System.currentTimeMillis()
        if (!forceRefresh && btcUsdRate != null && (now - lastRateUpdateTime < 15 * 60 * 1000)) {
            Log.d(TAG, "Using cached BTC-USD rate: $btcUsdRate")
            return btcUsdRate
        }
        
        return withContext(Dispatchers.IO) {
            try {
                // Free API from CoinGecko
                val url = URL("https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "BTC-USD rate response: $response")
                    
                    val responseMap: Map<String, Map<String, Double>> = objectMapper.readValue(
                        response,
                        object : TypeReference<Map<String, Map<String, Double>>>() {}
                    )
                    
                    val rate = responseMap["bitcoin"]?.get("usd")
                    if (rate != null) {
                        // Cache the rate
                        btcUsdRate = rate
                        lastRateUpdateTime = now
                        
                        Log.d(TAG, "Updated BTC-USD rate: $rate")
                        return@withContext rate
                    }
                } else {
                    Log.e(TAG, "Error fetching BTC-USD rate: HTTP $responseCode")
                }
                
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching BTC-USD rate", e)
                null
            }
        }
    }
    
    // Convert BTC to USD
    suspend fun convertBtcToUsd(btcAmount: Double, forceRefresh: Boolean = false): Double? {
        val rate = getBtcToUsdRate(forceRefresh) ?: return null
        return btcAmount * rate
    }

    // List of Electrum servers
    private val electrumServers = listOf(
        ElectrumServer("100.64.0.1", 50001, false) // Non-SSL server
        // ElectrumServer("100.64.0.2", 5000, false) // Non-SSL server
        // https://github.com/spesmilo/electrum/blob/e1377c9856cd3848571432c2ab07c46a14987134/electrum/chains/servers.json
        // TODO: write in documentation that there are onion services here so taht's cool for extra privacy
        // ElectrumServer("104.248.139.211", 50002, true)
    )

    private fun getUnsafeSSLSocketFactory(): SSLSocketFactory {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())
        return sslContext.socketFactory
    }

    private fun addressToScripthash(address: String): String? {
        return try {
            val params: NetworkParameters = MainNetParams.get()
            val addr = Address.fromString(params, address)
            val script: Script = ScriptBuilder.createOutputScript(addr)
            val scriptBytes = script.program
            val sha256 = MessageDigest.getInstance("SHA-256").digest(scriptBytes)
            Utils.HEX.encode(sha256.reversedArray())
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Bitcoin address: $address", e)
            null
        }
    }

    // Fetch balance for an address
    suspend fun getAddressBalance(address: String): Double? {
        val scripthash = addressToScripthash(address) ?: return null
        val server = electrumServers.random()
        Log.d(TAG, "Getting balance using server: ${server.host}:${server.port} (SSL: ${server.useSSL})")
        
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = connectToServer(server)
                if (socket == null) return@withContext null
                
                val outputStream: OutputStream = socket.getOutputStream()
                val inputStream: InputStream = socket.getInputStream()
                
                // Create JSON-RPC request for balance
                val request = mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "blockchain.scripthash.get_balance",
                    "params" to listOf(scripthash),
                    "id" to 1
                )
                val requestJson = objectMapper.writeValueAsString(request)
                Log.d(TAG, "Sending balance request: $requestJson")
                
                // Add newline to request to ensure proper framing
                outputStream.write((requestJson + "\n").toByteArray())
                outputStream.flush()
                
                // Read the response
                val responseBuilder = StringBuilder()
                val buffer = ByteArray(4096)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = String(buffer, 0, bytesRead)
                    responseBuilder.append(chunk)
                    
                    // Check if we have a complete JSON object
                    try {
                        val response: Map<String, Any> = objectMapper.readValue(
                            responseBuilder.toString(),
                            object : TypeReference<Map<String, Any>>() {}
                        )
                        
                        val result = response["result"] as? Map<String, Any>
                        if (result != null) {
                            val confirmed = result["confirmed"] as? Number ?: 0
                            val unconfirmed = result["unconfirmed"] as? Number ?: 0
                            
                            // Convert satoshis to BTC (1 BTC = 100,000,000 satoshis)
                            val totalSatoshis = confirmed.toLong() + unconfirmed.toLong()
                            val btcBalance = totalSatoshis.toDouble() / 100_000_000.0
                            
                            Log.d(TAG, "Address $address balance: $btcBalance BTC (confirmed: ${confirmed.toLong() / 100_000_000.0}, unconfirmed: ${unconfirmed.toLong() / 100_000_000.0})")
                            return@withContext btcBalance
                        }
                        null
                    } catch (e: Exception) {
                        // Continue reading if JSON is not complete
                        continue
                    }
                }
                
                Log.w(TAG, "End of stream reached without complete balance response")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching address balance", e)
                null
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing socket", e)
                }
            }
        }
    }
    
    private fun connectToServer(server: ElectrumServer): Socket? {
        return try {
            Log.d(TAG, "Attempting to connect to server...")
            val socket = if (server.useSSL) {
                Log.d(TAG, "Creating SSL socket...")
                val socketFactory = getUnsafeSSLSocketFactory()
                socketFactory.createSocket(server.host, server.port)
            } else {
                Log.d(TAG, "Creating plain socket...")
                Socket(server.host, server.port)
            }
            
            // Set socket timeouts
            socket.soTimeout = 30000 // Read timeout: 30 seconds
            socket.keepAlive = true
            Log.d(TAG, "Socket connected successfully")
            socket
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server: ${e.message}")
            null
        }
    }

    // Fetch transaction history using jsonrpc4j
    suspend fun getTransactionHistory(address: String): List<Map<String, Any>>? {
        val scripthash = addressToScripthash(address) ?: return null
        val server = electrumServers.random()
        Log.d(TAG, "Using server: ${server.host}:${server.port} (SSL: ${server.useSSL})")
        Log.d(TAG, "Scripthash for address $address: $scripthash")

        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = connectToServer(server)
                if (socket == null) return@withContext null

                val outputStream: OutputStream = socket.getOutputStream()
                val inputStream: InputStream = socket.getInputStream()
                Log.d(TAG, "Streams created successfully")

                val jsonRpcClient = JsonRpcClient(objectMapper)

                // Create JSON-RPC request
                val request = mapOf(
                    "jsonrpc" to "2.0",
                    "method" to "blockchain.scripthash.get_history",
                    "params" to listOf(scripthash),
                    "id" to 1
                )
                val requestJson = objectMapper.writeValueAsString(request)
                Log.d(TAG, "Sending JSON-RPC request: $requestJson")

                // Add newline to request to ensure proper framing
                outputStream.write((requestJson + "\n").toByteArray())
                outputStream.flush()
                Log.d(TAG, "Request sent with newline, waiting for response...")

                // Read the response directly from the InputStream
                val responseBuilder = StringBuilder()
                val buffer = ByteArray(4096)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    val chunk = String(buffer, 0, bytesRead)
                    Log.d(TAG, "Received chunk: $chunk")
                    responseBuilder.append(chunk)
                    
                    // Check if we have a complete JSON object
                    try {
                        val response: Map<String, Any> = objectMapper.readValue(
                            responseBuilder.toString(),
                            object : TypeReference<Map<String, Any>>() {}
                        )
                        Log.d(TAG, "Successfully parsed complete response: $response")

                        val result = response["result"] as? List<MutableMap<String, Any>>
                        if (result != null) {
                            val newTransactions = mutableListOf<Map<String, Any>>()
                            for (transaction in result) {
                                val txHash = transaction["tx_hash"] as String
                                val height = transaction["height"] as? Int ?: 0

                                // Check if the transaction is new or has changed height
                                if (transactionCache[txHash] != height) {
                                    transactionCache[txHash] = height
                                    transaction["status"] = if (height > 0) "confirmed" else "mempool"
                                    newTransactions.add(transaction)
                                }
                            }

                            saveTransactionCache() // Save the updated cache
                            return@withContext newTransactions
                        }
                        null
                    } catch (e: Exception) {
                        // Continue reading if JSON is not complete
                        Log.d(TAG, "Incomplete JSON, continuing to read...")
                        continue
                    }
                }
                
                Log.w(TAG, "End of stream reached without complete response. Partial response: ${responseBuilder.toString()}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching transaction history", e)
                null
            } finally {
                try {
                    socket?.close()
                    Log.d(TAG, "Socket closed successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing socket", e)
                }
            }
        }
    }
}
