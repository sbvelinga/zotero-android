package org.zotero.android.database.requests

import io.realm.Realm
import io.realm.kotlin.where
import org.zotero.android.database.DbError
import org.zotero.android.database.DbResponseRequest
import org.zotero.android.database.objects.FieldKeys
import org.zotero.android.database.objects.RItem
import org.zotero.android.sync.LibraryIdentifier

class ReadAttachmentPathDbRequest(
    val libraryId: LibraryIdentifier,
    val key: String,
) : DbResponseRequest<String> {
    override val needsWrite: Boolean
        get() = false

    override fun process(database: Realm): String {
        val item = database
            .where<RItem>()
            .key(key, libraryId)
            .findFirst()
            ?: throw DbError.objectNotFound
        return item.fields.firstOrNull { it.key == FieldKeys.Item.Attachment.path }?.value
            ?: throw DbError.objectNotFound
    }
}
