package com.wholphinplus.sources

/**
 * What Settings → Account needs from Wholphin's own sign-in: who is signed in, and switching user
 * or signing this TV out. Wholphin's main view model fills these in (a marked hook in
 * MainActivity.kt); until then the Account page shows no server and no such buttons.
 */
object AccountActions {
    /** The signed-in server (its name and address) and user. */
    data class Who(
        val server: String,
        val url: String,
        val user: String,
    )

    @Volatile private var whoNow: () -> Who? = { null }

    @Volatile var switchUser: (() -> Unit)? = null
        private set

    @Volatile var signOut: (() -> Unit)? = null
        private set

    fun attach(
        who: () -> Who?,
        switchUser: () -> Unit,
        signOut: () -> Unit,
    ) {
        whoNow = who
        this.switchUser = switchUser
        this.signOut = signOut
    }

    fun who(): Who? = runCatching { whoNow() }.getOrNull()

    /** An account this TV has signed in to: a server and a user on it, with its saved sign-in. */
    data class Account(
        val key: String,
        val server: String,
        val url: String,
        val user: String,
        val current: Boolean,
        /** The user has a Wholphin PIN: asked before switching. */
        val pin: Boolean,
    )

    /** The accounts this TV knows (Wholphin's saved servers and users), the signed-in one marked. */
    @Volatile var accounts: (suspend () -> List<Account>)? = null
        private set

    /** Signs in as [Account.key] with its saved sign-in (no sign-out). Throws "wrong_pin". */
    @Volatile var switchTo: (suspend (key: String, pin: String?) -> Unit)? = null
        private set

    /** Signs in to another server or user, keeping this one saved to switch back to. */
    @Volatile var addAccount: (() -> Unit)? = null
        private set

    fun attachAccounts(
        accounts: suspend () -> List<Account>,
        switchTo: suspend (String, String?) -> Unit,
        addAccount: () -> Unit,
    ) {
        this.accounts = accounts
        this.switchTo = switchTo
        this.addAccount = addAccount
    }
}
