# ContactsSDK

An Android SDK for reading device contacts with Ethereum (ETH) address and ENS name support. Built for the [ethOS](https://ethereumphone.org) ecosystem.

ETH addresses are stored in the `DATA15` field of Android's `ContactsContract`, and ENS names are stored in `SharedPreferences` — the same convention used by the ethOS Contacts app.

[![](https://jitpack.io/v/EthereumPhone/ContactsSDK.svg)](https://jitpack.io/#EthereumPhone/ContactsSDK)

## Setup

### 1. Add JitPack repository

In your root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        // ...
        maven { setUrl("https://jitpack.io") }
    }
}
```

### 2. Add the dependency

In your module `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.EthereumPhone:ContactsSDK:0.1.0")
}
```

### 3. Add permissions

Add to your `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.READ_CONTACTS" />
<!-- Only needed if you want to write ETH addresses to contacts -->
<uses-permission android:name="android.permission.WRITE_CONTACTS" />
```

Make sure to request these permissions at runtime on Android 6.0+.

## Usage

### Initialize

```kotlin
val contactsSDK = ContactsSDK(context)
```

### Get all contacts

```kotlin
val contacts = contactsSDK.getContacts()

for (contact in contacts) {
    println("${contact.displayName}: ${contact.ethAddress ?: "no ETH address"}")
}
```

### Get contacts with ETH addresses only

```kotlin
val ethContacts = contactsSDK.getContactsWithEthAddress()
```

### Get contacts with ENS names only

```kotlin
val ensContacts = contactsSDK.getContactsWithEns()
```

### Get contacts with any Ethereum data (ETH address or ENS)

```kotlin
val web3Contacts = contactsSDK.getContactsWithEthData()
```

### Get a specific contact by ID

```kotlin
val contact = contactsSDK.getContactById("123")
contact?.let {
    println("Name: ${it.displayName}")
    println("ETH: ${it.ethAddress}")
    println("ENS: ${it.ensName}")
    println("Phone: ${it.phoneNumber}")
    println("Email: ${it.email}")
}
```

### Write an ETH address to a contact

```kotlin
contactsSDK.setEthAddress(contactId = 123L, address = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045")
```

### Write an ENS name to a contact

```kotlin
contactsSDK.setEnsName(contactId = 123L, ensName = "vitalik.eth")
```

### Add a new contact with ETH data

```kotlin
val newContactId = contactsSDK.addContact(
    displayName = "Vitalik Buterin",
    phoneNumber = "+1234567890",
    email = "vitalik@ethereum.org",
    ethAddress = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045",
    ensName = "vitalik.eth"
)
```

## Contact Model

```kotlin
data class Contact(
    val contactId: String,
    val displayName: String,
    val phoneNumber: String?,
    val email: String?,
    val photoUri: String?,
    val ethAddress: String?,
    val ensName: String?
)
```

Helper properties:
- `contact.hasEthAddress` — `true` if the contact has a valid ETH address
- `contact.hasEns` — `true` if the contact has an ENS name

## How ETH data is stored

This SDK uses the same storage convention as the ethOS Contacts app:

| Data | Storage Location |
|------|-----------------|
| ETH Address | `ContactsContract.Data.DATA15` under `StructuredName` MIME type |
| ENS Name | `SharedPreferences` with key `"ENS_{contactId}"` in `"contact_prefs"` file |

If `DATA15` contains a valid ETH address (`0x` + 40 hex chars), it's parsed as an ETH address. Otherwise, if it contains a dot (e.g., `vitalik.eth`), it's treated as an ENS name.

## JitPack

This library is published via [JitPack](https://jitpack.io/#EthereumPhone/ContactsSDK).

To trigger a new build after a release, visit: https://jitpack.io/#EthereumPhone/ContactsSDK

## License

MIT License
