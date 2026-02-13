package org.ethereumphone.contactssdk

import android.annotation.SuppressLint
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import org.ethereumphone.contactssdk.model.Contact

/**
 * SDK for reading Android contacts with Ethereum (ETH) address and ENS support.
 *
 * ETH addresses are stored in the DATA15 field of the StructuredName MIME type
 * in Android's ContactsContract. This is the same convention used by the
 * ethOS Contacts app.
 *
 * ENS names are stored in SharedPreferences with key "ENS_{contactId}" (in the
 * "contact_prefs" preferences file), matching the ethOS Contacts app convention.
 *
 * @param context Android context (application context recommended)
 */
class ContactsSDK(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    companion object {
        private const val TAG = "ContactsSDK"
        private val ETH_ADDRESS_REGEX = Regex("^0x[0-9a-fA-F]{40}$")
        private const val ENS_PREFS_NAME = "contact_prefs"
    }

    /**
     * Get all contacts from the device, including ETH address and ENS data.
     *
     * Requires `android.permission.READ_CONTACTS` permission.
     *
     * @return List of all contacts with available ETH/ENS data
     */
    @SuppressLint("Range")
    fun getContacts(): List<Contact> {
        val contactMap = HashMap<Long, TempContactData>()

        val selection = "${ContactsContract.Data.MIMETYPE} IN (?, ?, ?, ?)"
        val selectionArgs = arrayOf(
            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        )

        val projection = arrayOf(
            ContactsContract.Data.CONTACT_ID,
            ContactsContract.Data.MIMETYPE,
            ContactsContract.Data.DATA1,
            ContactsContract.Data.DATA15,
            ContactsContract.Data.PHOTO_URI
        )

        val sortOrder = "${ContactsContract.Data.CONTACT_ID} ASC"

        try {
            contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(ContactsContract.Data.CONTACT_ID)
                val mimeIdx = cursor.getColumnIndex(ContactsContract.Data.MIMETYPE)
                val data1Idx = cursor.getColumnIndex(ContactsContract.Data.DATA1)
                val data15Idx = cursor.getColumnIndex(ContactsContract.Data.DATA15)
                val photoUriIdx = cursor.getColumnIndex(ContactsContract.Data.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val contactId = cursor.getLong(idIdx)
                    val mimeType = cursor.getString(mimeIdx)
                    val tempData = contactMap.getOrPut(contactId) { TempContactData() }

                    when (mimeType) {
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE -> {
                            tempData.displayName = cursor.getString(data1Idx)
                            if (data15Idx >= 0 && cursor.getType(data15Idx) == Cursor.FIELD_TYPE_STRING) {
                                val data15 = cursor.getString(data15Idx)
                                if (data15 != null) {
                                    if (ETH_ADDRESS_REGEX.matches(data15)) {
                                        tempData.ethAddress = data15
                                    } else if (data15.contains(".")) {
                                        // Likely an ENS name (e.g., "vitalik.eth")
                                        tempData.ensName = data15
                                    }
                                }
                            }
                        }
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE -> {
                            tempData.phone = cursor.getString(data1Idx)
                        }
                        ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE -> {
                            tempData.email = cursor.getString(data1Idx)
                        }
                        ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE -> {
                            if (photoUriIdx >= 0) {
                                tempData.photoUri = cursor.getString(photoUriIdx)
                            }
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "READ_CONTACTS permission not granted", e)
            return emptyList()
        }

        val prefs = context.getSharedPreferences(ENS_PREFS_NAME, Context.MODE_PRIVATE)

        return contactMap.map { (id, data) ->
            // Check SharedPreferences for ENS (same convention as ethOS Contacts app)
            val ensFromPrefs = prefs.getString("ENS_$id", null)

            Contact(
                contactId = id.toString(),
                displayName = data.displayName.orEmpty(),
                phoneNumber = data.phone,
                email = data.email,
                photoUri = data.photoUri,
                ethAddress = data.ethAddress,
                ensName = data.ensName ?: ensFromPrefs
            )
        }.sortedBy { it.displayName.lowercase() }
    }

    /**
     * Get only contacts that have an ETH address.
     *
     * @return List of contacts with a valid ETH address in DATA15
     */
    fun getContactsWithEthAddress(): List<Contact> {
        return getContacts().filter { it.hasEthAddress }
    }

    /**
     * Get only contacts that have an ENS name.
     *
     * @return List of contacts with an ENS name
     */
    fun getContactsWithEns(): List<Contact> {
        return getContacts().filter { it.hasEns }
    }

    /**
     * Get only contacts that have either an ETH address or an ENS name.
     *
     * @return List of contacts with any Ethereum-related data
     */
    fun getContactsWithEthData(): List<Contact> {
        return getContacts().filter { it.hasEthAddress || it.hasEns }
    }

    /**
     * Get a single contact by their Android contact ID.
     *
     * @param contactId The Android contact ID
     * @return The contact, or null if not found
     */
    @SuppressLint("Range")
    fun getContactById(contactId: String): Contact? {
        val nameCursor = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.PHOTO_URI),
            "${ContactsContract.Contacts._ID} = ?",
            arrayOf(contactId),
            null
        )

        var displayName = ""
        var photoUri: String? = null
        nameCursor?.use {
            if (it.moveToFirst()) {
                displayName = it.getString(it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)) ?: ""
                photoUri = it.getString(it.getColumnIndex(ContactsContract.Contacts.PHOTO_URI))
            }
        }

        if (displayName.isEmpty()) return null

        val phone = queryDataField(
            contactId,
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )

        val email = queryDataField(
            contactId,
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            ContactsContract.CommonDataKinds.Email.DATA
        )

        val data15 = getData15(contactId)
        var ethAddress: String? = null
        var ensName: String? = null
        if (data15 != null) {
            if (ETH_ADDRESS_REGEX.matches(data15)) {
                ethAddress = data15
            } else if (data15.contains(".")) {
                ensName = data15
            }
        }

        val prefs = context.getSharedPreferences(ENS_PREFS_NAME, Context.MODE_PRIVATE)
        if (ensName == null) {
            ensName = prefs.getString("ENS_$contactId", null)
        }

        return Contact(
            contactId = contactId,
            displayName = displayName,
            phoneNumber = phone,
            email = email,
            photoUri = photoUri,
            ethAddress = ethAddress,
            ensName = ensName
        )
    }

    /**
     * Write an ETH address to a contact's DATA15 field.
     *
     * Requires `android.permission.WRITE_CONTACTS` permission.
     *
     * @param contactId The Android contact ID
     * @param address The ETH address (must start with 0x and be 42 chars)
     * @return true if the write was successful
     */
    fun setEthAddress(contactId: Long, address: String): Boolean {
        require(ETH_ADDRESS_REGEX.matches(address)) {
            "Invalid ETH address: must match 0x followed by 40 hex characters"
        }
        return writeData15(contactId, address)
    }

    /**
     * Write an ENS name to a contact's DATA15 field.
     *
     * Requires `android.permission.WRITE_CONTACTS` permission.
     *
     * @param contactId The Android contact ID
     * @param ensName The ENS name (e.g., "vitalik.eth")
     * @return true if the write was successful
     */
    fun setEnsName(contactId: Long, ensName: String): Boolean {
        return writeData15(contactId, ensName)
    }

    /**
     * Save an ENS name to SharedPreferences for a contact.
     * This uses the same convention as the ethOS Contacts app.
     *
     * @param contactId The Android contact ID
     * @param ensName The ENS name
     */
    fun saveEnsToPrefs(contactId: Long, ensName: String) {
        context.getSharedPreferences(ENS_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("ENS_$contactId", ensName)
            .apply()
    }

    /**
     * Add a new contact with ETH address and/or ENS data.
     *
     * Requires `android.permission.WRITE_CONTACTS` permission.
     *
     * @param displayName The contact's display name
     * @param phoneNumber Optional phone number
     * @param email Optional email address
     * @param ethAddress Optional ETH address
     * @param ensName Optional ENS name
     * @return The new contact's ID, or null if creation failed
     */
    fun addContact(
        displayName: String,
        phoneNumber: String? = null,
        email: String? = null,
        ethAddress: String? = null,
        ensName: String? = null
    ): String? {
        val ops = ArrayList<ContentProviderOperation>()

        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )

        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                .build()
        )

        if (!phoneNumber.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phoneNumber)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }

        if (!email.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                    .build()
            )
        }

        return try {
            val results = contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawContactUri = results[0].uri ?: return null
            val rawContactId = ContentUris.parseId(rawContactUri)

            // Get the aggregate contact ID
            val cursor = contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawContactId.toString()),
                null
            )

            val contactId = cursor?.use {
                if (it.moveToFirst()) {
                    it.getLong(it.getColumnIndex(ContactsContract.RawContacts.CONTACT_ID))
                } else null
            } ?: return null

            // Write ETH address or ENS to DATA15
            val data15Value = ethAddress ?: ensName
            if (data15Value != null) {
                writeData15(contactId, data15Value)
            }

            // Save ENS to SharedPreferences
            if (!ensName.isNullOrBlank()) {
                saveEnsToPrefs(contactId, ensName)
            }

            contactId.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add contact", e)
            null
        }
    }

    // ---- Internal helpers ----

    @SuppressLint("Range")
    private fun getData15(contactId: String): String? {
        val projection = arrayOf(ContactsContract.Data.DATA15)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)

        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndex(ContactsContract.Data.DATA15))
            }
        }
        return null
    }

    @SuppressLint("Range")
    private fun writeData15(contactId: Long, data: String): Boolean {
        val values = ContentValues().apply {
            put(ContactsContract.Data.DATA15, data)
        }

        val projection = arrayOf(ContactsContract.Data._ID)
        val selection = "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?"
        val selectionArgs = arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)

        var dataId: Long? = null
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                dataId = cursor.getLong(cursor.getColumnIndex(ContactsContract.Data._ID))
            }
        }

        return dataId?.let {
            val rowsUpdated = contentResolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.Data._ID} = ?",
                arrayOf(it.toString())
            )
            rowsUpdated > 0
        } ?: false
    }

    @SuppressLint("Range")
    private fun queryDataField(
        contactId: String,
        uri: android.net.Uri,
        idColumn: String,
        dataColumn: String
    ): String? {
        contentResolver.query(
            uri,
            arrayOf(dataColumn),
            "$idColumn = ?",
            arrayOf(contactId),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getString(cursor.getColumnIndex(dataColumn))
            }
        }
        return null
    }

    private data class TempContactData(
        var displayName: String? = null,
        var email: String? = null,
        var phone: String? = null,
        var ethAddress: String? = null,
        var ensName: String? = null,
        var photoUri: String? = null
    )
}
