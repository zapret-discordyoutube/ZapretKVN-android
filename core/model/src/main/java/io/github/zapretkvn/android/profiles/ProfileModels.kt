package io.github.zapretkvn.android.profiles

enum class ProfileSource(val storageValue: String) {
    RawJson("raw_json"),
    File("file"),
    Clipboard("clipboard"),
    Link("link"),
    Qr("qr"),
    Url("url"),
    Subscription("subscription"),
    ;

    companion object {
        fun fromStorage(value: String): ProfileSource =
            entries.firstOrNull { it.storageValue == value } ?: RawJson
    }
}

data class ProfileMetadata(
    val id: String,
    val name: String,
    val source: ProfileSource,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class StoredProfile(
    val metadata: ProfileMetadata,
    val json: String,
    val hasBackup: Boolean,
)

class ProfileStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
