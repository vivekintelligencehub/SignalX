package com.signalX

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AttachmentPanelView — WhatsApp-style 3-stage attachment panel (FINAL, research-verified).
 *
 * STATES:
 *   COLLAPSED → SIRF white options area (keyboard ke baraabar); images ki 1 strip
 *               row usi white area ke bottom 82dp ke UPAR overlay hoti hai.
 *               Input box bilkul nahi hilta (black extra space nahi hai).
 *   MIDDLE    → gallery sheet options ke UPAR glide karti hai (options move nahi hote),
 *               header gradually fade-in, chat dim
 *   FULL      → poori screen; ab grid scroll chalti hai; baaki states mein grid scroll
 *               LOCKED rehti hai (sheet drag karti hai)
 *
 * RULES (video-verified):
 *   RULE 1: COLLAPSED mein photo tap  → select + sheet MIDDLE
 *   RULE 2: MIDDLE/FULL mein photo tap → sirf select/deselect (state same)
 *   RULE 3: Selection ke saath COLLAPSED ki taraf (drag / ✕ / scrim tap / back)
 *           → sheet PEHLE poori COLLAPSED hoti hai, PHIR discard popup upar aata hai.
 *           Cancel → jahan the wahi state recover. Discard → collapsed hi rahe, selection clear.
 *
 * ChatActivity compatibility (existing code untouched):
 *   showPanel(), hidePanel(), refreshImages(), onImageSend, onRequestMediaPermission
 *   + optional: setContentPushView(view), handleBackPress(), onOptionClicked, keyboardHeightPx
 */
class AttachmentPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    enum class State { COLLAPSED, MIDDLE, FULL }
    private enum class Zone { BAR, OPTIONS, HEADER, SHEET_BODY, OUTSIDE }

    // ---------------------------------------------------------------
    // Public callbacks — ChatActivity already wires these exact names
    // ---------------------------------------------------------------
    var onImageSend: ((List<Uri>, String) -> Unit)? = null
    var onRequestMediaPermission: (() -> Unit)? = null

    /** Baaki option cells: "location","contact","document","poll","audio","members","setting" */
    var onOptionClicked: ((String) -> Unit)? = null
    var onPanelVisibilityChanged: ((Boolean) -> Unit)? = null

    /** Keyboard ki last known height px (optional, exact keyboard-fit). ChatActivity se aa sakta hai. */
    var keyboardHeightPx: Int = 0
        set(v) {
            // v==0 = keyboard band hua → "unknown", last measured height YAAD rakh.
            // Isse tray HAMESHA keyboard naap ki rehti hai (input box kabhi na hile).
            if (v > dp(120f)) field = v
            if (panelH > 0) applySizing()
        }

    // ---------------------------------------------------------------
    // Views
    // ---------------------------------------------------------------
    private val scrimView: View
    private val optionsContainer: LinearLayout
    private val gallerySheet: LinearLayout
    private val headerSection: LinearLayout
    private val btnCloseGallery: TextView
    private val btnRecents: TextView
    private val btnHd: TextView
    private val galleryRecycler: RecyclerView

    private val selectionBar: LinearLayout
    private val barThumb: ImageView
    private val capEmoji: TextView
    private val etCaption: EditText
    private val btnSendImages: FrameLayout
    private val tvSendCount: TextView

    private val discardPopup: LinearLayout
    private val dpThumbs: LinearLayout
    private val btnDpCancel: TextView
    private val btnDpDiscard: TextView

    private val albumSheet: LinearLayout
    private val albumDragZone: View
    private val albumRecycler: RecyclerView
    private val fileBox: View

    private val editorOverlay: LinearLayout
    private val edClose: TextView
    private val edStrip: LinearLayout
    private val edHd: TextView
    private val edImage: ImageView
    private val edCaption: EditText
    private val edCount: TextView

    // ---------------------------------------------------------------
    // Data
    // ---------------------------------------------------------------
    private val imagesAdapter: RecentImagesAdapter
    private val albumsAdapter: AlbumsAdapter
    private var allImages: List<MediaImage> = emptyList()
    private var buckets: List<MediaBucket> = emptyList()
    private var selectedBucketId: String? = null   // null = Recents
    private val selection = mutableListOf<RecentImage>() // order = selection order
    private var imagesLoaded = false
    private var imagesLoading = false
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val maxSelection = 30

    // ---------------------------------------------------------------
    // Geometry / drag
    // ---------------------------------------------------------------
    private var panelH = 0
    private val rowH: Float get() = dp(82f).toFloat()   // item 80dp + 1dp+1dp margin (matches item_recent_image.xml)
    private val headerH: Float get() = dp(68f).toFloat() // handle pill (20dp) + header row (48dp)
    private var collapsedPanelPx = 0                 // options + 1 row (keyboard replacement)
    private var albumHeightPx = 0

    private val fullTop: Float get() = 0f
    private val middleTop: Float get() = panelH * 0.35f
    private val collapsedTop: Float get() = panelH - rowH - headerH
    private val hiddenTop: Float get() = panelH.toFloat()

    private var curTop = 0f
    private var currentState = State.COLLAPSED
    private var panelVisible = false
    private var openingClosing = false

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var downX = 0f
    private var downY = 0f
    private var dragStartTop = 0f
    private var dragStartState = State.COLLAPSED
    private var downZone = Zone.OUTSIDE
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    // RULE 3 pending
    private var pendingCollapseFrom: State? = null

    // push (input bar ko tray ke upar rakhta hai)
    private var contentPushView: View? = null
    private var pushOriginalBottom = 0

    // album sheet own drag
    private var abDownY = 0f
    private var abDragging = false

    // editor
    private var editorOpen = false
    private var edIndex = 0

    private val headerBg = GradientDrawable()
    private val bodyBg = GradientDrawable()
    private val sheetColor = 0xFF11181C.toInt()
    private var gridScrollLocked = true

    // ---------------------------------------------------------------
    // Init
    // ---------------------------------------------------------------
    init {
        LayoutInflater.from(context).inflate(R.layout.view_attachment_sheet, this, true)
        isClickable = false // root blindly touch consume NAHI karega (pass-through)

        scrimView = findViewById(R.id.scrimView)
        optionsContainer = findViewById(R.id.optionsContainer)
        gallerySheet = findViewById(R.id.gallerySheet)
        headerSection = findViewById(R.id.headerSection)
        btnCloseGallery = findViewById(R.id.btnCloseGallery)
        btnRecents = findViewById(R.id.btnRecents)
        btnHd = findViewById(R.id.btnHd)
        galleryRecycler = findViewById(R.id.galleryRecycler)

        selectionBar = findViewById(R.id.selectionBar)
        barThumb = findViewById(R.id.barThumb)
        capEmoji = findViewById(R.id.capEmoji)
        etCaption = findViewById(R.id.etCaption)
        btnSendImages = findViewById(R.id.btnSendImages)
        tvSendCount = findViewById(R.id.tvSendCount)

        discardPopup = findViewById(R.id.discardPopup)
        dpThumbs = findViewById(R.id.dpThumbs)
        btnDpCancel = findViewById(R.id.btnDpCancel)
        btnDpDiscard = findViewById(R.id.btnDpDiscard)

        albumSheet = findViewById(R.id.albumSheet)
        albumDragZone = findViewById(R.id.albumDragZone)
        albumRecycler = findViewById(R.id.albumRecycler)
        fileBox = findViewById(R.id.fileBox)

        editorOverlay = findViewById(R.id.editorOverlay)
        edClose = findViewById(R.id.edClose)
        edStrip = findViewById(R.id.edStrip)
        edHd = findViewById(R.id.edHd)
        edImage = findViewById(R.id.edImage)
        edCaption = findViewById(R.id.edCaption)
        edCount = findViewById(R.id.edCount)

        // z-order (drawing + touch priority)
        scrimView.elevation = dp(1f).toFloat()
        optionsContainer.elevation = dp(2f).toFloat()
        gallerySheet.elevation = dp(4f).toFloat()
        selectionBar.elevation = dp(6f).toFloat()
        albumSheet.elevation = dp(8f).toFloat()
        discardPopup.elevation = dp(10f).toFloat()
        editorOverlay.elevation = dp(12f).toFloat()

        // sheet styling: rounded corners -> FULL pe flat
        headerBg.setColor(sheetColor)
        headerBg.cornerRadii = topRadii(dp(16f).toFloat())
        headerSection.background = headerBg
        // body bg HAMESHA dark: COLLAPSED mein options ke neeche wali jagah white
        // nahi, DARK gallery strip dikhti hai (WhatsApp jaisa); images uske upar aati hain
        bodyBg.setColor(sheetColor)
        bodyBg.alpha = 255
        galleryRecycler.background = bodyBg

        // scrim: dim chat, tap = collapse attempt (RULE 3 compatible)
        scrimView.visibility = GONE
        scrimView.setOnClickListener { collapseThenAsk(currentState) }

        // grid — SAME adapter + SAME item_recent_image.xml. collapsed mein sirf
        // iski pehli row dikhti hai; duplicate grid nahi.
        // Dhyan: suppressLayout NAHI (wo items ka layout hi rok deta hai = blank grid!).
        // Sirf SCROLL lock hai: sheet drag pe rahe to grid scroll nahi hoti, FULL mein hoti hai.
        imagesAdapter = RecentImagesAdapter(mutableListOf()) { img -> onThumbTap(img) }
        gridScrollLocked = true
        galleryRecycler.layoutManager = object : GridLayoutManager(context, 4) {
            override fun canScrollVertically(): Boolean =
                !gridScrollLocked && super.canScrollVertically()
        }
        galleryRecycler.adapter = imagesAdapter
        galleryRecycler.overScrollMode = View.OVER_SCROLL_NEVER

        albumsAdapter = AlbumsAdapter(emptyList()) { bucket -> onAlbumPicked(bucket) }
        albumRecycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        albumRecycler.adapter = albumsAdapter

        wireClicks()
        wireAlbumGestures()

        // IMPORTANT: view_attach_panel.xml ki apni hard-coded (keyboard-naap) height ko
        // override karo — white box SIRF options ke barabar rahe, uske neeche koi white NAHI.
        // (optionsContainer children: [optPill, <include view_attach_panel>])
        optionsContainer.getChildAt(optionsContainer.childCount - 1)?.let { included ->
            (included.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT
                lp.weight = 0f
            }
        }

        headerSection.alpha = 0f
        headerSection.visibility = INVISIBLE
        optionsContainer.alpha = 0f
        curTop = hiddenTop
    }

    private fun wireClicks() {
        // Option cells (view_attach_panel.xml ke EXISTING ids — file unchanged)
        findViewById<View>(R.id.cellGallery).setOnClickListener {
            if (currentState != State.FULL) goState(State.MIDDLE)
        }
        findViewById<View>(R.id.cellLocation)?.setOnClickListener { onOptionClicked?.invoke("location") }
        findViewById<View>(R.id.cellContact)?.setOnClickListener { onOptionClicked?.invoke("contact") }
        findViewById<View>(R.id.cellDocument)?.setOnClickListener { onOptionClicked?.invoke("document") }
        findViewById<View>(R.id.cellPoll)?.setOnClickListener { onOptionClicked?.invoke("poll") }
        findViewById<View>(R.id.cellAudio)?.setOnClickListener { onOptionClicked?.invoke("audio") }
        findViewById<View>(R.id.cellMembers)?.setOnClickListener { onOptionClicked?.invoke("members") }
        findViewById<View>(R.id.cellSetting)?.setOnClickListener { onOptionClicked?.invoke("setting") }

        btnCloseGallery.setOnClickListener { collapseThenAsk(currentState) }

        btnRecents.setOnClickListener {
            if (isAlbumOpen()) closeAlbums() else openAlbums()   // TOGGLE
        }

        btnHd.setOnClickListener {
            val on = !btnHd.isSelected
            setHd(on)
            toast(if (on) "Items set to HD quality" else "Items set to standard quality")
        }
        edHd.setOnClickListener { btnHd.performClick() }

        fileBox.setOnClickListener {
            closeAlbums()
            onOptionClicked?.invoke("document")   // Files → existing document flow
        }

        // selection bar
        btnSendImages.setOnClickListener { sendSelection() }
        capEmoji.setOnClickListener {
            etCaption.requestFocus()
            showKeyboard(etCaption)
        }
        barThumb.setOnClickListener { openEditor() }

        // discard popup
        btnDpCancel.setOnClickListener {
            hideDiscardPopup()
            val back = pendingCollapseFrom ?: State.MIDDLE
            pendingCollapseFrom = null
            goState(back)   // Cancel → jahan the wahin recover
        }
        btnDpDiscard.setOnClickListener {
            hideDiscardPopup()
            pendingCollapseFrom = null
            clearSelection()   // Discard → collapsed hi rahe, sirf selection clear
            hideKeyboardNow()
        }

        // editor
        edClose.setOnClickListener { closeEditor() }
        findViewById<TextView>(R.id.toolUndo).setOnClickListener { toolPhase2("Undo") }
        findViewById<TextView>(R.id.toolRotate).setOnClickListener { toolPhase2("Rotate") }
        findViewById<TextView>(R.id.toolCrop).setOnClickListener { toolPhase2("Crop") }
        findViewById<TextView>(R.id.toolSticker).setOnClickListener { toolPhase2("Stickers") }
        findViewById<TextView>(R.id.toolText).setOnClickListener { toolPhase2("Text") }
        findViewById<TextView>(R.id.toolDraw).setOnClickListener { toolPhase2("Draw") }
        findViewById<View>(R.id.edSend).setOnClickListener {
            etCaption.setText(edCaption.text.toString())
            sendSelection()
        }
        findViewById<TextView>(R.id.edCapEmoji).setOnClickListener {
            edCaption.requestFocus()
            showKeyboard(edCaption)
        }
    }

    private fun toolPhase2(name: String) = toast("$name — option rakha hai, working phase-2")

    // ---------------------------------------------------------------
    // Public API (ChatActivity-compatible)
    // ---------------------------------------------------------------
    fun isOpen(): Boolean = panelVisible

    /** ChatActivity: chatMainContent pass karo — input bar tray ke upar push ho jaayega. */
    fun setContentPushView(v: View) {
        contentPushView = v
        pushOriginalBottom = v.paddingBottom
    }

    fun showPanel() {
        if (panelVisible) {
            if (currentState != State.COLLAPSED) goState(State.COLLAPSED)
            return
        }
        if (panelH == 0) {
            post { showPanel() }
            return
        }
        panelVisible = true
        visibility = VISIBLE
        onPanelVisibilityChanged?.invoke(true)
        loadImagesIfNeeded(force = false)

        curTop = hiddenTop
        updatePositions(curTop)
        animateOpenClose(opening = true)
    }

    fun hidePanel() {
        if (!panelVisible) return
        panelVisible = false
        closeEditor()
        closeAlbums()
        hideDiscardPopup()
        clearSelection()
        pendingCollapseFrom = null
        animateOpenClose(opening = false)
    }

    /** Permission grant ke baad ChatActivity isi ko call karti hai (existing wiring). */
    fun refreshImages() {
        imagesLoaded = false
        loadImagesIfNeeded(force = true)
    }

    /** ChatActivity.onBackPressed se sabse pehle call karo. true = consume ho gaya. */
    fun handleBackPress(): Boolean {
        if (!panelVisible) return false
        if (editorOpen) { closeEditor(); return true }
        if (isAlbumOpen()) { closeAlbums(); return true }
        if (discardPopup.visibility == VISIBLE) {
            btnDpCancel.performClick()   // back = Cancel (state recover)
            return true
        }
        if (selection.isNotEmpty() && currentState != State.COLLAPSED) {
            collapseThenAsk(currentState)
            return true
        }
        return when (currentState) {
            State.FULL -> { goState(State.MIDDLE); true }
            State.MIDDLE -> { goState(State.COLLAPSED); true }
            State.COLLAPSED -> { hidePanel(); true }
        }
    }

    // ---------------------------------------------------------------
    // Sizing
    // ---------------------------------------------------------------
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        panelH = h
        applySizing()
    }

    private fun applySizing() {
        if (panelH <= 0) return
        val optContent = optionsContainer.height.takeIf { it > 0 } ?: dp(210f)
        // PERMANENT RULE (user): tray ki TOTAL height = keyboard ki EXACT height
        // (live WindowInsets se measured) — input box ↔ keyboard switch pe BILKUL
        // NAHI hilta, har phone pe. dp number hardcode NAHI kyunki har phone ke
        // keyboard ki height alag hoti hai. (keyboard abhi tak kabhi na khuli ho
        // → fallback: options+strip fit)
        collapsedPanelPx = when {
            keyboardHeightPx > dp(120f) ->
                min(keyboardHeightPx, (panelH * 0.72f).toInt())
            else ->
                min(max(optContent + rowH.toInt(), dp(300f)), (panelH * 0.6f).toInt())
        }
        // white options box = tray ka upar wala hissa (total − dark strip row)
        val olp = optionsContainer.layoutParams as FrameLayout.LayoutParams
        val newOptH = (collapsedPanelPx - rowH.toInt()).coerceAtLeast(dp(140f))
        if (olp.height != newOptH || olp.bottomMargin != rowH.toInt()) {
            olp.height = newOptH
            olp.bottomMargin = rowH.toInt()
            optionsContainer.layoutParams = olp
        }
        // scrim: tray ke UPAR ka poora area (input bar tak) cover karta hai
        val slp = scrimView.layoutParams as FrameLayout.LayoutParams
        if (slp.bottomMargin != collapsedPanelPx) {
            slp.bottomMargin = collapsedPanelPx
            scrimView.layoutParams = slp
        }
        albumHeightPx = (panelH * 0.56f).toInt()
        val alp = albumSheet.layoutParams as FrameLayout.LayoutParams
        if (alp.height != albumHeightPx) {
            alp.height = albumHeightPx
            albumSheet.layoutParams = alp
        }

        curTop = if (panelVisible) topOf(currentState) else hiddenTop
        updatePositions(curTop)
        if (panelVisible) applyPush(collapsedPanelPx)
        if (isAlbumOpen()) albumSheet.translationY = 0f else albumSheet.translationY = albumHeightPx.toFloat()
    }

    private fun applyPush(px: Int) {
        contentPushView?.setPadding(
            contentPushView!!.paddingLeft,
            contentPushView!!.paddingTop,
            contentPushView!!.paddingRight,
            pushOriginalBottom + px
        )
    }

    private fun topOf(s: State): Float = when (s) {
        State.FULL -> fullTop
        State.MIDDLE -> middleTop
        State.COLLAPSED -> collapsedTop
    }

    // ---------------------------------------------------------------
    // Open / close animations
    // ---------------------------------------------------------------
    private fun animateOpenClose(opening: Boolean) {
        animator?.cancel()
        openingClosing = true

        val fromTop = curTop
        val toTop = if (opening) collapsedTop else hiddenTop
        val pv = contentPushView
        val pushFrom = (pv?.paddingBottom ?: 0) - pushOriginalBottom
        val pushTo = if (opening) collapsedPanelPx else 0
        val optFrom = optionsContainer.alpha
        val optTo = if (opening) 1f else 0f

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                updatePositions(fromTop + (toTop - fromTop) * t)
                optionsContainer.alpha = optFrom + (optTo - optFrom) * t
                if (pv != null) pv.setPadding(
                    pv.paddingLeft, pv.paddingTop, pv.paddingRight,
                    pushOriginalBottom + (pushFrom + (pushTo - pushFrom) * t).toInt()
                )
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    openingClosing = false
                    if (opening) {
                        currentState = State.COLLAPSED
                        gridScrollLocked = true
                    } else {
                        visibility = GONE
                        currentState = State.COLLAPSED
                        updatePositions(hiddenTop)
                        optionsContainer.alpha = 0f
                        applyPush(0)
                        onPanelVisibilityChanged?.invoke(false)
                    }
                }
            })
            start()
        }
    }

    // ---------------------------------------------------------------
    // Touch engine
    // ---------------------------------------------------------------
    private fun zoneAt(x: Float, y: Float): Zone {
        // selection bar sabse pehle (woh sheet ke bottom band pe overlay karta hai)
        if (selectionBar.visibility == VISIBLE && y >= selectionBar.top && y <= selectionBar.bottom) return Zone.BAR

        val sheetTop = curTop // sheet.top == 0 (match_parent child)
        val inSheet = y >= sheetTop
        val inHeader = inSheet && y <= sheetTop + headerH

        val o = optionsContainer
        val inOptions = y >= o.top && y <= o.bottom && y < sheetTop

        return when {
            inOptions -> Zone.OPTIONS
            inHeader -> Zone.HEADER
            inSheet -> Zone.SHEET_BODY
            else -> Zone.OUTSIDE
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!panelVisible || openingClosing || editorOpen) return false

        // album khuli ho to BAHAR ka pehla touch = sirf album band (consume)
        if (isAlbumOpen()) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                val r = IntArray(2).also { albumSheet.getLocationOnScreen(it) }
                val inside = ev.rawY >= r[1] && ev.rawY <= r[1] + albumSheet.height
                if (!inside) {
                    closeAlbums()
                    return true // consume — sheet drag / select kuch nahi hoga is touch se
                }
            }
            return false // album ke andar: RV/pill apna kaam sambhalte hain
        }
        if (discardPopup.visibility == VISIBLE) {
            // popup ke time drag/select block (popup ke apne buttons kaam karenge)
            return ev.actionMasked != MotionEvent.ACTION_DOWN
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragStartTop = curTop
                dragStartState = currentState
                downZone = zoneAt(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (downZone == Zone.OUTSIDE || downZone == Zone.BAR) return false
                val dy = ev.y - downY
                val dx = ev.x - downX
                if (downZone == Zone.SHEET_BODY && currentState == State.FULL) {
                    // FULL mein grid apna scroll karti hai — LEKIN grid EXACT top pe ho aur
                    // neeche kheencho → sheet drag (WhatsApp jaisa wapas-aane ka gesture)
                    if (dy > touchSlop && abs(dy) > abs(dx) && !galleryRecycler.canScrollVertically(-1)) {
                        startTracking(ev)
                        return true
                    }
                    return false
                }
                if (abs(dy) > touchSlop && abs(dy) > abs(dx)) {
                    startTracking(ev)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!panelVisible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(event)
                val newTop = dragStartTop + (event.y - downY)
                updatePositions(newTop.coerceIn(fullTop, hiddenTop))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                var vy = 0f
                velocityTracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000)
                    vy = it.yVelocity
                }
                stopTracking()
                settle(curTop, if (event.actionMasked == MotionEvent.ACTION_UP) vy else 0f, dragStartState)
            }
        }
        return true
    }

    private fun startTracking(ev: MotionEvent) {
        stopTracking()
        velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(ev)
    }

    private fun stopTracking() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun settle(y: Float, vy: Float, startedFrom: State) {
        val canDismiss = startedFrom == State.COLLAPSED
        val dismissZone = collapsedTop + (hiddenTop - collapsedTop) * 0.4f

        val target: State? = when {
            vy < -STRONG_FLING -> State.FULL
            vy > STRONG_FLING -> if (canDismiss && y > dismissZone) null else State.COLLAPSED
            vy < -MIN_FLING -> if (y > middleTop + touchSlop) State.MIDDLE else State.FULL
            vy > MIN_FLING -> when {
                y < middleTop - touchSlop -> State.MIDDLE
                canDismiss && y > dismissZone -> null
                else -> State.COLLAPSED
            }
            else -> {
                if (canDismiss && y > dismissZone && y > collapsedTop + touchSlop) null
                else {
                    val dFull = abs(y - fullTop)
                    val dMid = abs(y - middleTop)
                    val dCol = abs(y - collapsedTop)
                    when {
                        dFull <= dMid && dFull <= dCol -> State.FULL
                        dMid <= dCol -> State.MIDDLE
                        else -> State.COLLAPSED
                    }
                }
            }
        }

        when {
            target == null -> hidePanel()                          // tray dismiss (WhatsApp jaisa)
            target == State.COLLAPSED -> collapseThenAsk(startedFrom) // RULE 3 hook
            else -> goState(target)
        }
    }

    fun goState(state: State) = goStateInternal(state, null)

    private fun goStateInternal(state: State, onEnd: (() -> Unit)?) {
        animator?.cancel()
        val from = curTop
        val to = topOf(state)
        galleryRecycler.suppressLayout(false) // not used anymore; scroll lock gridScrollLocked se hota hai
        gridScrollLocked = state != State.FULL
        if (abs(to - from) < 1f) {
            currentState = state
            updatePositions(to)
            onEnd?.invoke()
            return
        }
        val dist = abs(to - from)
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (170 + (dist / panelH.coerceAtLeast(1)) * 160).toLong().coerceIn(170, 340)
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { a -> updatePositions(from + (to - from) * (a.animatedValue as Float)) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentState = state
                    updatePositions(to)
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    /** Sheet position + header gradual reveal + scrim + corners — sab yahin sync. */
    private fun updatePositions(y: Float) {
        curTop = y
        gallerySheet.translationY = y

        val revealSpan = (collapsedTop - middleTop).coerceAtLeast(1f)
        val reveal = ((collapsedTop - y) / revealSpan * 1.15f).coerceIn(0f, 1f)
        headerSection.alpha = reveal
        headerSection.visibility = if (reveal <= 0.02f) INVISIBLE else VISIBLE

        scrimView.alpha = reveal * 0.55f
        val scrimOn = reveal > 0.05f && panelVisible
        scrimView.visibility = if (scrimOn) VISIBLE else GONE

        val fullSpan = (middleTop - fullTop).coerceAtLeast(1f)
        val ff = ((middleTop - y) / fullSpan).coerceIn(0f, 1f)
        headerBg.cornerRadii = topRadii(dp(16f) * (1f - ff))
    }

    // ---------------------------------------------------------------
    // RULE 3 — pehle COLLAPSE, phir discard POPUP
    // ---------------------------------------------------------------
    private fun collapseThenAsk(fromState: State) {
        if (selection.isEmpty() || fromState == State.COLLAPSED) {
            goStateInternal(State.COLLAPSED, null)
            return
        }
        pendingCollapseFrom = fromState
        goStateInternal(State.COLLAPSED) { showDiscardPopup() }   // collapse complete hone pe popup
    }

    private fun showDiscardPopup() {
        if (pendingCollapseFrom == null) return
        dpThumbs.removeAllViews()
        val size = dp(46f)
        selection.take(5).forEachIndexed { i, img ->
            val iv = ImageView(context)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(6f)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            Glide.with(iv).load(img.uri).centerCrop().into(iv)
            dpThumbs.addView(iv)
        }
        discardPopup.visibility = VISIBLE
        discardPopup.translationY = discardPopup.height + dp(140f).toFloat()
        discardPopup.animate().translationY(0f).setDuration(220).start()
    }

    private fun hideDiscardPopup() {
        if (discardPopup.visibility != VISIBLE) return
        discardPopup.animate().translationY(discardPopup.height + dp(140f).toFloat())
            .setDuration(180).withEndAction { discardPopup.visibility = GONE }.start()
    }

    // ---------------------------------------------------------------
    // RULE 1 & 2 — photo tap (multi-select, in-place)
    // ---------------------------------------------------------------
    private fun onThumbTap(img: RecentImage) {
        val idx = selection.indexOfFirst { it.uri == img.uri }
        val added: Boolean
        if (idx >= 0) {
            selection.removeAt(idx)
            added = false
        } else {
            if (selection.size >= maxSelection) {
                toast("Max $maxSelection photos")
                return
            }
            selection.add(img)
            added = true
        }
        imagesAdapter.setSelection(selection.map { it.uri })
        updateSelectionBar()
        // RULE 1: collapsed tha → MIDDLE; middle/full tha → state wahin (RULE 2)
        if (added && currentState == State.COLLAPSED) goStateInternal(State.MIDDLE, null)
    }

    private fun clearSelection() {
        selection.clear()
        imagesAdapter.setSelection(emptyList())
        updateSelectionBar()
    }

    private fun updateSelectionBar() {
        val n = selection.size
        selectionBar.visibility = if (n > 0) VISIBLE else GONE
        if (n > 0) {
            tvSendCount.text = n.toString()
            Glide.with(barThumb).load(selection.first().uri).centerCrop().into(barThumb)
        }
    }

    private fun sendSelection() {
        if (selection.isEmpty()) return
        val caption = etCaption.text.toString().trim()
        hideKeyboardNow()
        // Sab selected images EK message mein jaate hain (uris list + ek caption)
        onImageSend?.invoke(selection.map { it.uri }, caption)
        clearSelection()
        closeEditor()
        etCaption.setText("")
        hidePanel()
    }

    // ---------------------------------------------------------------
    // EDITOR (fullscreen preview; tools abhi placeholders — jaisa bola tha)
    // ---------------------------------------------------------------
    private fun openEditor() {
        if (selection.isEmpty()) return
        editorOpen = true
        edIndex = 0
        edCaption.setText(etCaption.text.toString())
        editorOverlay.visibility = VISIBLE
        editorOverlay.alpha = 0f
        editorOverlay.animate().alpha(1f).setDuration(160).start()
        renderEditor()
    }

    private fun renderEditor() {
        edStrip.removeAllViews()
        val size = dp(40f)
        selection.forEachIndexed { k, img ->
            val iv = ImageView(context)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = dp(8f)
            iv.layoutParams = lp
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            val cur = k == edIndex
            iv.setPadding(if (cur) dp(2f) else 0, if (cur) dp(2f) else 0, if (cur) dp(2f) else 0, if (cur) dp(2f) else 0)
            iv.setBackgroundColor(if (cur) 0xFF00A884.toInt() else 0x00000000)
            iv.alpha = if (cur) 1f else 0.55f
            Glide.with(iv).load(img.uri).centerCrop().into(iv)
            iv.setOnClickListener { edIndex = k; renderEditor() }
            edStrip.addView(iv)
        }
        Glide.with(edImage).load(selection[edIndex].uri).fitCenter().into(edImage)
        edCount.text = selection.size.toString()
        syncHdChip()
    }

    private fun closeEditor() {
        if (!editorOpen) return
        editorOpen = false
        etCaption.setText(edCaption.text.toString())
        editorOverlay.animate().alpha(0f).setDuration(140).withEndAction {
            editorOverlay.visibility = GONE
            editorOverlay.alpha = 1f
        }.start()
    }

    // ---------------------------------------------------------------
    // HD
    // ---------------------------------------------------------------
    private fun setHd(on: Boolean) {
        btnHd.isSelected = on
        btnHd.text = if (on) "HD ✓" else "HD"
        btnHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt())
        syncHdChip()
    }

    private fun syncHdChip() {
        val on = btnHd.isSelected
        edHd.text = if (on) "HD ✓" else "HD"
        edHd.setTextColor(if (on) 0xFF25D366.toInt() else 0xFFFFFFFF.toInt())
    }

    // ---------------------------------------------------------------
    // Media loading (MediaStoreHelper — background thread, NO main-thread jank)
    // ---------------------------------------------------------------
    private fun hasMediaPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= 33)
            android.Manifest.permission.READ_MEDIA_IMAGES
        else
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadImagesIfNeeded(force: Boolean) {
        if (imagesLoading) return
        if (imagesLoaded && !force) return
        if (!hasMediaPermission()) {
            toast("DEBUG: media permission NAHI — request bheja (baad mein ye toast hatana)")
            onRequestMediaPermission?.invoke()
            return
        }
        imagesLoading = true
        ioExecutor.execute {
            // MediaStoreHelper synchronous hai — background thread pe sahi.
            // DEBUG: ab har failure toast karega taaki pata chale data kahan atka.
            val result = runCatching { MediaStoreHelper.getAllImages(context) }
            mainHandler.post {
                imagesLoading = false
                result.onFailure { t ->
                    if (t is SecurityException) {
                        toast("DEBUG: SecurityException — photos access blocked")
                        onRequestMediaPermission?.invoke()
                    } else {
                        toast("DEBUG load ERROR: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    return@post
                }
                val loaded = result.getOrDefault(emptyList())
                allImages = loaded
                imagesLoaded = true
                buckets = MediaStoreHelper.getBuckets(loaded)
                applyFilter()
                // DEBUG toast (baad mein hatana): data aa raha ya nahi — yehi batayega
                toast("DEBUG: ${loaded.size} photos load hue (adapter=${imagesAdapter.itemCount})")
            }
        }
    }

    private fun applyFilter() {
        val list = if (selectedBucketId == null) allImages
        else allImages.filter { it.bucketId == selectedBucketId }
        imagesAdapter.update(list.map { RecentImage(it.uri) })
    }

    // ---------------------------------------------------------------
    // Recents ▾ album picker (+ Files box) — toggle / outside / drag close
    // ---------------------------------------------------------------
    private fun isAlbumOpen(): Boolean = albumSheet.visibility == VISIBLE

    private fun openAlbums() {
        if (!imagesLoaded) return
        val rows = mutableListOf<MediaBucket>()
        rows.add(MediaBucket("", "Recents", allImages.firstOrNull()?.uri, allImages.size))
        rows.addAll(buckets)
        albumsAdapter.submit(rows, selectedBucketId)
        albumSheet.visibility = VISIBLE
        albumSheet.post {
            albumSheet.translationY = albumHeightPx.toFloat()
            albumSheet.animate().translationY(0f).setDuration(200).start()
        }
    }

    private fun closeAlbums() {
        if (!isAlbumOpen()) return
        albumSheet.animate().translationY(albumHeightPx.toFloat()).setDuration(180).withEndAction {
            albumSheet.visibility = GONE
        }.start()
    }

    private fun onAlbumPicked(bucket: MediaBucket) {
        selectedBucketId = if (bucket.id.isEmpty()) null else bucket.id
        btnRecents.text = "${bucket.name} ▾"
        applyFilter()
        closeAlbums()
    }

    /** Pill se drag-close + list top-overscroll drag-close, list natively scroll karti hai. */
    private fun wireAlbumGestures() {
        albumDragZone.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { abDownY = ev.rawY; abDragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - abDownY
                    if (dy > touchSlop) abDragging = true
                    if (abDragging) albumSheet.translationY = max(0f, dy)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (abDragging) {
                        if (albumSheet.translationY > albumHeightPx * 0.25f) closeAlbums()
                        else albumSheet.animate().translationY(0f).setDuration(160).start()
                    }
                    abDragging = false
                    true
                }
                else -> false
            }
        }

        // list top pe ho + neeche pull → sheet drag-close; warna normal scroll
        var listDownY = 0f
        var listTakingOver = false
        albumRecycler.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { listDownY = ev.rawY; listTakingOver = false }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - listDownY
                    if (!listTakingOver && dy > touchSlop && !albumRecycler.canScrollVertically(-1)) {
                        listTakingOver = true
                    }
                    if (listTakingOver) {
                        albumSheet.translationY = max(0f, dy)
                        albumRecycler.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (listTakingOver) {
                        if (albumSheet.translationY > albumHeightPx * 0.25f) closeAlbums()
                        else albumSheet.animate().translationY(0f).setDuration(160).start()
                    }
                    listTakingOver = false
                }
            }
            false
        }
    }

    // ---------------------------------------------------------------
    // Cleanup / helpers
    // ---------------------------------------------------------------
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
        stopTracking()
        ioExecutor.shutdown()
    }

    private fun hideKeyboardNow() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etCaption.windowToken, 0)
        imm.hideSoftInputFromWindow(edCaption.windowToken, 0)
        etCaption.clearFocus()
        edCaption.clearFocus()
    }

    private fun showKeyboard(target: EditText) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    private fun topRadii(r: Float) = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    companion object {
        private const val MIN_FLING = 1000f
        private const val STRONG_FLING = 2600f
    }

    // ---------------------------------------------------------------
    // Album list adapter (inner — MediaStoreHelper.getBuckets ka data)
    // ---------------------------------------------------------------
    private class AlbumsAdapter(
        private var rows: List<MediaBucket>,
        val onPick: (MediaBucket) -> Unit
    ) : RecyclerView.Adapter<AlbumsAdapter.VH>() {
        private var selectedId: String? = null

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val cover: ImageView = v.findViewById(R.id.albumCover)
            val name: TextView = v.findViewById(R.id.albumName)
            val count: TextView = v.findViewById(R.id.albumCount)
        }

        fun submit(newRows: List<MediaBucket>, sel: String?) {
            rows = newRows
            selectedId = sel
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_album_row, parent, false))

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val b = rows[position]
            holder.name.text = b.name
            holder.count.text = b.count.toString()
            if (b.coverUri != null) {
                Glide.with(holder.cover).load(b.coverUri).centerCrop().into(holder.cover)
            } else {
                holder.cover.setImageDrawable(null)
            }
            holder.itemView.setOnClickListener { onPick(b) }
        }
    }
}