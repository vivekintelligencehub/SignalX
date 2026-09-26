package com.signalX

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

import com.vanniktech.emoji.EmojiEditText
import com.vanniktech.emoji.EmojiPopup


class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "EXTRA_CHAT_ID"
        const val EXTRA_JUMP_MESSAGE_ID = "EXTRA_JUMP_MESSAGE_ID"
        const val EXTRA_JUMP_SESSION_IDS = "EXTRA_JUMP_SESSION_IDS"
        const val EXTRA_HIGHLIGHT_QUERY = "EXTRA_HIGHLIGHT_QUERY"
    }


    private lateinit var chatId: String

    private var isSelfChat = false
    private var isFromLockedZone = false


    // ======================================================
    // MESSAGE LIST
    // ======================================================

    private lateinit var rvMessages: RecyclerView
    private lateinit var messageAdapter: ChatMessageAdapter

    private val revealedSessionIds =
        mutableSetOf<String>()


    // ======================================================
    // INPUT
    // ======================================================

    private lateinit var etMessageInput: EmojiEditText
    private lateinit var btnSendOrMic: TextView


    // ======================================================
    // SESSION BANNERS
    // ======================================================

    private lateinit var sessionActiveBanner: TextView
    private lateinit var closeAllSessionsBanner: TextView


    // ======================================================
    // SEARCH / HIGHLIGHT
    // ======================================================

    private var initialJumpMessageId: String? = null
    private var highlightQuery: String? = null

    private var highlightedMessageIds: List<String> =
        emptyList()

    private var currentHighlightIndex: Int = -1

    private var hasScrolledToInitialHighlight = false


    private lateinit var highlightBanner: TextView
    private lateinit var highlightNavContainer: View
    private lateinit var btnHighlightUp: TextView
    private lateinit var btnHighlightDown: TextView


    // ======================================================
    // PANELS
    // ======================================================

    private enum class ActivePanel {
        NONE,
        ATTACH,
        SESSION
    }

    private var currentPanel =
        ActivePanel.NONE


    private lateinit var panelContainer: View
    private lateinit var sessionPanel: View

    private lateinit var btnEmoji: TextView
    private lateinit var btnAttach: TextView
    private lateinit var btnTransfer: ImageView

    private lateinit var attachmentPanel: AttachmentPanelView

    private lateinit var emojiPopup: EmojiPopup


    // Photos permission â€” grant hote hi gallery refresh hoti hai
    private val requestMediaPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted && ::attachmentPanel.isInitialized) {

                attachmentPanel.refreshImages()
            }
        }


    // ======================================================
    // VAULT PASSWORD RESULT
    // ======================================================

    private val verifyPasswordLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {
                performVaultLock()
            }
        }


    // ======================================================
    // ON CREATE
    // ======================================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_chat)


        // --------------------------------------------------
        // Chat ID
        // --------------------------------------------------

        chatId =
            intent.getStringExtra(
                EXTRA_CHAT_ID
            ) ?: "self"

        isSelfChat =
            chatId == "self"

        isFromLockedZone =
            ChatRepository.getChatById(chatId)?.location ==
                    ChatLocation.LOCKED ||
            (
                isSelfChat &&
                ChatRepository.selfLocation ==
                    ChatLocation.LOCKED
            )


        // --------------------------------------------------
        // Existing setup
        // --------------------------------------------------

        setupHeader()
        setupMessageList()
        setupInputBar()
        setupPanels()
        setupHighlightControls()
        applyIntentJumpExtras()


        // --------------------------------------------------
        // Back button
        // --------------------------------------------------

        findViewById<TextView>(
            R.id.btnBackChat
        ).setOnClickListener {

            finish()
        }


        // --------------------------------------------------
        // Keyboard height panel ko dena (caption bar ke liye)
        // --------------------------------------------------

        ViewCompat.setOnApplyWindowInsetsListener(
            window.decorView
        ) { _, insets ->

            attachmentPanel.keyboardHeightPx =
                if (
                    insets.isVisible(
                        WindowInsetsCompat.Type.ime()
                    )
                ) {
                    insets.getInsets(
                        WindowInsetsCompat.Type.ime()
                    ).bottom
                } else {
                    0
                }

            insets
        }
    }


    // ======================================================
    // RESUME
    // ======================================================

    override fun onResume() {

        super.onResume()


        if (
            isFromLockedZone &&
            !LockedZoneGuard.checkAccessOnResume(this)
        ) {
            return
        }


        messageAdapter.isBulkReveal = true

        refreshMessages()
    }


    // ======================================================
    // PAUSE
    // ======================================================

    override fun onPause() {

        super.onPause()


        val activeId =
            MessageRepository.getActiveSessionId(
                chatId
            )

        if (activeId != null) {

            MessageRepository.lockActiveSession(
                chatId
            )
        }


        revealedSessionIds.clear()

        closePanels()
    }


    // ======================================================
    // BACK
    // ======================================================

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {

        if (
            currentPanel !=
            ActivePanel.NONE
        ) {

            // Attachment panel apna back khud sambhale
            // (editor > album > discard popup > selection > stepwise > hide)
            if (
                ::attachmentPanel.isInitialized &&
                attachmentPanel.handleBackPress()
            ) {
                return
            }

            closePanels()

            return
        }

        super.onBackPressed()
    }


    // ======================================================
    // BIOMETRIC
    // ======================================================

    private fun tryBiometricForSession(
        session: ChatSession,
        dialog: AlertDialog,
        onSuccess: () -> Unit
    ) {

        val biometricManager =
            BiometricManager.from(this)


        val canAuthenticate =
            biometricManager.canAuthenticate(

                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK
            )


        if (
            canAuthenticate !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return
        }


        val executor =
            ContextCompat.getMainExecutor(this)


        val biometricPrompt =
            BiometricPrompt(
                this,
                executor,

                object :
                    BiometricPrompt.AuthenticationCallback() {

                    override fun
                    onAuthenticationSucceeded(
                        result:
                        BiometricPrompt.AuthenticationResult
                    ) {

                        super.onAuthenticationSucceeded(
                            result
                        )

                        onSuccess()
                    }


                    override fun
                    onAuthenticationError(
                        errorCode: Int,
                        errString: CharSequence
                    ) {

                        super.onAuthenticationError(
                            errorCode,
                            errString
                        )
                    }
                }
            )


        val promptInfo =
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Session")
                .setSubtitle(
                    "Use fingerprint or enter password below"
                )
                .setNegativeButtonText("Cancel")
                .build()


        biometricPrompt.authenticate(
            promptInfo
        )
    }


    // ======================================================
    // HEADER
    // ======================================================

    private fun setupHeader() {

        val displayName: String


        if (isSelfChat) {

            val prefs =
                getSharedPreferences(
                    "signalx_prefs",
                    Context.MODE_PRIVATE
                )

            val syncedName =
                prefs.getString(
                    "synced_name",
                    null
                )


            displayName =
                if (syncedName.isNullOrBlank()) {
                    "You"
                } else {
                    syncedName
                }


            findViewById<TextView>(
                R.id.tvChatHeaderStatus
            ).text =
                "(Manage Yourself)"


            findViewById<View>(
                R.id.btnVideoCall
            ).visibility =
                View.GONE


            findViewById<View>(
                R.id.btnVoiceCall
            ).visibility =
                View.GONE

        } else {

            val item =
                ChatRepository.getChatById(
                    chatId
                )


            displayName =
                item?.name ?: "Chat"


            findViewById<TextView>(
                R.id.tvChatHeaderStatus
            ).text =
                "Online"


            findViewById<TextView>(
                R.id.btnVideoCall
            ).setOnClickListener {

                Toast.makeText(
                    this,
                    "Coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }


            findViewById<TextView>(
                R.id.btnVoiceCall
            ).setOnClickListener {

                Toast.makeText(
                    this,
                    "Coming soon",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


        findViewById<TextView>(
            R.id.tvChatHeaderName
        ).text =
            displayName


        findViewById<TextView>(
            R.id.tvChatAvatarLetter
        ).text =
            displayName
                .take(1)
                .uppercase()


        findViewById<TextView>(
            R.id.btnChatOptionsMenu
        ).setOnClickListener {

            val intent =
                Intent(
                    this,
                    ComingSoonActivity::class.java
                )


            intent.putExtra(
                ComingSoonActivity.EXTRA_TITLE,
                "Chat"
            )


            intent.putExtra(
                ComingSoonActivity.EXTRA_SECURE,
                isFromLockedZone
            )


            startActivity(intent)
        }


        sessionActiveBanner =
            findViewById(
                R.id.sessionActiveBanner
            )


        closeAllSessionsBanner =
            findViewById(
                R.id.closeAllSessionsBanner
            )


        sessionActiveBanner.setOnClickListener {

            MessageRepository.lockActiveSession(
                chatId
            )


            Toast.makeText(
                this,
                "Session locked",
                Toast.LENGTH_SHORT
            ).show()


            refreshMessages()
        }


        closeAllSessionsBanner.setOnClickListener {

            messageAdapter.isBulkReveal = true

            revealedSessionIds.clear()


            Toast.makeText(
                this,
                "All sessions locked",
                Toast.LENGTH_SHORT
            ).show()


            refreshMessages()
        }
    }


    // ======================================================
    // MESSAGE LIST
    // ======================================================

    private fun setupMessageList() {

        rvMessages =
            findViewById(
                R.id.rvMessages
            )


        rvMessages.layoutManager =
            LinearLayoutManager(this)


        messageAdapter =
            ChatMessageAdapter(

                rows = mutableListOf(),

                onBannerClick = { session ->
                    showUnlockSessionDialog(
                        session
                    )
                },

                onCloseRevealedSessionClick = {
                    session ->

                    revealedSessionIds.remove(
                        session.id
                    )

                    refreshMessages()
                }
            )


        rvMessages.adapter =
            messageAdapter
    }


    private fun refreshMessages() {

        val messages =
            MessageRepository
                .getMessages(chatId)
                .sortedBy {
                    it.timestamp
                }


        val rows =
            mutableListOf<ChatListRow>()


        var lastBannerSessionId:
            String? = null


        var pendingRevealedGroup:
            MutableList<Message>? = null


        var pendingRevealedSession:
            ChatSession? = null


        fun flushRevealedGroup() {

            val group =
                pendingRevealedGroup


            val sess =
                pendingRevealedSession


            if (
                group != null &&
                sess != null &&
                group.isNotEmpty()
            ) {

                rows.add(
                    ChatListRow.RevealedSessionRow(
                        sess,
                        group.toList()
                    )
                )
            }


            pendingRevealedGroup = null
            pendingRevealedSession = null
        }


        for (message in messages) {

            val sessionId =
                message.sessionId


            if (sessionId == null) {

                flushRevealedGroup()

                rows.add(
                    ChatListRow.MessageRow(
                        message
                    )
                )

                lastBannerSessionId = null

                continue
            }


            val session =
                MessageRepository.getSession(
                    chatId,
                    sessionId
                )


            if (session == null) {

                flushRevealedGroup()

                rows.add(
                    ChatListRow.MessageRow(
                        message
                    )
                )

                continue
            }


            val isRevealed =
                revealedSessionIds.contains(
                    sessionId
                )


            when {

                session.isLocked &&
                    !isRevealed -> {

                    flushRevealedGroup()


                    if (
                        lastBannerSessionId !=
                        sessionId
                    ) {

                        rows.add(
                            ChatListRow.SessionBannerRow(
                                session
                            )
                        )


                        lastBannerSessionId =
                            sessionId
                    }
                }


                session.isLocked &&
                    isRevealed -> {

                    if (
                        pendingRevealedSession?.id !=
                        sessionId
                    ) {

                        flushRevealedGroup()


                        pendingRevealedSession =
                            session


                        pendingRevealedGroup =
                            mutableListOf()
                    }


                    pendingRevealedGroup?.add(
                        message
                    )


                    lastBannerSessionId = null
                }


                else -> {

                    flushRevealedGroup()


                    rows.add(
                        ChatListRow.MessageRow(
                            message
                        )
                    )


                    lastBannerSessionId = null
                }
            }
        }


        flushRevealedGroup()


        messageAdapter.updateRows(
            rows
        )


        updateBanners()


        if (highlightQuery != null) {

            computeAndApplyHighlight()

        } else {

            performPlainInitialJump()
        }
    }


    private fun updateBanners() {

        val activeId =
            MessageRepository.getActiveSessionId(
                chatId
            )


        sessionActiveBanner.visibility =
            if (activeId != null) {
                View.VISIBLE
            } else {
                View.GONE
            }


        if (
            revealedSessionIds.isNotEmpty()
        ) {

            closeAllSessionsBanner.visibility =
                View.VISIBLE


            closeAllSessionsBanner.text =
                "ðŸ”’  Close All Sessions (${revealedSessionIds.size})"

        } else {

            closeAllSessionsBanner.visibility =
                View.GONE
        }
    }


    private fun scrollToBottom() {

        rvMessages.post {

            if (
                messageAdapter.itemCount > 0
            ) {

                rvMessages.smoothScrollToPosition(
                    messageAdapter.itemCount - 1
                )
            }
        }
    }


    // ======================================================
    // SEARCH / HIGHLIGHT
    // ======================================================

    private fun setupHighlightControls() {

        highlightBanner =
            findViewById(
                R.id.highlightBanner
            )


        highlightNavContainer =
            findViewById(
                R.id.highlightNavContainer
            )


        btnHighlightUp =
            findViewById(
                R.id.btnHighlightUp
            )


        btnHighlightDown =
            findViewById(
                R.id.btnHighlightDown
            )


        highlightBanner.setOnClickListener {

            clearHighlight()
        }


        btnHighlightUp.setOnClickListener {

            navigateHighlight(-1)
        }


        btnHighlightDown.setOnClickListener {

            navigateHighlight(1)
        }
    }


    private fun applyIntentJumpExtras() {

        val sessionIdsToReveal =
            intent.getStringArrayListExtra(
                EXTRA_JUMP_SESSION_IDS
            )


        sessionIdsToReveal?.forEach {

            revealedSessionIds.add(it)
        }


        val query =
            intent.getStringExtra(
                EXTRA_HIGHLIGHT_QUERY
            )


        if (!query.isNullOrBlank()) {

            highlightQuery = query
        }


        initialJumpMessageId =
            intent.getStringExtra(
                EXTRA_JUMP_MESSAGE_ID
            )


        messageAdapter.isBulkReveal =
            highlightQuery != null
    }


    private fun performPlainInitialJump() {

        val targetId =
            initialJumpMessageId
                ?: return


        if (
            hasScrolledToInitialHighlight
        ) {
            return
        }


        hasScrolledToInitialHighlight =
            true


        scrollToMessage(
            targetId
        )


        messageAdapter.currentHighlightMessageId =
            targetId


        messageAdapter.notifyDataSetChanged()


        Handler(
            Looper.getMainLooper()
        ).postDelayed({

            if (
                highlightQuery == null
            ) {

                messageAdapter.currentHighlightMessageId =
                    null

                messageAdapter.notifyDataSetChanged()
            }

        }, 1000L)
    }


    private fun computeAndApplyHighlight() {

        val query =
            highlightQuery


        if (
            query == null ||
            query.isBlank()
        ) {

            highlightedMessageIds =
                emptyList()


            currentHighlightIndex =
                -1


            messageAdapter.highlightedMessageIds =
                emptySet()


            messageAdapter.currentHighlightMessageId =
                null


            messageAdapter.notifyDataSetChanged()


            highlightBanner.visibility =
                View.GONE


            highlightNavContainer.visibility =
                View.GONE


            return
        }


        val previousTargetId =
            highlightedMessageIds.getOrNull(
                currentHighlightIndex
            )
                ?: initialJumpMessageId


        val matches =
            MessageRepository
                .getMessages(chatId)

                .filter {
                    it.text.contains(
                        query,
                        ignoreCase = true
                    )
                }

                .filter { m ->

                    m.sessionId == null ||
                        run {

                            val s =
                                MessageRepository.getSession(
                                    chatId,
                                    m.sessionId
                                )


                            s == null ||
                                !s.isLocked ||
                                revealedSessionIds.contains(
                                    s.id
                                )
                        }
                }

                .sortedBy {
                    it.timestamp
                }

                .map {
                    it.id
                }


        highlightedMessageIds =
            matches


        if (matches.isEmpty()) {

            currentHighlightIndex =
                -1


            messageAdapter.highlightedMessageIds =
                emptySet()


            messageAdapter.currentHighlightMessageId =
                null


            messageAdapter.notifyDataSetChanged()


            highlightBanner.visibility =
                View.VISIBLE


            highlightNavContainer.visibility =
                View.GONE


            return
        }


        currentHighlightIndex =
            if (
                previousTargetId != null &&
                matches.contains(
                    previousTargetId
                )
            ) {

                matches.indexOf(
                    previousTargetId
                )

            } else {

                matches.lastIndex
            }


        val targetId =
            matches[
                currentHighlightIndex
            ]


        messageAdapter.highlightedMessageIds =
            matches.toSet()


        messageAdapter.currentHighlightMessageId =
            targetId


        messageAdapter.notifyDataSetChanged()


        highlightBanner.visibility =
            View.VISIBLE


        highlightNavContainer.visibility =
            if (matches.size > 1) {
                View.VISIBLE
            } else {
                View.GONE
            }


        if (
            !hasScrolledToInitialHighlight
        ) {

            hasScrolledToInitialHighlight =
                true


            scrollToMessage(
                targetId
            )
        }
    }


    private fun scrollToMessage(
        messageId: String
    ) {

        rvMessages.post {

            val pos =
                messageAdapter
                    .getPositionForMessageId(
                        messageId
                    )


            if (pos != -1) {

                rvMessages.smoothScrollToPosition(
                    pos
                )
            }
        }
    }


    private fun navigateHighlight(
        direction: Int
    ) {

        if (
            highlightedMessageIds.isEmpty()
        ) {
            return
        }


        currentHighlightIndex =
            (
                currentHighlightIndex +
                    direction
                ).coerceIn(
                    0,
                    highlightedMessageIds.lastIndex
                )


        val targetId =
            highlightedMessageIds[
                currentHighlightIndex
            ]


        messageAdapter.currentHighlightMessageId =
            targetId


        messageAdapter.notifyDataSetChanged()


        scrollToMessage(
            targetId
        )
    }


    private fun clearHighlight() {

        highlightQuery = null

        highlightedMessageIds =
            emptyList()

        currentHighlightIndex =
            -1

        hasScrolledToInitialHighlight =
            false


        messageAdapter.highlightedMessageIds =
            emptySet()


        messageAdapter.currentHighlightMessageId =
            null


        messageAdapter.notifyDataSetChanged()


        highlightBanner.visibility =
            View.GONE


        highlightNavContainer.visibility =
            View.GONE
    }


    // ======================================================
    // INPUT BAR
    // ======================================================

    private fun setupInputBar() {

        etMessageInput =
            findViewById(
                R.id.etMessageInput
            )


        btnSendOrMic =
            findViewById(
                R.id.btnSendOrMic
            )


        etMessageInput.addTextChangedListener(

            object : TextWatcher {

                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }


                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                }


                override fun afterTextChanged(
                    s: Editable?
                ) {

                    btnSendOrMic.text =
                        if (
                            s.isNullOrBlank()
                        ) {
                            "ðŸŽ¤"
                        } else {
                            "âž¤"
                        }
                }
            }
        )


        btnSendOrMic.setOnClickListener {

            val text =
                etMessageInput
                    .text
                    ?.toString()
                    ?.trim()
                    ?: ""


            if (text.isBlank()) {

                Toast.makeText(
                    this,
                    "Coming soon",
                    Toast.LENGTH_SHORT
                ).show()

                return@setOnClickListener
            }


            MessageRepository.addMessage(
                chatId,
                text
            )


            etMessageInput.text?.clear()


            refreshMessages()

            scrollToBottom()
        }


        findViewById<TextView>(
            R.id.btnCamera
        ).setOnClickListener {

            Toast.makeText(
                this,
                "Coming soon",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    // ======================================================
    // PANELS
    // ======================================================

    private fun setupPanels() {

        panelContainer =
            findViewById(
                R.id.panelContainer
            )


        sessionPanel =
            findViewById(
                R.id.sessionPanel
            )


        btnEmoji =
            findViewById(
                R.id.btnEmoji
            )


        btnAttach =
            findViewById(
                R.id.btnAttach
            )


        btnTransfer =
            findViewById(
                R.id.btnTransfer
            )


        attachmentPanel =
            findViewById(
                R.id.attachmentPanel
            )


        val rootView =
            findViewById<View>(
                android.R.id.content
            )


        emojiPopup =
            EmojiPopup(
                rootView,
                etMessageInput
            )


        // --------------------------------------------------
        // Emoji
        // --------------------------------------------------

        btnEmoji.setOnClickListener {

            if (
                currentPanel !=
                ActivePanel.NONE
            ) {

                // Koi panel khula hai (paperclip/session) â†’ uska content gayab
                // aur EMOJI content kholo (keyboard NAHI â€” icon switching jaisa)
                closePanels()

                if (!emojiPopup.isShowing) {

                    emojiPopup.toggle()
                }

                return@setOnClickListener
            }


            emojiPopup.toggle()
        }


        // --------------------------------------------------
        // Paperclip
        // --------------------------------------------------

        btnAttach.setOnClickListener {

            // TOGGLE: paperclip panel khula hai â†’ band + keyboard wapas
            // (WhatsApp jaisa); warna panel kholo.
            if (
                currentPanel ==
                ActivePanel.ATTACH
            ) {

                closePanels()

                showKeyboard()

            } else {

                openAttachPanel()
            }
        }


        // --------------------------------------------------
        // Transfer / Session
        // --------------------------------------------------

        btnTransfer.setOnClickListener {

            togglePanel(
                ActivePanel.SESSION
            )
        }


        // --------------------------------------------------
        // Input
        // --------------------------------------------------

        etMessageInput.setOnClickListener {

            if (
                currentPanel !=
                ActivePanel.NONE
            ) {

                closePanels()

                showKeyboard()
            }
        }


        wireSessionPanelCells()


        // --------------------------------------------------
        // Image send from attachment panel
        // --------------------------------------------------

        attachmentPanel.onImageSend =
            { uri, caption ->

                /*
                 * Existing app's current image-message
                 * format is preserved.
                 *
                 * Caption is currently not added to the
                 * database because the existing Message/
                 * MessageRepository format supplied earlier
                 * has no caption field.
                 */

                MessageRepository.addMessage(
                    chatId,
                    "IMG::$uri"
                )


                refreshMessages()

                scrollToBottom()
            }


        // --------------------------------------------------
        // Attachment panel â€” permission / push / option cells
        // --------------------------------------------------

        attachmentPanel.onRequestMediaPermission =
            {
                requestMediaPermission()
            }


        attachmentPanel.setContentPushView(
            findViewById(R.id.chatMainContent)
        )


        attachmentPanel.onOptionClicked =
            { optionId ->

                Toast.makeText(
                    this,
                    "$optionId â€” wiring phase 2",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }


    // ======================================================
    // PANEL TOGGLE
    // ======================================================

    private fun togglePanel(
        panel: ActivePanel
    ) {

        if (
            currentPanel == panel
        ) {

            closePanels()

            showKeyboard()

        } else {

            hideKeyboard()


            // Attachment panel must be hidden
            // when Session panel opens.

            attachmentPanel.hidePanel()


            panelContainer.visibility =
                View.VISIBLE


            sessionPanel.visibility =
                if (
                    panel ==
                    ActivePanel.SESSION
                ) {
                    View.VISIBLE
                } else {
                    View.GONE
                }


            currentPanel =
                panel


            updateIconHighlight()
        }
    }


    // ======================================================
    // OPEN ATTACHMENT
    // ======================================================

    private fun openAttachPanel() {

        if (
            isFinishing ||
            isDestroyed
        ) {
            return
        }


        hideKeyboard()


        // Session panel is unrelated and must close.

        panelContainer.visibility =
            View.GONE


        sessionPanel.visibility =
            View.GONE


        currentPanel =
            ActivePanel.ATTACH


        attachmentPanel.visibility =
            View.VISIBLE


        attachmentPanel.showPanel()


        updateIconHighlight()
    }


    // ======================================================
    // MEDIA PERMISSION
    // ======================================================

    private fun requestMediaPermission() {

        val permission =
            if (
                android.os.Build.VERSION.SDK_INT >= 33
            ) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }


        requestMediaPermissionLauncher.launch(
            permission
        )
    }


    // ======================================================
    // CLOSE PANELS
    // ======================================================

    private fun closePanels() {

        attachmentPanel.hidePanel()


        panelContainer.visibility =
            View.GONE


        sessionPanel.visibility =
            View.GONE


        currentPanel =
            ActivePanel.NONE


        btnEmoji.text =
            "ðŸ˜Š"


        updateIconHighlight()
    }


    // ======================================================
    // ICON HIGHLIGHT
    // ======================================================

    private fun updateIconHighlight() {

        btnAttach.background =
            if (
                currentPanel ==
                ActivePanel.ATTACH
            ) {

                ContextCompat.getDrawable(
                    this,
                    R.drawable.bg_icon_active
                )

            } else {

                null
            }


        btnTransfer.background =
            if (
                currentPanel ==
                ActivePanel.SESSION
            ) {

                ContextCompat.getDrawable(
                    this,
                    R.drawable.bg_icon_active
                )

            } else {

                null
            }
    }


    // ======================================================
    // KEYBOARD
    // ======================================================

    private fun hideKeyboard() {

        val imm =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager


        imm.hideSoftInputFromWindow(
            etMessageInput.windowToken,
            0
        )
    }


    private fun showKeyboard() {

        etMessageInput.requestFocus()


        val imm =
            getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager


        imm.showSoftInput(
            etMessageInput,
            InputMethodManager.SHOW_IMPLICIT
        )
    }


    // ======================================================
    // SESSION PANEL CELLS
    // ======================================================

    private fun wireSessionPanelCells() {

        findViewById<View>(
            R.id.cellSessionLock
        ).setOnClickListener {

            closePanels()

            onSessionLockTapped()
        }


        findViewById<View>(
            R.id.cellVaultLock
        ).setOnClickListener {

            closePanels()

            handleVaultLock()
        }


        findViewById<View>(
            R.id.cellDisappearMode
        ).setOnClickListener {

            Toast.makeText(
                this,
                "Coming soon",
                Toast.LENGTH_SHORT
            ).show()


            closePanels()
        }


        findViewById<View>(
            R.id.cellDisappearTimer
        ).setOnClickListener {

            Toast.makeText(
                this,
                "Coming soon",
                Toast.LENGTH_SHORT
            ).show()


            closePanels()
        }


        findViewById<View>(
            R.id.cellSettingsChat
        ).setOnClickListener {

            Toast.makeText(
                this,
                "Coming soon",
                Toast.LENGTH_SHORT
            ).show()


            closePanels()
        }
    }


    // ======================================================
    // SESSION LOCK
    // ======================================================

    private fun onSessionLockTapped() {

        if (
            MessageRepository.getActiveSessionId(
                chatId
            ) != null
        ) {

            MessageRepository.lockActiveSession(
                chatId
            )


            Toast.makeText(
                this,
                "Session locked",
                Toast.LENGTH_SHORT
            ).show()


            refreshMessages()

        } else {

            showStartSessionDialog()
        }
    }


    private fun showStartSessionDialog() {

        val dialogView =
            LayoutInflater.from(this)
                .inflate(
                    R.layout.dialog_start_session,
                    null
                )


        val rgPasswordType =
            dialogView.findViewById<RadioGroup>(
                R.id.rgPasswordType
            )


        val rbUnique =
            dialogView.findViewById<RadioButton>(
                R.id.rbUniquePassword
            )


        val uniqueFields =
            dialogView.findViewById<View>(
                R.id.uniquePasswordFields
            )


        val commonFields =
            dialogView.findViewById<View>(
                R.id.commonPasswordFields
            )


        val etSessionPassword =
            dialogView.findViewById<EditText>(
                R.id.etSessionPassword
            )


        val etSessionPasswordConfirm =
            dialogView.findViewById<EditText>(
                R.id.etSessionPasswordConfirm
            )


        val etCommonPassword =
            dialogView.findViewById<EditText>(
                R.id.etCommonPassword
            )


        val etCommonPasswordConfirm =
            dialogView.findViewById<EditText>(
                R.id.etCommonPasswordConfirm
            )


        val tvCommonPasswordNote =
            dialogView.findViewById<TextView>(
                R.id.tvCommonPasswordNote
            )


        val tvError =
            dialogView.findViewById<TextView>(
                R.id.tvSessionPasswordError
            )


        val cbBiometric =
            dialogView.findViewById<CheckBox>(
                R.id.cbBiometric
            )


        val btnStart =
            dialogView.findViewById<TextView>(
                R.id.btnStartSession
            )


        PasswordVisibilityHelper.attach(
            etSessionPassword,
            dialogView.findViewById(
                R.id.ivToggleSessionPassword
            )
        )


        PasswordVisibilityHelper.attach(
            etSessionPasswordConfirm,
            dialogView.findViewById(
                R.id.ivToggleSessionPasswordConfirm
            )
        )


        PasswordVisibilityHelper.attach(
            etCommonPassword,
            dialogView.findViewById(
                R.id.ivToggleCommonPassword
            )
        )


        PasswordVisibilityHelper.attach(
            etCommonPasswordConfirm,
            dialogView.findViewById(
                R.id.ivToggleCommonPasswordConfirm
            )
        )


        val commonAlreadySet =
            MessageRepository.hasCommonPassword(
                this
            )


        fun updateFieldVisibility() {

            if (
                rbUnique.isChecked
            ) {

                uniqueFields.visibility =
                    View.VISIBLE

                commonFields.visibility =
                    View.GONE

                tvCommonPasswordNote.visibility =
                    View.GONE

            } else {

                uniqueFields.visibility =
                    View.GONE


                if (commonAlreadySet) {

                    commonFields.visibility =
                        View.GONE

                    tvCommonPasswordNote.visibility =
                        View.VISIBLE

                } else {

                    commonFields.visibility =
                        View.VISIBLE

                    tvCommonPasswordNote.visibility =
                        View.GONE
                }
            }
        }


        updateFieldVisibility()


        rgPasswordType.setOnCheckedChangeListener {
                _, _ ->

            updateFieldVisibility()
        }


        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    "Start Session Lock"
                )
                .setView(dialogView)
                .setNegativeButton(
                    "Cancel",
                    null
                )
                .create()


        btnStart.setOnClickListener {

            tvError.visibility =
                View.GONE


            val biometricEnabled =
                cbBiometric.isChecked


            if (
                rbUnique.isChecked
            ) {

                val pass =
                    etSessionPassword
                        .text
                        .toString()


                val confirm =
                    etSessionPasswordConfirm
                        .text
                        .toString()


                when {

                    PasswordRulesHelper
                        .containsReservedChars(pass) -> {

                        tvError.text =
                            "Password cannot contain ',' or '/'"


                        tvError.visibility =
                            View.VISIBLE
                    }


                    pass.length < 4 -> {

                        tvError.text =
                            "Password must be at least 4 characters"


                        tvError.visibility =
                            View.VISIBLE
                    }


                    pass != confirm -> {

                        tvError.text =
                            "Passwords do not match"


                        tvError.visibility =
                            View.VISIBLE
                    }


                    else -> {

                        MessageRepository.startSession(

                            chatId = chatId,

                            useCommonPassword = false,

                            uniquePasswordHash =
                                SecurityUtils.hash(pass),

                            biometricEnabled =
                                biometricEnabled
                        )


                        Toast.makeText(
                            this,
                            "Session started",
                            Toast.LENGTH_SHORT
                        ).show()


                        dialog.dismiss()

                        refreshMessages()
                    }
                }

            } else {

                if (
                    commonAlreadySet
                ) {

                    MessageRepository.startSession(

                        chatId = chatId,

                        useCommonPassword = true,

                        uniquePasswordHash = null,

                        biometricEnabled =
                            biometricEnabled
                    )


                    Toast.makeText(
                        this,
                        "Session started",
                        Toast.LENGTH_SHORT
                    ).show()


                    dialog.dismiss()

                    refreshMessages()

                } else {

                    val pass =
                        etCommonPassword
                            .text
                            .toString()


                    val confirm =
                        etCommonPasswordConfirm
                            .text
                            .toString()


                    when {

                        PasswordRulesHelper
                            .containsReservedChars(pass) -> {

                            tvError.text =
                                "Password cannot contain ',' or '/'"


                            tvError.visibility =
                                View.VISIBLE
                        }


                        pass.length < 4 -> {

                            tvError.text =
                                "Password must be at least 4 characters"


                            tvError.visibility =
                                View.VISIBLE
                        }


                        pass != confirm -> {

                            tvError.text =
                                "Passwords do not match"


                            tvError.visibility =
                                View.VISIBLE
                        }


                        else -> {

                            MessageRepository.setCommonPassword(
                                this,
                                pass
                            )


                            MessageRepository.startSession(

                                chatId = chatId,

                                useCommonPassword = true,

                                uniquePasswordHash = null,

                                biometricEnabled =
                                    biometricEnabled
                            )


                            Toast.makeText(
                                this,
                                "Session started",
                                Toast.LENGTH_SHORT
                            ).show()


                            dialog.dismiss()

                            refreshMessages()
                        }
                    }
                }
            }
        }


        dialog.show()
    }


    // ======================================================
    // UNLOCK SESSION
    // ======================================================

    private fun showUnlockSessionDialog(
        session: ChatSession
    ) {

        val dialogView =
            LayoutInflater.from(this)
                .inflate(
                    R.layout.dialog_unlock_session,
                    null
                )


        val etPassword =
            dialogView.findViewById<EditText>(
                R.id.etUnlockSessionPassword
            )


        val tvError =
            dialogView.findViewById<TextView>(
                R.id.tvUnlockSessionError
            )


        val btnFingerprint =
            dialogView.findViewById<TextView>(
                R.id.btnUseFingerprintSession
            )


        val btnUnlock =
            dialogView.findViewById<TextView>(
                R.id.btnUnlockSession
            )


        PasswordVisibilityHelper.attach(
            etPassword,
            dialogView.findViewById(
                R.id.ivToggleUnlockSessionPassword
            )
        )


        etPassword.hint =
            if (
                session.useCommonPassword
            ) {
                "Enter common password"
            } else {
                "Enter unique password"
            }


        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    "Enter Session Password"
                )
                .setView(dialogView)
                .setNegativeButton(
                    "Cancel",
                    null
                )
                .create()


        fun unlockSuccess() {

            revealedSessionIds.add(
                session.id
            )


            messageAdapter.isBulkReveal =
                false


            dialog.dismiss()

            refreshMessages()
        }


        if (
            session.biometricEnabled
        ) {

            btnFingerprint.visibility =
                View.VISIBLE


            btnFingerprint.setOnClickListener {

                tryBiometricForSession(
                    session,
                    dialog,
                    ::unlockSuccess
                )
            }


            dialog.setOnShowListener {

                tryBiometricForSession(
                    session,
                    dialog,
                    ::unlockSuccess
                )
            }
        }


        btnUnlock.setOnClickListener {

            val entered =
                etPassword
                    .text
                    .toString()


            val isCorrect =
                if (
                    session.useCommonPassword
                ) {

                    MessageRepository
                        .verifyCommonPassword(
                            this,
                            entered
                        )

                } else {

                    SecurityUtils.hash(
                        entered
                    ) ==
                        session.uniquePasswordHash
                }


            if (isCorrect) {

                unlockSuccess()

            } else {

                tvError.text =
                    "Incorrect password"


                tvError.visibility =
                    View.VISIBLE
            }
        }


        dialog.show()
    }


    // ======================================================
    // VAULT LOCK
    // ======================================================

    private fun handleVaultLock() {

        if (isSelfChat) {

            if (
                ChatRepository.selfLocation ==
                ChatLocation.LOCKED
            ) {

                Toast.makeText(
                    this,
                    "This chat is already in Locked Chats",
                    Toast.LENGTH_SHORT
                ).show()


                return
            }

        } else {

            val item =
                ChatRepository.getChatById(
                    chatId
                )


            if (item == null) {

                Toast.makeText(
                    this,
                    "Chat not found",
                    Toast.LENGTH_SHORT
                ).show()


                return
            }


            if (
                item.location ==
                ChatLocation.LOCKED
            ) {

                Toast.makeText(
                    this,
                    "This chat is already in Locked Chats",
                    Toast.LENGTH_SHORT
                ).show()


                return
            }
        }


        val prefs =
            getSharedPreferences(
                "signalx_prefs",
                Context.MODE_PRIVATE
            )


        if (
            prefs.contains(
                "locked_chats_pass_hash"
            )
        ) {

            val intent =
                Intent(
                    this,
                    UnlockLockedChatsActivity::class.java
                )


            intent.putExtra(
                UnlockLockedChatsActivity.EXTRA_MODE,
                UnlockLockedChatsActivity.MODE_VERIFY_ONLY
            )


            verifyPasswordLauncher.launch(
                intent
            )

        } else {

            Toast.makeText(
                this,
                "Set a Locked Chats password first",
                Toast.LENGTH_SHORT
            ).show()


            startActivity(
                Intent(
                    this,
                    CreatePasswordActivity::class.java
                )
            )
        }
    }


    private fun performVaultLock() {

        if (isSelfChat) {

            ChatRepository.selfLocation =
                ChatLocation.LOCKED

        } else {

            ChatRepository.updateChat(
                chatId
            ) {
                it.copy(
                    location =
                        ChatLocation.LOCKED
                )
            }
        }


        Toast.makeText(
            this,
            "Moved to Locked Chats",
            Toast.LENGTH_SHORT
        ).show()


        finish()
    }
}