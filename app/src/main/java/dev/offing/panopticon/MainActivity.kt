package dev.offing.panopticon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.work.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.core.type.TypeReference
import java.util.concurrent.TimeUnit

// Extended public key class for managing xpub/zpub
data class ExtendedPublicKey(
    val key: String,
    val label: String = "",
    val derivedAddressCount: Int = 10,
    val startIndex: Int = 0
)

data class BitcoinAddress(
    val address: String,
    val label: String = "",
    val transactions: List<String>? = null,
    val balance: Double? = null,
    val usdBalance: Double? = null,
    // For derived addresses
    val parentExtPubKey: String? = null,
    val addressIndex: Int? = null,
    val isDerivedAddress: Boolean = false
)

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.d("MainActivity", "Notification permission granted")
        } else {
            Log.d("MainActivity", "Notification permission denied")
        }
    }
    
    private val objectMapper = jacksonObjectMapper()
    private val hdWalletManager = HDWalletManager()
    
    // SharedPreferences keys
    private val ADDRESS_PREFS = "bitcoin_addresses"
    private val EXTPUBKEY_PREFS = "extended_pubkeys"
    private val ADDRESSES_KEY = "addresses"
    private val EXTPUBKEYS_KEY = "extpubkeys"

    // Save addresses to SharedPreferences
    fun saveAddresses(addresses: List<BitcoinAddress>) {
        val sharedPreferences = getSharedPreferences(ADDRESS_PREFS, Context.MODE_PRIVATE)
        
        // Convert transactions to null before saving to avoid storing large amounts of data
        val addressesToSave = addresses.map { it.copy(transactions = null) }
        
        val json = objectMapper.writeValueAsString(addressesToSave)
        sharedPreferences.edit().putString(ADDRESSES_KEY, json).apply()
        Log.d("MainActivity", "Saved addresses: $json")
    }

    // Load addresses from SharedPreferences
    fun loadAddresses(): List<BitcoinAddress> {
        val sharedPreferences = getSharedPreferences(ADDRESS_PREFS, Context.MODE_PRIVATE)
        val json = sharedPreferences.getString(ADDRESSES_KEY, null) ?: return emptyList()
        
        return try {
            val addresses: List<BitcoinAddress> = objectMapper.readValue(
                json,
                object : TypeReference<List<BitcoinAddress>>() {}
            )
            Log.d("MainActivity", "Loaded addresses: $addresses")
            addresses
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading addresses", e)
            emptyList()
        }
    }
    
    // Save extended public keys to SharedPreferences
    fun saveExtendedPublicKeys(extPubKeys: List<ExtendedPublicKey>) {
        val sharedPreferences = getSharedPreferences(EXTPUBKEY_PREFS, Context.MODE_PRIVATE)
        val json = objectMapper.writeValueAsString(extPubKeys)
        sharedPreferences.edit().putString(EXTPUBKEYS_KEY, json).apply()
        Log.d("MainActivity", "Saved extended public keys: $json")
    }

    // Load extended public keys from SharedPreferences
    fun loadExtendedPublicKeys(): List<ExtendedPublicKey> {
        val sharedPreferences = getSharedPreferences(EXTPUBKEY_PREFS, Context.MODE_PRIVATE)
        val json = sharedPreferences.getString(EXTPUBKEYS_KEY, null) ?: return emptyList()
        
        return try {
            val extPubKeys: List<ExtendedPublicKey> = objectMapper.readValue(
                json,
                object : TypeReference<List<ExtendedPublicKey>>() {}
            )
            Log.d("MainActivity", "Loaded extended public keys: $extPubKeys")
            extPubKeys
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading extended public keys", e)
            emptyList()
        }
    }
    
    // Generate addresses from an extended public key
    fun generateAddressesFromExtPubKey(extPubKey: ExtendedPublicKey): List<BitcoinAddress> {
        val addresses = hdWalletManager.deriveAddresses(
            extPubKey.key,
            extPubKey.startIndex,
            extPubKey.derivedAddressCount
        )
        
        return addresses.mapIndexed { index, address ->
            BitcoinAddress(
                address = address,
                label = "${extPubKey.label} #${extPubKey.startIndex + index}",
                isDerivedAddress = true,
                parentExtPubKey = extPubKey.key,
                addressIndex = extPubKey.startIndex + index
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BitcoinNotifierScreen()
        }
        
        // Check and request notification permission
        checkAndRequestNotificationPermission()
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    // Permission is granted
                    Log.d("MainActivity", "Notification permission already granted")
                }
                else -> {
                    // Request the permission
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}

@Composable
fun BitcoinNotifierScreen() {
    val context = LocalContext.current
    val activity = context as MainActivity
    val electrumClient = remember { ElectrumClient(context) }
    val hdWalletManager = remember { HDWalletManager() }
    
    // Load addresses and extended public keys from SharedPreferences
    val loadedAddresses = remember { activity.loadAddresses() }
    val loadedExtPubKeys = remember { activity.loadExtendedPublicKeys() }
    
    // Generate addresses from extended public keys
    val derivedAddresses = remember {
        loadedExtPubKeys.flatMap { extPubKey ->
            activity.generateAddressesFromExtPubKey(extPubKey)
        }
    }
    
    // Initial address if no addresses are loaded
    val initialAddresses = if (loadedAddresses.isNotEmpty() || derivedAddresses.isNotEmpty()) {
        loadedAddresses + derivedAddresses
    } else {
        listOf(BitcoinAddress("3J56TVL9WQ8dk7MFFxp4EB3N8NxArUqeWL"))
    }
    
    // List of extended public keys
    var extendedPublicKeys by remember { mutableStateOf(loadedExtPubKeys) }
    
    // List of addresses
    var addresses by remember { mutableStateOf(initialAddresses) }
    
    // Function to regenerate addresses from extended public keys
    val regenerateAddresses = {
        val newDerivedAddresses = extendedPublicKeys.flatMap { extPubKey ->
            activity.generateAddressesFromExtPubKey(extPubKey)
        }
        
        // Filter out derived addresses to keep only manually added addresses
        val manualAddresses = addresses.filter { !it.isDerivedAddress }
        addresses = manualAddresses + newDerivedAddresses
    }
    
    // Selected address index
    var selectedAddressIndex by remember { mutableStateOf(0) }
    
    // New address input
    var newAddressInput by remember { mutableStateOf("") }
    
    // New address label
    var newAddressLabel by remember { mutableStateOf("") }
    
    // Show add address dialog
    var showAddAddressDialog by remember { mutableStateOf(false) }
    
    // For extended public key
    var newExtPubKeyInput by remember { mutableStateOf("") }
    var newExtPubKeyLabel by remember { mutableStateOf("") }
    var newExtPubKeyCount by remember { mutableStateOf("10") }
    var showAddExtPubKeyDialog by remember { mutableStateOf(false) }
    
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Bitcoin Transaction Notifier",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        
        // Address list
        Text(
            text = "Monitored Addresses:",
            style = MaterialTheme.typography.titleMedium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            modifier = Modifier
                .weight(0.3f)
                .fillMaxWidth()
        ) {
            items(addresses) { address ->
                val isSelected = addresses.indexOf(address) == selectedAddressIndex
                
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    elevation = CardDefaults.cardElevation(if (isSelected) 8.dp else 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .clickable { selectedAddressIndex = addresses.indexOf(address) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (address.label.isNotEmpty()) address.label else "Address ${addresses.indexOf(address) + 1}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                
                                // Display balance if available
                                if (address.balance != null) {
                                    Column(
                                        horizontalAlignment = Alignment.End
                                    ) {
                                        Text(
                                            text = "${String.format("%.8f", address.balance)} BTC",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        
                                        if (address.usdBalance != null) {
                                            Text(
                                                text = "$${String.format("%,.2f", address.usdBalance)} USD",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }
                                }
                            }
                            
                            Text(
                                text = address.address,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        // Delete button for all but the first address
                        if (addresses.size > 1) {
                            IconButton(
                                onClick = {
                                    val updatedAddresses = addresses.filterIndexed { index, _ -> index != addresses.indexOf(address) }
                                    addresses = updatedAddresses
                                    if (selectedAddressIndex >= addresses.size) {
                                        selectedAddressIndex = addresses.size - 1
                                    }
                                    
                                    // Save updated addresses to SharedPreferences
                                    activity.saveAddresses(updatedAddresses)
                                }
                            ) {
                                Text("×", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            }
            
            // Add buttons
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showAddAddressDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("+")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add Address")
                        }
                    }
                    
                    Button(
                        onClick = { showAddExtPubKeyDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text("+")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add xPub/zPub")
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Selected address details
        val selectedAddress = addresses.getOrNull(selectedAddressIndex)
        
        if (selectedAddress != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedAddress.label.isNotEmpty()) 
                        "Address: ${selectedAddress.label}" 
                    else 
                        "Address ${selectedAddressIndex + 1}",
                    style = MaterialTheme.typography.titleMedium
                )
                
                // Display balance if available
                if (selectedAddress.balance != null) {
                    Column(
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = "${String.format("%.8f", selectedAddress.balance)} BTC",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        if (selectedAddress.usdBalance != null) {
                            Text(
                                text = "$${String.format("%,.2f", selectedAddress.usdBalance)} USD",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            OutlinedTextField(
                value = selectedAddress.address,
                onValueChange = { newAddress ->
                    val updatedAddresses = addresses.toMutableList().apply {
                        this[selectedAddressIndex] = selectedAddress.copy(address = newAddress)
                    }
                    addresses = updatedAddresses
                    
                    // Save updated addresses to SharedPreferences
                    activity.saveAddresses(updatedAddresses)
                },
                label = { Text("Bitcoin Address") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val address = selectedAddress.address
                    if (address.isNotEmpty()) {
                        Log.d("BitcoinNotifierScreen", "Button clicked with address: $address")
                        // Schedule background worker
                        val workRequest = PeriodicWorkRequestBuilder<TransactionWorker>(5, TimeUnit.MINUTES)
                            .setInputData(workDataOf("address" to address))
                            .build()

                        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                            "TransactionWorker",
                            ExistingPeriodicWorkPolicy.REPLACE,
                            workRequest
                        )

                        coroutineScope.launch {
                            try {
                                Log.d("BitcoinNotifierScreen", "Starting network request...")
                                
                                // Fetch balance first
                                val balance = electrumClient.getAddressBalance(address)
                                Log.d("BitcoinNotifierScreen", "Address balance: $balance BTC")
                                
                                // Convert to USD if balance is available
                                var usdBalance: Double? = null
                                if (balance != null) {
                                    usdBalance = electrumClient.convertBtcToUsd(balance)
                                    Log.d("BitcoinNotifierScreen", "USD balance: $usdBalance")
                                }
                                
                                // Then fetch transaction history
                                val transactionHistory = electrumClient.getTransactionHistory(address)
                                Log.d("BitcoinNotifierScreen", "Raw transaction history: $transactionHistory")
                                    
                                val transactions = transactionHistory?.mapNotNull { transaction ->
                                    val txHash = transaction["tx_hash"] as? String
                                    val height = transaction["height"] as? Int
                                    val status = if (height != null && height > 0) "confirmed" else "mempool"
                                    if (txHash != null) {
                                        "$txHash ($status)"
                                    } else {
                                        null
                                    }
                                }
                                
                                val updatedAddresses = addresses.toMutableList().apply {
                                    this[selectedAddressIndex] = selectedAddress.copy(
                                        transactions = transactions,
                                        balance = balance,
                                        usdBalance = usdBalance
                                    )
                                }
                                addresses = updatedAddresses
                                
                                // Save updated addresses to SharedPreferences
                                activity.saveAddresses(updatedAddresses)
                                
                                Log.d("BitcoinNotifierScreen", "Processed transactions: $transactions, balance: $balance")
                            } catch (e: Exception) {
                                Log.e("BitcoinNotifierScreen", "Error fetching data", e)
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Data")
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Row with buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Refresh balance button
                Button(
                    onClick = {
                        val address = selectedAddress.address
                        if (address.isNotEmpty()) {
                            coroutineScope.launch {
                                try {
                                    // Just fetch balance
                                    val balance = electrumClient.getAddressBalance(address)
                                    Log.d("BitcoinNotifierScreen", "Address balance: $balance BTC")
                                    
                                    // Convert to USD if balance is available
                                    var usdBalance: Double? = null
                                    if (balance != null) {
                                        usdBalance = electrumClient.convertBtcToUsd(balance)
                                        Log.d("BitcoinNotifierScreen", "USD balance: $usdBalance")
                                    }
                                    
                                    val updatedAddresses = addresses.toMutableList().apply {
                                        this[selectedAddressIndex] = selectedAddress.copy(
                                            balance = balance,
                                            usdBalance = usdBalance
                                        )
                                    }
                                    addresses = updatedAddresses
                                    
                                    // Save updated addresses to SharedPreferences
                                    activity.saveAddresses(updatedAddresses)
                                } catch (e: Exception) {
                                    Log.e("BitcoinNotifierScreen", "Error fetching balance", e)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh Balance")
                }
                
                // Refresh exchange rate
                Button(
                    onClick = {
                        if (selectedAddress.balance != null) {
                            coroutineScope.launch {
                                try {
                                    // Force update exchange rate
                                    val usdBalance = electrumClient.convertBtcToUsd(selectedAddress.balance, forceRefresh = true)
                                    Log.d("BitcoinNotifierScreen", "Updated USD balance: $usdBalance")
                                    
                                    val updatedAddresses = addresses.toMutableList().apply {
                                        this[selectedAddressIndex] = selectedAddress.copy(
                                            usdBalance = usdBalance
                                        )
                                    }
                                    addresses = updatedAddresses
                                    
                                    // Save updated addresses to SharedPreferences
                                    activity.saveAddresses(updatedAddresses)
                                } catch (e: Exception) {
                                    Log.e("BitcoinNotifierScreen", "Error updating exchange rate", e)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Refresh USD Rate")
                }
            }
            
            // Debug button
            Button(
                onClick = {
                    electrumClient.logTransactionCache()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Log Cache")
            }

            Spacer(modifier = Modifier.height(16.dp))

            val transactions = selectedAddress.transactions
            when {
                transactions == null -> Text("Enter an address and tap 'Check Transactions' to fetch data.")
                transactions.isEmpty() -> Text("No transactions found for this address.")
                else -> TransactionList(transactions)
            }
        }
    }
    
    // Add Address Dialog
    if (showAddAddressDialog) {
        AlertDialog(
            onDismissRequest = { showAddAddressDialog = false },
            title = { Text("Add New Bitcoin Address") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newAddressLabel,
                        onValueChange = { newAddressLabel = it },
                        label = { Text("Label (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = newAddressInput,
                        onValueChange = { newAddressInput = it },
                        label = { Text("Bitcoin Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newAddressInput.isNotEmpty()) {
                            // Check if input is an extended public key
                            if (hdWalletManager.isExtendedPublicKey(newAddressInput)) {
                                // Switch to extended public key dialog
                                showAddAddressDialog = false
                                newExtPubKeyInput = newAddressInput
                                newExtPubKeyLabel = newAddressLabel
                                showAddExtPubKeyDialog = true
                                return@Button
                            }
                            
                            // Add new address
                            val newAddress = BitcoinAddress(
                                address = newAddressInput,
                                label = newAddressLabel
                            )
                            val updatedAddresses = addresses + newAddress
                            addresses = updatedAddresses
                            selectedAddressIndex = addresses.size - 1
                            newAddressInput = ""
                            newAddressLabel = ""
                            
                            // Save updated addresses to SharedPreferences
                            activity.saveAddresses(updatedAddresses)
                            
                            // Fetch balance for the new address
                            coroutineScope.launch {
                                try {
                                    val balance = electrumClient.getAddressBalance(newAddress.address)
                                    
                                    // Convert to USD if balance is available
                                    var usdBalance: Double? = null
                                    if (balance != null) {
                                        usdBalance = electrumClient.convertBtcToUsd(balance)
                                        Log.d("BitcoinNotifierScreen", "New address USD balance: $usdBalance")
                                    }
                                    
                                    if (balance != null) {
                                        // Update the just-added address with its balance
                                        val addressesWithBalance = addresses.toMutableList().apply {
                                            this[this.size - 1] = newAddress.copy(
                                                balance = balance,
                                                usdBalance = usdBalance
                                            )
                                        }
                                        addresses = addressesWithBalance
                                        activity.saveAddresses(addressesWithBalance)
                                    }
                                } catch (e: Exception) {
                                    Log.e("BitcoinNotifierScreen", "Error fetching initial balance", e)
                                }
                            }
                        }
                        showAddAddressDialog = false
                    }
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                Button(
                    onClick = { showAddAddressDialog = false }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Add Extended Public Key Dialog
    if (showAddExtPubKeyDialog) {
        AlertDialog(
            onDismissRequest = { showAddExtPubKeyDialog = false },
            title = { Text("Add Extended Public Key (xpub/ypub/zpub)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newExtPubKeyLabel,
                        onValueChange = { newExtPubKeyLabel = it },
                        label = { Text("Wallet Label (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    OutlinedTextField(
                        value = newExtPubKeyInput,
                        onValueChange = { newExtPubKeyInput = it },
                        label = { Text("Extended Public Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        minLines = 2
                    )
                    
                    OutlinedTextField(
                        value = newExtPubKeyCount,
                        onValueChange = { 
                            // Only allow numeric input
                            if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                newExtPubKeyCount = it 
                            }
                        },
                        label = { Text("Number of Addresses to Generate") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    
                    // Preview of the key type
                    if (newExtPubKeyInput.isNotEmpty() && hdWalletManager.isExtendedPublicKey(newExtPubKeyInput)) {
                        val keyType = hdWalletManager.generateLabelForExtendedKey(newExtPubKeyInput)
                        Text(
                            text = "Detected: $keyType",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newExtPubKeyInput.isNotEmpty() && 
                            hdWalletManager.isExtendedPublicKey(newExtPubKeyInput)) {
                            
                            // Create label if none was provided
                            val finalLabel = if (newExtPubKeyLabel.isEmpty()) {
                                hdWalletManager.generateLabelForExtendedKey(newExtPubKeyInput)
                            } else {
                                newExtPubKeyLabel
                            }
                            
                            // Parse count with fallback to 10
                            val count = newExtPubKeyCount.toIntOrNull() ?: 10
                            
                            // Add new extended public key
                            val newExtPubKey = ExtendedPublicKey(
                                key = newExtPubKeyInput,
                                label = finalLabel,
                                derivedAddressCount = count
                            )
                            
                            val updatedExtPubKeys = extendedPublicKeys + newExtPubKey
                            extendedPublicKeys = updatedExtPubKeys
                            
                            // Save updated extended public keys
                            activity.saveExtendedPublicKeys(updatedExtPubKeys)
                            
                            // Generate addresses from the new extended public key
                            val derivedAddresses = activity.generateAddressesFromExtPubKey(newExtPubKey)
                            
                            // Add to address list
                            val manualAddresses = addresses.filter { !it.isDerivedAddress }
                            addresses = manualAddresses + derivedAddresses
                            
                            // Select the first derived address
                            if (derivedAddresses.isNotEmpty()) {
                                selectedAddressIndex = manualAddresses.size
                            }
                            
                            // Clear inputs
                            newExtPubKeyInput = ""
                            newExtPubKeyLabel = ""
                            newExtPubKeyCount = "10"
                            
                            // Fetch balances for the derived addresses
                            coroutineScope.launch {
                                derivedAddresses.forEachIndexed { index, address ->
                                    try {
                                        val balance = electrumClient.getAddressBalance(address.address)
                                        var usdBalance: Double? = null
                                        if (balance != null) {
                                            usdBalance = electrumClient.convertBtcToUsd(balance)
                                        }
                                        
                                        if (balance != null) {
                                            // Update address with balance
                                            val addrIndex = manualAddresses.size + index
                                            val updatedAddresses = addresses.toMutableList()
                                            updatedAddresses[addrIndex] = address.copy(
                                                balance = balance,
                                                usdBalance = usdBalance
                                            )
                                            addresses = updatedAddresses
                                        }
                                    } catch (e: Exception) {
                                        Log.e("BitcoinNotifierScreen", "Error fetching balance for ${address.address}", e)
                                    }
                                }
                            }
                        }
                        showAddExtPubKeyDialog = false
                    },
                    enabled = newExtPubKeyInput.isNotEmpty() && hdWalletManager.isExtendedPublicKey(newExtPubKeyInput)
                ) {
                    Text("Import")
                }
            },
            dismissButton = {
                Button(
                    onClick = { 
                        newExtPubKeyInput = ""
                        newExtPubKeyLabel = ""
                        newExtPubKeyCount = "10"
                        showAddExtPubKeyDialog = false 
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
}

@Composable
fun TransactionList(transactions: List<String>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        items(transactions) { txHash ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                elevation = CardDefaults.cardElevation(4.dp)
            ) {
                Text(
                    text = txHash,
                    modifier = Modifier.padding(8.dp),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewBitcoinNotifierScreen() {
    BitcoinNotifierScreen()
}
