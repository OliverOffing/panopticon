package dev.offing.panopticon

import android.util.Log
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Utils
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.Script
import java.util.Base64

class HDWalletManager {
    private val TAG = "HDWalletManager"
    private val params: NetworkParameters = MainNetParams.get()
    
    // Cache for derived addresses
    private val derivedAddressCache = mutableMapOf<String, List<String>>()
    
    // Check if a string is an extended public key (xpub or zpub)
    fun isExtendedPublicKey(key: String): Boolean {
        return key.startsWith("xpub") || key.startsWith("ypub") || key.startsWith("zpub")
    }
    
    // Import an extended public key
    fun importExtendedPublicKey(extPubKey: String): DeterministicKey? {
        return try {
            // Handle zpub conversion if needed
            val xpub = when {
                extPubKey.startsWith("zpub") -> convertZpubToXpub(extPubKey)
                extPubKey.startsWith("ypub") -> convertYpubToXpub(extPubKey)
                else -> extPubKey
            }
            
            DeterministicKey.deserializeB58(xpub, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error importing extended public key: $extPubKey", e)
            null
        }
    }
    
    // Convert zpub (BIP84) to xpub format
    private fun convertZpubToXpub(zpub: String): String {
        try {
            // Decode Base58
            val decoded = Utils.base58Decode(zpub)
            
            // Replace zpub version bytes with xpub version bytes
            // zpub: 04b24746
            // xpub: 0488b21e
            val xpubBytes = decoded.clone()
            xpubBytes[0] = 0x04.toByte()
            xpubBytes[1] = 0x88.toByte()
            xpubBytes[2] = 0xB2.toByte()
            xpubBytes[3] = 0x1E.toByte()
            
            return Utils.base58Encode(xpubBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting zpub to xpub", e)
            return zpub // Return original if conversion fails
        }
    }
    
    // Convert ypub (BIP49) to xpub format
    private fun convertYpubToXpub(ypub: String): String {
        try {
            // Decode Base58
            val decoded = Utils.base58Decode(ypub)
            
            // Replace ypub version bytes with xpub version bytes
            // ypub: 049d7cb2
            // xpub: 0488b21e
            val xpubBytes = decoded.clone()
            xpubBytes[0] = 0x04.toByte()
            xpubBytes[1] = 0x88.toByte()
            xpubBytes[2] = 0xB2.toByte()
            xpubBytes[3] = 0x1E.toByte()
            
            return Utils.base58Encode(xpubBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error converting ypub to xpub", e)
            return ypub // Return original if conversion fails
        }
    }
    
    // Derive addresses from an extended public key
    fun deriveAddresses(extPubKey: String, startIndex: Int, count: Int): List<String> {
        // Check cache first
        val cacheKey = "$extPubKey-$startIndex-$count"
        derivedAddressCache[cacheKey]?.let { return it }
        
        val masterKey = importExtendedPublicKey(extPubKey) ?: return emptyList()
        val addresses = mutableListOf<String>()
        
        try {
            // Different derivation based on key type
            val scriptType = when {
                extPubKey.startsWith("zpub") -> Script.ScriptType.P2WPKH // BIP84 - Native SegWit
                extPubKey.startsWith("ypub") -> Script.ScriptType.P2SH_P2WPKH // BIP49 - Compatible SegWit
                else -> Script.ScriptType.P2PKH // BIP44 - Legacy
            }
            
            // BIP44/BIP49/BIP84 paths all use m/0/i for receiving addresses
            val receiving = HDKeyDerivation.deriveChildKey(masterKey, 0)
            
            for (i in startIndex until startIndex + count) {
                val childKey = HDKeyDerivation.deriveChildKey(receiving, i)
                
                // Create appropriate address type
                val address = Address.fromKey(params, childKey, scriptType).toString()
                addresses.add(address)
                Log.d(TAG, "Derived address $i: $address")
            }
            
            // Cache results
            derivedAddressCache[cacheKey] = addresses
            return addresses
        } catch (e: Exception) {
            Log.e(TAG, "Error deriving addresses from $extPubKey", e)
            return emptyList()
        }
    }
    
    // Generate a human-readable label for an extended public key
    fun generateLabelForExtendedKey(extPubKey: String): String {
        return when {
            extPubKey.startsWith("zpub") -> "Native SegWit HD Wallet (BIP84)"
            extPubKey.startsWith("ypub") -> "Compatible SegWit HD Wallet (BIP49)"
            extPubKey.startsWith("xpub") -> "Legacy HD Wallet (BIP44)"
            else -> "HD Wallet"
        }
    }
}