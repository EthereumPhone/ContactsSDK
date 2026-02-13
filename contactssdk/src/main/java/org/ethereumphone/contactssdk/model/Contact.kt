package org.ethereumphone.contactssdk.model

data class Contact(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String?,
    val email: String?,
    val photoUri: String?,
    val ethAddress: String?,
    val ensName: String?
) {
    val hasEthAddress: Boolean
        get() = !ethAddress.isNullOrBlank()

    val hasEns: Boolean
        get() = !ensName.isNullOrBlank()
}
