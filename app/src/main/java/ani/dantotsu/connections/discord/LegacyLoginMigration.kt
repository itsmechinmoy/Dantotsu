package ani.dantotsu.connections.discord

/**
 * Runs once when token logins are dropped: whoever had one gets app presence switched on,
 * everyone else keeps their setting. Later changes by the user are left alone.
 */
object LegacyLoginMigration {

    interface Store {
        val hadLegacyLogin: Boolean
        var migrated: Boolean
        fun enablePresence()
    }

    fun run(store: Store) {
        if (store.migrated) return
        if (store.hadLegacyLogin) store.enablePresence()
        store.migrated = true
    }
}
