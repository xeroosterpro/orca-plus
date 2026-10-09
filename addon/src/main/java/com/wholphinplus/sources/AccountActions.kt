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
}
