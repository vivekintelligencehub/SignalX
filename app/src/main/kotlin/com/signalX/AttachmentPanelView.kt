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
import com.signalX.utils.MediaStoreHelper
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
    private lateinit var imgSelected: ImageView
    private lateinit var etCaption: EditText

    private lateinit var discardBar: View
    private lateinit var btnCancelDiscard: Button
    private lateinit var btnDiscard: Button

    private lateinit var imageAdapter: RecentImagesAdapter

    private var selectedImage: RecentImage? = null

    private var downY = 0f
    private var lastY = 0f
    private var dragging = false

    private var collapsedY = 0f
    private var middleY = 0f
    private var fullY = 0f

    private var panelY = 0f

    private var screenHeight = 0

    private enum class State {
        COLLAPSED,
        MIDDLE,
        FULL
    }

    private var state = State.COLLAPSED

    var onImageSend: ((Uri, String) -> Unit)? = null

    init {
        inflate(context, R.layout.view_attachment_sheet, this)

        elevation = 30f
        setBackgroundColor(Color.WHITE)

        rvImages = findViewById(R.id.rvAttachmentImages)
        galleryHeader = findViewById(R.id.galleryHeader)
        optionsScroll = findViewById(R.id.optionsScroll)

        selectionPanel = findViewById(R.id.selectionPanel)
        imgSelected = findViewById(R.id.imgSelected)
        etCaption = findViewById(R.id.etMediaCaption)

        discardBar = findViewById(R.id.discardBar)
        btnCancelDiscard = findViewById(R.id.btnCancelDiscard)
        btnDiscard = findViewById(R.id.btnDiscard)

        setupGallery()
        setupSelection()
        setupAttachmentButtons()
    }

    // =========================================================
    // GALLERY
    // =========================================================

    private fun setupGallery() {

        imageAdapter = RecentImagesAdapter(
            mutableListOf()
        ) { image ->

            /*
             * Important WhatsApp-like rule:
             *
             * If gallery is not FULL:
             *     selecting an image moves to MIDDLE.
             *
             * If gallery is already FULL:
             *     stay FULL.
             */

            val wasFull = state == State.FULL

            selectImage(image)

            if (!wasFull) {
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

        MediaStoreHelper.getAllImages(context) { list ->

            post {

                if (!isAttachedToWindow) return@post

                imageAdapter.update(list)
            }
        }
    }

    // =========================================================
    // ATTACHMENT OPTIONS
    // =========================================================

    private fun setupAttachmentButtons() {

        /*
         * Gallery button from Viewer Attach Panel.xml
         */

        findViewById<View>(R.id.cellGallery)
            .setOnClickListener {

                /*
                 * Gallery option always brings gallery
                 * to the middle state.
                 */

                moveToMiddle()
            }

        /*
         * Other existing buttons remain untouched.
         *
         * Location
         * Contact
         * Document
         * Poll
         * Audio
         * Members
         * Setting
         *
         * Their existing IDs are intentionally preserved.
         */
    }

    // =========================================================
    // IMAGE SELECTION
    // =========================================================

    private fun selectImage(image: RecentImage) {

        selectedImage = image

        imgSelected.setImageURI(image.uri)

        selectionPanel.visibility = View.VISIBLE

        /*
         * Hide normal attachment options while image is selected.
         */
        optionsScroll.visibility = View.GONE

        /*
         * Header is not needed in selected-image state.
         */
        galleryHeader.visibility = View.GONE

        discardBar.visibility = View.GONE

        etCaption.setText("")

        /*
         * Do NOT automatically open keyboard.
         */
        etCaption.clearFocus()
    }

    private fun setupSelection() {

        findViewById<ImageButton>(R.id.btnMediaSend)
            .setOnClickListener {

                val image = selectedImage ?: return@setOnClickListener

                val caption =
                    etCaption.text.toString().trim()

                onImageSend?.invoke(
                    image.uri,
                    caption
                )

                clearSelection()
                hidePanel()
            }

        /*
         * If selected image is dragged down,
         * show Cancel / Discard.
         */

        btnCancelDiscard.setOnClickListener {

            discardBar.visibility = View.GONE

            selectedImage?.let {
                imgSelected.setImageURI(it.uri)
            }

            selectionPanel.visibility = View.VISIBLE
        }

        btnDiscard.setOnClickListener {

            clearSelection()

            discardBar.visibility = View.GONE

            optionsScroll.visibility = View.VISIBLE

            moveToCollapsed()
        }
    }

    private fun clearSelection() {

        selectedImage = null

        imgSelected.setImageDrawable(null)

        etCaption.setText("")

        selectionPanel.visibility = View.GONE
    }

    // =========================================================
    // PANEL POSITIONS
    // =========================================================

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)

        screenHeight = h

        /*
         * Collapsed:
         * attachment panel sits low enough that
         * messages/input remain visible.
         */

        collapsedY = h * 0.58f

        /*
         * Middle:
         * approximately 50-60% of screen is occupied.
         */

        middleY = h * 0.40f

        /*
         * Full:
         * entire screen.
         */

        fullY = 0f

        if (panelY == 0f) {
            panelY = collapsedY
            translationY = panelY
        }
    }

    // =========================================================
    // TOUCH / DRAG
    // =========================================================

    override fun onInterceptTouchEvent(
        event: MotionEvent
    ): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                downY = event.rawY
                lastY = downY
                dragging = false

                return false
            }

            MotionEvent.ACTION_MOVE -> {

                val dy =
                    event.rawY - downY

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

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                downY = event.rawY
                lastY = downY

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                val currentY = event.rawY

                val dy =
                    currentY - lastY

                movePanelBy(dy)

                lastY = currentY

                return true
            }

            MotionEvent.ACTION_UP -> {

                val totalDy =
                    event.rawY - downY

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

        panelY =
            max(
                fullY,
                min(collapsedY, panelY)
            )

        translationY = panelY

        updateHeaderVisibility()
    }

    // =========================================================
    // SNAP LOGIC
    // =========================================================

    private fun snapAfterGesture(dy: Float) {

        val threshold =
            screenHeight * 0.18f

        /*
         * Strong upward gesture
         */
        if (dy < -threshold) {

            when (state) {

                State.COLLAPSED ->
                    moveToFull()

                State.MIDDLE ->
                    moveToFull()

                State.FULL ->
                    moveToFull()
            }

            return
        }

        /*
         * Strong downward gesture
         */
        if (dy > threshold) {

            when (state) {

                State.FULL ->
                    moveToCollapsed()

                State.MIDDLE ->
                    moveToCollapsed()

                State.COLLAPSED ->
                    moveToCollapsed()
            }

            return
        }

        /*
         * Small gesture:
         * go to nearest snap position.
         */

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

            State.COLLAPSED ->
                moveToCollapsed()

            State.MIDDLE ->
                moveToMiddle()

            State.FULL ->
                moveToFull()
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

        /*
         * Header gradually appears while moving
         * from collapsed toward middle/full.
         */

        val progress =
            1f -
                (
                    (panelY - fullY) /
                        (collapsedY - fullY)
                    ).coerceIn(0f, 1f)

        galleryHeader.alpha =
            progress.coerceIn(0f, 1f)

        galleryHeader.visibility =
            if (progress > 0.02f)
                View.VISIBLE
            else
                View.INVISIBLE
    }

    // =========================================================
    // OPEN / CLOSE
    // =========================================================

    fun showPanel() {

        visibility = View.VISIBLE

        /*
         * Opening paperclip starts with options visible
         * and gallery in the lower position.
         */

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
            context.getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

        imm.hideSoftInputFromWindow(
            windowToken,
            0
        )
    }

    // =========================================================
    // SELECTED IMAGE DRAG BEHAVIOUR
    // =========================================================

    private fun showDiscardControls() {

        if (selectedImage == null) {
            return
        }

        discardBar.visibility = View.VISIBLE
    }
}