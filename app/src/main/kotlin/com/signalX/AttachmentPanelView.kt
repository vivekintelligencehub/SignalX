package com.signalX

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.util.AttributeSet
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AttachmentPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private lateinit var rvImages: RecyclerView
    private lateinit var galleryHeader: View
    private lateinit var optionsScroll: ScrollView

    private lateinit var selectionPanel: View
    private lateinit var selectedPreviewContainer: FrameLayout
    private lateinit var etCaption: EditText
    private lateinit var btnViewSelectedThumb: ImageView

    private lateinit var discardBar: View
    private lateinit var btnCancelDiscard: Button
    private lateinit var btnDiscard: Button

    private lateinit var imageAdapter: RecentImagesAdapter

    // Multi-select: order maayne rakhta hai (numbering + send order dono ke liye)
    private val selectedImages = mutableListOf<RecentImage>()

    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    private var collapsedY = 0f
    private var middleY = 0f
    private var fullY = 0f

    private var panelY = 0f

    private var screenHeight = 0

    // ChatActivity se aata hai — real input bar kahan khatam hoti hai
    private var collapsedAnchorPx = -1f

    private enum class State {
        COLLAPSED,
        MIDDLE,
        FULL
    }

    private var state = State.COLLAPSED

    // Discard-bar par "Cancel" dabane par wapas isi state par jaana hai
    private var stateBeforeDiscardPrompt = State.MIDDLE

    var onImageSend: ((List<Uri>, String) -> Unit)? = null

    init {
        inflate(context, R.layout.view_attachment_sheet, this)

        elevation = 30f
        setBackgroundColor(Color.WHITE)

        rvImages = findViewById(R.id.rvAttachmentImages)
        galleryHeader = findViewById(R.id.galleryHeader)
        optionsScroll = findViewById(R.id.optionsScroll)

        selectionPanel = findViewById(R.id.selectionPanel)
        selectedPreviewContainer = findViewById(R.id.selectedPreviewContainer)
        etCaption = findViewById(R.id.etMediaCaption)
        btnViewSelectedThumb = findViewById(R.id.btnViewSelectedThumb)

        discardBar = findViewById(R.id.discardBar)
        btnCancelDiscard = findViewById(R.id.btnCancelDiscard)
        btnDiscard = findViewById(R.id.btnDiscard)

        setupGallery()
        setupSelection()
        setupAttachmentButtons()
    }

    // =========================================================
    // ChatActivity ek baar yeh call karta hai jab use pata chal
    // jaata hai real input bar kahan khatam hoti hai — taaki
    // collapsed panel usi ke bilkul neeche se shuru ho, kabhi
    // usko cover na kare.
    // =========================================================

    fun setCollapsedAnchor(pxFromTop: Float) {

        collapsedAnchorPx = pxFromTop

        if (screenHeight > 0) {

            collapsedY = collapsedAnchorPx

            if (state == State.COLLAPSED && !dragging) {
                panelY = collapsedY
                translationY = panelY
            }
        }
    }

    // =========================================================
    // GALLERY
    // =========================================================

    private fun setupGallery() {

        imageAdapter = RecentImagesAdapter(
            mutableListOf()
        ) { image ->

            val wasFull = state == State.FULL

            toggleImageSelection(image)

            if (!wasFull && selectedImages.isNotEmpty()) {
                moveToMiddle()
            }
        }

        rvImages.layoutManager =
            GridLayoutManager(context, 4)

        rvImages.adapter = imageAdapter

        rvImages.isNestedScrollingEnabled = true

        findViewById<View>(R.id.btnGalleryClose)
            .setOnClickListener {
                hidePanel()
            }

        loadImages()
    }

    private fun loadImages() {

        val permission =
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_IMAGES
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }

        if (
            ContextCompat.checkSelfPermission(
                context,
                permission
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        // getAllImages() ek plain synchronous MediaStore query hai,
        // isliye background thread par chalate hain, result main
        // thread par post karte hain.

        Thread {

            val mediaImages = MediaStoreHelper.getAllImages(context)

            val recentImages = mediaImages.map { RecentImage(it.uri) }

            post {

                if (!isAttachedToWindow) return@post

                imageAdapter.update(recentImages)
            }

        }.start()
    }

    // =========================================================
    // ATTACHMENT OPTIONS
    // =========================================================

    private fun setupAttachmentButtons() {

        findViewById<View>(R.id.cellGallery)
            .setOnClickListener {
                moveToMiddle()
            }

        /*
         * Location, Contact, Document, Poll, Audio, Members,
         * Setting — existing IDs jyon ke tyon.
         */
    }

    // =========================================================
    // IMAGE SELECTION (multi-select)
    // =========================================================

    private fun toggleImageSelection(image: RecentImage) {

        val existingIndex =
            selectedImages.indexOfFirst { it.uri == image.uri }

        if (existingIndex >= 0) {
            selectedImages.removeAt(existingIndex)
        } else {
            selectedImages.add(image)
        }

        imageAdapter.updateSelection(selectedImages.map { it.uri })

        if (selectedImages.isEmpty()) {
            hideSelectionUi()
        } else {
            showSelectionUi()
        }
    }

    private fun showSelectionUi() {

        selectionPanel.visibility = View.VISIBLE
        optionsScroll.visibility = View.GONE
        galleryHeader.visibility = View.GONE
        discardBar.visibility = View.GONE

        Glide.with(context)
            .load(selectedImages.first().uri)
            .centerCrop()
            .into(btnViewSelectedThumb)

        renderSelectedPreview()
    }

    private fun hideSelectionUi() {

        selectionPanel.visibility = View.GONE

        etCaption.setText("")
        etCaption.clearFocus()

        if (state == State.COLLAPSED) {
            optionsScroll.visibility = View.VISIBLE
        }
    }

    private fun setupSelection() {

        findViewById<ImageButton>(R.id.btnMediaSend)
            .setOnClickListener {

                if (selectedImages.isEmpty()) return@setOnClickListener

                val uris = selectedImages.map { it.uri }
                val caption = etCaption.text.toString().trim()

                onImageSend?.invoke(uris, caption)

                clearSelection()
                hidePanel()
            }

        btnViewSelectedThumb.setOnClickListener {

            if (selectedImages.isEmpty()) return@setOnClickListener

            ImageViewerActivity.start(
                context,
                selectedImages.map { it.uri.toString() },
                0
            )
        }

        /*
         * Discard bar tab dikhta hai jab selection active rehte
         * hue sheet ko COLLAPSED tak drag kiya jaaye.
         */

        btnCancelDiscard.setOnClickListener {

            discardBar.visibility = View.GONE

            showSelectionUi()

            if (stateBeforeDiscardPrompt == State.FULL) {
                moveToFull()
            } else {
                moveToMiddle()
            }
        }

        btnDiscard.setOnClickListener {

            clearSelection()

            discardBar.visibility = View.GONE
            optionsScroll.visibility = View.VISIBLE

            moveToCollapsed()
        }
    }

    private fun clearSelection() {

        selectedImages.clear()

        imageAdapter.updateSelection(emptyList())

        selectedPreviewContainer.removeAllViews()

        etCaption.setText("")

        selectionPanel.visibility = View.GONE
    }

    // =========================================================
    // SELECTED-IMAGES PREVIEW GRID (1 / 2 / 3 / 4 / +N)
    // Same proportion-math as the sent-message grid, scaled to
    // fill this preview area instead of a fixed chat-bubble size.
    // =========================================================

    private fun renderSelectedPreview() {

        selectedPreviewContainer.post {

            val w = selectedPreviewContainer.width
            val h = selectedPreviewContainer.height

            if (w <= 0 || h <= 0) return@post

            buildPreviewGrid(w, h)
        }
    }

    private fun buildPreviewGrid(containerW: Int, containerH: Int) {

        selectedPreviewContainer.removeAllViews()

        val uris = selectedImages.map { it.uri }

        if (uris.isEmpty()) return

        val density = resources.displayMetrics.density
        val gapPx = (2 * density).toInt()

        fun addTile(uri: Uri, w: Int, h: Int, left: Int, top: Int, index: Int) {

            val iv = ImageView(context).apply {
                layoutParams = FrameLayout.LayoutParams(w, h).apply {
                    leftMargin = left
                    topMargin = top
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            Glide.with(iv).load(uri).centerCrop().into(iv)

            iv.setOnClickListener {
                ImageViewerActivity.start(
                    context,
                    uris.map { it.toString() },
                    index
                )
            }

            selectedPreviewContainer.addView(iv)
        }

        val halfW = (containerW - gapPx) / 2
        val halfH = (containerH - gapPx) / 2

        when (uris.size) {

            1 -> addTile(uris[0], containerW, containerH, 0, 0, 0)

            2 -> {
                addTile(uris[0], halfW, containerH, 0, 0, 0)
                addTile(uris[1], halfW, containerH, halfW + gapPx, 0, 1)
            }

            3 -> {
                addTile(uris[0], halfW, containerH, 0, 0, 0)
                addTile(uris[1], halfW, halfH, halfW + gapPx, 0, 1)
                addTile(uris[2], halfW, halfH, halfW + gapPx, halfH + gapPx, 2)
            }

            4 -> {
                addTile(uris[0], halfW, halfH, 0, 0, 0)
                addTile(uris[1], halfW, halfH, halfW + gapPx, 0, 1)
                addTile(uris[2], halfW, halfH, 0, halfH + gapPx, 2)
                addTile(uris[3], halfW, halfH, halfW + gapPx, halfH + gapPx, 3)
            }

            else -> {
                addTile(uris[0], halfW, halfH, 0, 0, 0)
                addTile(uris[1], halfW, halfH, halfW + gapPx, 0, 1)
                addTile(uris[2], halfW, halfH, 0, halfH + gapPx, 2)

                val overlay = TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(halfW, halfH).apply {
                        leftMargin = halfW + gapPx
                        topMargin = halfH + gapPx
                    }
                    text = "+${uris.size - 3}"
                    setBackgroundColor(0xCC000000.toInt())
                    setTextColor(Color.WHITE)
                    textSize = 20f
                    gravity = Gravity.CENTER
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }

                overlay.setOnClickListener {
                    ImageViewerActivity.start(context, uris.map { it.toString() }, 3)
                }

                selectedPreviewContainer.addView(overlay)
            }
        }
    }

    // =========================================================
    // PANEL POSITIONS
    // =========================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        screenHeight = h

        collapsedY =
            if (collapsedAnchorPx >= 0f) collapsedAnchorPx
            else h * 0.58f

        middleY = h * 0.40f

        fullY = 0f

        if (panelY == 0f) {
            panelY = collapsedY
            translationY = panelY
        }
    }

    // =========================================================
    // TOUCH / DRAG
    // =========================================================

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {

        if (discardBar.visibility == View.VISIBLE) return false

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                lastY = downY
                dragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - downY
                if (abs(dy) > 12f) {
                    dragging = true
                    return true
                }
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }

        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {

        if (discardBar.visibility == View.VISIBLE) return true

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                downY = event.rawY
                lastY = downY
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val currentY = event.rawY
                val dy = currentY - lastY
                movePanelBy(dy)
                lastY = currentY
                return true
            }

            MotionEvent.ACTION_UP -> {
                val totalDy = event.rawY - downY
                snapAfterGesture(totalDy)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                snapToNearest()
                return true
            }
        }

        return true
    }

    private fun movePanelBy(dy: Float) {

        panelY += dy
        panelY = max(fullY, min(collapsedY, panelY))
        translationY = panelY

        updateHeaderVisibility()
    }

    // =========================================================
    // SNAP LOGIC
    // =========================================================

    private fun snapAfterGesture(dy: Float) {

        val threshold = screenHeight * 0.18f

        if (dy < -threshold) {
            moveToFull()
            return
        }

        if (dy > threshold) {
            collapseFromDrag()
            return
        }

        snapToNearest()
    }

    private fun snapToNearest() {

        val distances = listOf(
            State.COLLAPSED to abs(panelY - collapsedY),
            State.MIDDLE to abs(panelY - middleY),
            State.FULL to abs(panelY - fullY)
        )

        val nearest =
            distances.minByOrNull { it.second }?.first
                ?: State.COLLAPSED

        when (nearest) {
            State.COLLAPSED -> collapseFromDrag()
            State.MIDDLE -> moveToMiddle()
            State.FULL -> moveToFull()
        }
    }

    /**
     * Jab bhi collapse COLLAPSED par jaake rukega, aur selection
     * active hai — toh discard bar dikhao, chupke se collapse
     * mat karo.
     */
    private fun collapseFromDrag() {

        if (selectedImages.isNotEmpty() && state != State.COLLAPSED) {

            stateBeforeDiscardPrompt = state

            selectionPanel.visibility = View.GONE
            optionsScroll.visibility = View.VISIBLE

            moveToCollapsed()

            discardBar.visibility = View.VISIBLE

        } else {

            moveToCollapsed()
        }
    }

    // =========================================================
    // ANIMATIONS
    // =========================================================

    fun moveToCollapsed() {
        state = State.COLLAPSED
        animatePanelTo(collapsedY)
    }

    fun moveToMiddle() {
        state = State.MIDDLE
        animatePanelTo(middleY)
    }

    fun moveToFull() {
        state = State.FULL
        animatePanelTo(fullY)
    }

    private fun animatePanelTo(target: Float) {

        animate()
            .translationY(target)
            .setDuration(220)
            .withEndAction {
                panelY = target
                updateHeaderVisibility()
            }
            .start()
    }

    // =========================================================
    // HEADER
    // =========================================================

    private fun updateHeaderVisibility() {

        val progress =
            1f - ((panelY - fullY) / (collapsedY - fullY)).coerceIn(0f, 1f)

        galleryHeader.alpha = progress.coerceIn(0f, 1f)

        galleryHeader.visibility =
            if (progress > 0.02f) View.VISIBLE else View.INVISIBLE
    }

    // =========================================================
    // OPEN / CLOSE
    // =========================================================

    fun showPanel() {

        visibility = View.VISIBLE

        clearSelection()

        optionsScroll.visibility = View.VISIBLE
        discardBar.visibility = View.GONE

        state = State.COLLAPSED

        post {
            if (screenHeight > 0) {
                panelY = collapsedY
                translationY = collapsedY
                updateHeaderVisibility()
            }
        }
    }

    fun hidePanel() {
        clearSelection()
        visibility = View.GONE
        hideKeyboard()
    }

    private fun hideKeyboard() {
        val imm =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }
}
