/*
 * Copyright (C) 2026 DevEmperor (Dictate)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package dev.patrickgold.florisboard.dictate.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.graphics.toArgb
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.app.FlorisAppActivity
import dev.patrickgold.florisboard.app.FlorisPreferenceStore
import dev.patrickgold.florisboard.dictate.DictateController
import dev.patrickgold.florisboard.dictate.importer.TranscribeShareActivity
import dev.patrickgold.florisboard.dictate.recognition.RecognitionBridge
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonDesign
import dev.patrickgold.florisboard.dictate.DictateFloatingButtonSize
import dev.patrickgold.florisboard.dictate.data.prompts.PromptModel
import dev.patrickgold.florisboard.dictate.data.prompts.PromptsDatabaseHelper
import dev.patrickgold.florisboard.dictate.data.prompts.snippetBody
import dev.patrickgold.florisboard.dictate.ui.AudioReactiveCloudOrbView
import dev.patrickgold.florisboard.dictate.ui.DictateAuroraOrbView
import dev.patrickgold.florisboard.dictate.ui.DictateLatticeSphereView
import dev.patrickgold.florisboard.lib.devtools.flogDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * Owns the floating dictation button (issue #88): a small draggable bubble shown over other apps via a
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] window (no draw-over-apps permission needed —
 * the window is hosted by the accessibility service). The bubble appears whenever an editable field is
 * focused (or a dictation is in flight), toggles recording on tap, snaps to the nearest screen edge when
 * dragged, and routes the result through [DictateController] with [DictateController.OutputTarget.OVERLAY]
 * so the text is injected into the focused field.
 *
 * The bubble is two windows. The visuals sit in an *untouchable* window as big as the largest shape the
 * design can take, which never changes size or position while it is on screen except to be dragged; the
 * finger meets a transparent window on top of it, sized to the shape the user sees. See [positionVisual]
 * for why — in one sentence: a window that is resized on every frame of an animation is not drawn in
 * step with its own position on this platform.
 *
 * The visuals are provided by a [BubbleSkin] — [RingSkin], [PillSkin], [OrbSkin], or [CloudSkin] — selected by the
 * `floatingButtonDesign` preference. While recording, a level ticker feeds the chosen skin the shared
 * normalized microphone level.
 *
 * Created and owned by [DictateAccessibilityService], which also provides the foreground-microphone
 * promotion the recording needs while the app is in the background.
 */
class DictateBubbleController(private val service: DictateAccessibilityService) {

    private val context: Context get() = service
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val prefs by FlorisPreferenceStore

    /**
     * The transparent touch window's view, sized to the shape the user sees. Every geometry question —
     * travel, clamping, where the side buttons go — is asked of this one; the canvas follows it.
     */
    private var rootView: View? = null
    private var skin: BubbleSkin? = null
    private var params: WindowManager.LayoutParams? = null
    private var added = false

    /** The canvas window the skin draws in — [BubbleSkin.root] under these params; see [positionVisual]. */
    private var visualParams: WindowManager.LayoutParams? = null
    private var visualAdded = false

    /** Secondary cancel button shown beside the bubble while recording. */
    private var cancelView: View? = null
    private var cancelParams: WindowManager.LayoutParams? = null
    private var cancelAdded = false
    private val cancelSize get() = sdp(34)

    /** Optional undo button shown beside the bubble right after a dictation (issue #133). */
    private var undoView: View? = null
    private var undoParams: WindowManager.LayoutParams? = null
    private var undoAdded = false
    private val undoSize get() = sdp(34)

    /**
     * Distance between the bubble's visible shape and the cancel/undo button beside it.
     *
     * Measured generously on purpose: the orb and cloud designs *grow* while recording — their glow and
     * their surface reach well past the body they are at rest — and a gap tuned to the resting shape put
     * the button inside that halo exactly when it was on screen.
     */
    private val sideButtonGap get() = sdp(14)
    /** True from when a dictation just finished until the next recording starts or undo is tapped. */
    private var justDictated = false

    /** Long-press rewording menu window. */
    private var menuView: View? = null
    private var menuAdded = false

    private var currentDesign = DictateFloatingButtonDesign.PILL
    private var sizeScale = DictateFloatingButtonSize.MEDIUM.scale
    private var accentColor = 0xFF30B7E6.toInt()

    /**
     * Where the bubble is parked, as an intent rather than as a pixel pair (issue #323): which side, and
     * how far along the travel. The single source of truth for placement — every geometry the window ends
     * up in derives its coordinates from this, which is what lets a position survive a rotation, a fold,
     * or a move into split screen.
     */
    private var anchor: BubbleAnchor = BubbleAnchor.Default

    /**
     * Whether [anchor] describes a placement that actually happened — restored from preferences, or read
     * back off the bubble on screen — rather than the untouched default.
     *
     * What it guards is the first layout pass. A restored position arrives immediately when the service
     * starts, long before the bubble has been measured, and the default placement runs at the moment it
     * is: without this, every service restart threw the remembered position away and dropped the bubble
     * back at the right edge.
     */
    private var anchorIsPlaced = false

    /** Whether the bubble is currently anchored to the right edge (drives which way the pill expands). */
    private val anchoredToRight: Boolean get() = anchor.edge == BubbleEdge.RIGHT

    /**
     * Set until the bubble has been placed at its default spot (right edge, vertically centered) once the
     * view is first measured. The window params can't compute that before the bubble's size is known, so
     * the default placement is finalized in the first layout pass — with a margin regardless of the
     * snap-to-edge setting (unlike snapping, which only adds the margin when enabled).
     */
    private var needsInitialPlacement = true

    /** The app the bubble is currently floating over; the key its position is filed under. */
    private var currentPackage: String? = null

    /** While true a transient error/success flash owns the visuals; live-state updates are suppressed. */
    private var holding = false
    private var holdJob: Job? = null

    /** The deferred adding of the cancel button; see [manageCancel]. */
    private var cancelShowJob: Job? = null

    /** Polls the mic level while recording to drive the waveform. */
    private var tickerJob: Job? = null

    /** Animates the horizontal snap-to-edge after a drag. */
    private var snapAnim: ValueAnimator? = null

    /** Idle auto-dim: shrinks/fades the bubble to a small dot after a while; restored on touch. */
    private var dimJob: Job? = null
    private var dimmed = false
    private var idleShownPrev = false

    /** The state of the previous emission, used to tell a successful finish (busy → idle) from a cancel. */
    private var prevState: DictateController.UiState = DictateController.UiState.Idle

    /** The latest Recording state (timer source for the ticker), or null when not recording. */
    private var recordingState: DictateController.UiState.Recording? = null

    /** The last state pushed to the skin; lets us skip redundant re-applies (combine re-emits on focus). */
    private var lastAppliedState: DictateController.UiState? = null

    /**
     * Whether the bubble started the in-flight dictation. Used to attribute a terminal Error/success state
     * to the overlay (so it is surfaced here) without reacting to a keyboard-driven dictation that happens
     * while the bubble is also visible.
     */
    private var weStartedDictation = false

    /** Inputs that decide whether the bubble is shown and how it looks, combined from prefs + service. */
    private data class Inputs(
        val enabled: Boolean,
        val showWithDictateKeyboard: Boolean,
        val focused: Boolean,
        val dictateKeyboardActive: Boolean,
        val state: DictateController.UiState,
    )

    /** [Inputs] plus the design/size/color prefs and the IME-visible signal; one combined emission. */
    private data class Emission(
        val inputs: Inputs,
        val design: DictateFloatingButtonDesign,
        val size: DictateFloatingButtonSize,
        val imeVisible: Boolean,
        val accentColor: Int,
    )

    /** An [Emission] and the three signals from outside the bubble that can suppress it. */
    private data class Surroundings(
        val emission: Emission,
        val recognitionActive: Boolean,
        val screenOn: Boolean,
        val allowedInApp: Boolean,
    )

    /** Starts observing the feature toggle + focus + design + dictation state to drive the bubble. */
    fun start() {
        scope.launch {
            DictateAccessibilityService.foregroundPackage.collect { pkg -> onForegroundPackageChanged(pkg) }
        }
        scope.launch {
            val base = combine(
                prefs.dictate.floatingButtonEnabled.asFlow(),
                prefs.dictate.floatingButtonShowWithDictateKeyboard.asFlow(),
                DictateAccessibilityService.editableFocused,
                DictateAccessibilityService.dictateKeyboardActive,
                DictateController.state,
            ) { enabled, showWithKeyboard, focused, dictateKeyboard, state ->
                Inputs(enabled, showWithKeyboard, focused, dictateKeyboard, state)
            }
            val emissions = combine(
                base,
                prefs.dictate.floatingButtonDesign.asFlow(),
                prefs.dictate.floatingButtonSize.asFlow(),
                DictateAccessibilityService.imeVisible,
                prefs.dictate.floatingButtonColor.asFlow(),
            ) { inputs, design, size, imeVisible, color ->
                Emission(inputs, design, size, imeVisible, color.toArgb())
            }
            // Whether this app is one the button may appear over at all (#392). Built from the same
            // foreground-package flow the per-app anchors already ride on, so nothing new has to watch for
            // the app changing — a window change updates that flow, and the filter re-decides with it.
            val allowedInApp = combine(
                DictateAccessibilityService.foregroundPackage,
                prefs.dictate.floatingButtonAppScope.asFlow(),
                prefs.dictate.floatingButtonApps.asFlow(),
            ) { pkg, scope, apps ->
                BubbleApps.allows(scope, apps.toSet(), pkg)
            }
            combine(
                emissions,
                RecognitionBridge.active,
                DictateAccessibilityService.screenOn,
                allowedInApp,
            ) { emission, recogActive, screenOn, appAllowed ->
                Surroundings(emission, recogActive, screenOn, appAllowed)
            }.collect { (emission, recogActive, screenOn, appAllowed) ->
                val (inputs, design, size, imeVisible, accent) = emission
                val (enabled, showWithKeyboard, focused, dictateKeyboard, state) = inputs
                if (design != currentDesign || size.scale != sizeScale || accent != accentColor) {
                    currentDesign = design
                    sizeScale = size.scale
                    accentColor = accent
                    rebuildSkin()
                }
                // The Dictate keyboard is on screen when it is the selected IME *and* an IME window is
                // visible. While it is up it already has its own mic, so hide the bubble (unless the user
                // opted in). Using the IME-visible signal (not the dictation state) means a keyboard-driven
                // dictation keeps the bubble hidden, while a bubble-driven one — which opens no IME window —
                // still keeps it shown.
                val dictateKeyboardShown = dictateKeyboard && imeVisible
                val hiddenByOwnKeyboard = dictateKeyboardShown && !showWithKeyboard
                // The rule itself lives in [BubbleVisibility], where it can be asked without a phone. Note
                // that a bubble is *removed* rather than faded: alpha or GONE is a request to a compositor
                // we do not control, and that compositor is exactly the part behaving unexpectedly here.
                val show = BubbleVisibility.shouldShow(
                    enabled = enabled,
                    focused = focused,
                    state = state,
                    hiddenByOwnKeyboard = hiddenByOwnKeyboard,
                    recognitionActive = recogActive,
                    screenOn = screenOn,
                    allowedInApp = appAllowed,
                )
                if (show) ensureShown() else hide()
                // The rewording menu is a window of its own and does not come down with hide(). Tied to the
                // screen alone on purpose: taking it away whenever the bubble hides would be a different
                // change, about focus, not about the screen. The app filter is the exception, and for the
                // same reason it beats everything else: "nothing over this app" has to mean every window
                // we own, and a menu left standing would be the one that stayed (#392).
                if (!screenOn || !appAllowed) hidePromptMenu()
                recordingState = state as? DictateController.UiState.Recording
                applyState(state)
                manageForeground(state)
                manageTicker(state)
                manageKeepScreenOn(state)
                manageCancel(state, show)
                // Track when a dictation just finished so the undo button is offered only in that
                // window (until the next recording), not perpetually from a stale cached result.
                if (state is DictateController.UiState.Recording) {
                    justDictated = false
                } else if (state is DictateController.UiState.Idle &&
                    (prevState is DictateController.UiState.Transcribing || prevState is DictateController.UiState.Rewording)
                ) {
                    justDictated = true
                }
                manageUndo(state, show)
                reportTerminalState(state)
                // Auto-dim only while idle and shown; restore (and stop the timer) otherwise.
                val idleShown = state is DictateController.UiState.Idle && show
                if (idleShown && !idleShownPrev) scheduleDim()
                if (!idleShown) {
                    cancelDim()
                    applyDim(false)
                }
                idleShownPrev = idleShown
                prevState = state
            }
        }
    }

    /** Tears everything down: stops observing, removes the window and drops the foreground state. */
    fun destroy() {
        scope.cancel()
        stopTicker()
        cancelDim()
        snapAnim?.cancel()
        hidePromptMenu()
        hide()
        skin?.destroy()
        service.stopMicForeground()
    }

    // --- Window add/remove -----------------------------------------------------------------------

    private fun ensureShown() {
        if (added) return
        val view = rootView ?: createView().also { rootView = it }
        val visual = skin?.root ?: return
        val lp = params ?: createParams().also { params = it }
        val vlp = visualParams ?: createVisualParams().also { visualParams = it }
        syncShapeSize() // sizes the touch window to the shape; nothing is on screen yet, so nothing is pushed
        positionVisual() // and derives the canvas from it, likewise
        runCatching {
            // The canvas first, so the touch window lands above it. Input would not care either way — the
            // canvas is untouchable — but the seam is easier to reason about with the invisible one on top.
            if (!visualAdded) {
                windowManager.addView(visual, vlp)
                visualAdded = true
            }
            windowManager.addView(view, lp)
            added = true
        }
    }

    private fun hide() {
        val view = rootView
        if (added && view != null) runCatching { windowManager.removeView(view) }
        added = false
        val visual = skin?.root
        if (visualAdded && visual != null) runCatching { windowManager.removeView(visual) }
        visualAdded = false
        // Reset the dim so a re-shown bubble starts fully visible.
        cancelDim()
        dimmed = false
        idleShownPrev = false
        visual?.apply {
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
        hideCancel()
        hideUndo()
    }

    // --- Cancel button (shown while recording) ---------------------------------------------------

    private fun manageCancel(state: DictateController.UiState, shown: Boolean) {
        if (shown && state is DictateController.UiState.Recording) {
            if (cancelAdded || cancelShowJob?.isActive == true) return
            // A few frames later: adding a window is a round trip to the window manager, and the pill's
            // first step is drawn in this very frame. The button fades in over the opening anyway, so
            // nobody sees the difference — while the first frame no longer carries the cost.
            cancelShowJob = scope.launch {
                delay(CANCEL_SHOW_DELAY_MS)
                if (added && DictateController.state.value is DictateController.UiState.Recording) showCancel()
            }
        } else {
            cancelShowJob?.cancel()
            cancelShowJob = null
            hideCancel()
        }
    }

    private fun showCancel() {
        if (cancelAdded) return
        val v = cancelView ?: createCancelView().also { cancelView = it }
        val lp = cancelParams ?: createCancelParams().also { cancelParams = it }
        runCatching {
            // It lands at the far end of the *settled* pill straight away (the touch window is sized to
            // the settled shape), so it fades in over the pill's opening rather than popping up alone.
            service.noteOwnWindowChange()
            v.alpha = 0f
            windowManager.addView(v, lp)
            cancelAdded = true
            positionCancel()
            v.animate().alpha(1f).setDuration(240).start()
        }
    }

    private fun hideCancel() {
        val v = cancelView
        if (cancelAdded && v != null) {
            service.noteOwnWindowChange()
            runCatching { windowManager.removeView(v) }
        }
        cancelAdded = false
    }

    private fun createCancelView(): View {
        val size = cancelSize
        val pad = sdp(7)
        val icon = sideButtonIcon(R.drawable.ic_dictate_overlay_close, pad)
        return sideButtonHost(icon, size).apply {
            setOnClickListener {
                if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
                DictateController.cancelRecording()
            }
        }
    }

    private fun createCancelParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    // --- Undo button (shown beside the bubble right after a dictation, issue #133) ----------------

    private fun manageUndo(state: DictateController.UiState, shown: Boolean) {
        // Not while the bubble has auto-dimmed to a dot — the undo button would otherwise be left
        // standing at full size next to a shrunken bubble (issue #133 follow-up).
        val canUndo = shown && !dimmed && state is DictateController.UiState.Idle && justDictated &&
            prefs.dictate.floatingButtonUndoEnabled.get() && DictateController.hasLastDictation()
        if (canUndo) showUndo() else hideUndo()
    }

    private fun showUndo() {
        if (undoAdded) return
        val v = undoView ?: createUndoView().also { undoView = it }
        val lp = undoParams ?: createUndoParams().also { undoParams = it }
        runCatching {
            service.noteOwnWindowChange()
            windowManager.addView(v, lp)
            undoAdded = true
            positionUndo()
        }
    }

    private fun hideUndo() {
        val v = undoView
        if (undoAdded && v != null) {
            service.noteOwnWindowChange()
            runCatching { windowManager.removeView(v) }
        }
        undoAdded = false
    }

    private fun createUndoView(): View {
        val size = undoSize
        val pad = sdp(7)
        val icon = sideButtonIcon(R.drawable.ic_dictate_overlay_undo, pad)
        return sideButtonHost(icon, size).apply {
            setOnClickListener {
                if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
                val ok = DictateController.undoLastDictation(context)
                justDictated = false
                hideUndo()
                if (!ok) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.dictate__floating_button_undo_failed),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun createUndoParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun positionUndo() {
        val ulp = undoParams ?: return
        val undo = undoView ?: return
        val blp = params ?: return
        val bubble = rootView ?: return
        val uw = undoSize
        val gap = sideButtonGap
        ulp.y = (blp.y + (bubble.height - uw) / 2).coerceIn(0, (screenHeight() - uw).coerceAtLeast(0))
        // Same inward side as the cancel button so it sits beside the bubble and follows drags, and
        // measured to the visible shape the same way so the two never sit at different distances.
        val inset = skin?.visualInset ?: 0
        val left = leftX()
        val rawX = if (sideIsRight) left + inset - gap - uw else left + bubble.width - inset + gap
        ulp.x = rawX.coerceIn(0, (screenWidth() - uw).coerceAtLeast(0))
        if (undoAdded) runCatching { windowManager.updateViewLayout(undo, ulp) }
    }

    // --- Long-press rewording menu ---------------------------------------------------------------

    private fun onLongPress() {
        val state = DictateController.state.value
        // Holding runs the on-device model on this one dictation, exactly as holding the keyboard's key
        // does — same preference, same guard, so the two buttons never disagree about whether the
        // shortcut exists: while recording it sends there instead of to the cloud (#228), and while a
        // cloud request is still running it takes the recording back from it (#270). With no model
        // downloaded the transcription surfaces the "install one" feedback rather than the hold doing
        // nothing.
        if (prefs.dictate.longPressSendLocalModel.get() && DictateController.canLongPressLocal()) {
            if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
            cancelDim()
            applyDim(false)
            DictateController.holdForLocalModel(context)
            return
        }
        // Rewording only makes sense when not already recording/transcribing. A parked nudge is neither —
        // and used to make this menu open into nothing, the same way it blocked [applyPrompt] (#339).
        if (state !is DictateController.UiState.Idle && state !is DictateController.UiState.Error &&
            state !is DictateController.UiState.Promo
        ) {
            return
        }
        if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
        cancelDim()
        applyDim(false)
        showPromptMenu()
    }

    private fun showPromptMenu() {
        if (menuAdded) return
        scope.launch {
            val prompts = withContext(Dispatchers.IO) {
                runCatching { PromptsDatabaseHelper.getInstance(context).getAll() }.getOrDefault(emptyList())
            }.filter { !it.name.isNullOrBlank() }
            if (menuAdded) return@launch
            // Always show the menu — the Live Prompt entry (freeform voice command, #230) is always
            // available even with no saved rewording prompts.
            addPromptMenu(prompts)
        }
    }

    private fun addPromptMenu(prompts: List<PromptModel>) {
        // The rows, and nothing else: the card they sit on is built further down and stays put while
        // these scroll inside it.
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        fun menuItem(label: String, iconRes: Int, bold: Boolean, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                setTextColor(color(R.color.dictate_overlay_icon))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                val hz = dp(18)
                val vt = dp(12)
                setPadding(hz, vt, hz, vt)
                // Every row carries an icon, the prompts included: one with and one without would read as
                // a row whose icon failed to load, and the labels would no longer start at one edge.
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = dp(14)
                TextViewCompat.setCompoundDrawableTintList(
                    this,
                    ColorStateList.valueOf(color(R.color.dictate_overlay_icon)),
                )
                setOnClickListener { onClick() }
            }
        fun rowParams() = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        // The actions the user has put in this menu (#408) come first, above the rewording prompts and
        // separated from them by a rule: they open a screen instead of dictating, and a row reading
        // "Transcribe a file" in among the saved prompts would read as a third prompt. The hold is the
        // only free gesture this button has (#357), so this menu is where anything new has to go.
        val actions = BubbleMenuAction.entries.filter { it.pref(prefs).get() }
        actions.forEach { action ->
            list.addView(
                menuItem(context.getString(action.labelRes), action.iconRes, bold = false) {
                    hidePromptMenu()
                    runMenuAction(action)
                },
                rowParams(),
            )
        }
        if (actions.isNotEmpty()) {
            list.addView(menuDivider(), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1),
            ).apply {
                val m = dp(8)
                topMargin = m
                bottomMargin = m
            })
        }
        // Live Prompt heads the prompts (freeform voice command, #230): records a spoken instruction via
        // the floating button, then rewords it — with the selected text as context, or generating from
        // scratch — and injects the result. The icon is the chip's own on the prompt strip,
        // `Icons.Default.RecordVoiceOver` — the **filled** one: its outlined twin is a different icon and
        // reads as a different thing beside the filled rows around it.
        list.addView(
            menuItem(
                context.getString(R.string.quick_action__dictate_live_prompt),
                R.drawable.ic_dictate_live_prompt,
                bold = true,
            ) {
                hidePromptMenu()
                startingDictation {
                    DictateController.startLivePrompt(context, DictateController.OutputTarget.OVERLAY)
                }
            },
            rowParams(),
        )
        prompts.forEach { prompt ->
            list.addView(
                menuItem(prompt.name.orEmpty(), promptIcon(prompt), bold = false) {
                    hidePromptMenu()
                    DictateController.applyPrompt(
                        context, prompt, target = DictateController.OutputTarget.OVERLAY,
                    )
                },
                rowParams(),
            )
        }
        // The card is as wide as its widest row and no wider — it used to stretch across the whole screen
        // because a ScrollView gives an unparameterised child MATCH_PARENT — and no taller than a share of
        // the screen. Past that only the rows move: the scrolling view sits *inside* the card rather than
        // carrying it, so the rounded shape stays where it is instead of sliding with the list and
        // overscrolling past its own corners.
        val maxWidth = (screenWidth() * MENU_MAX_WIDTH).toInt() - dp(2 * MENU_PADDING_DP)
        val maxHeight = (screenHeight() * MENU_MAX_HEIGHT).toInt() - dp(2 * MENU_PADDING_DP)
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        }
        val card = FrameLayout(context).apply {
            background = roundedRect(color(R.color.dictate_overlay_menu_surface), dpf(16f))
            val p = dp(MENU_PADDING_DP)
            setPadding(p, p, p, p)
            // Clip to the background's own outline, so a row scrolling past the top or bottom is cut off
            // by the rounded corner instead of drawing over it.
            clipToOutline = true
            isClickable = true // swallow taps so they don't dismiss via the scrim
            elevation = dpf(8f)
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val scrim = FrameLayout(context).apply {
            // Transparent, not a dark full-screen dim: the menu floats over the app without covering the
            // whole screen; the invisible full-screen layer only catches an outside tap to dismiss.
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { hidePromptMenu() }
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        )
        menuView = scrim
        runCatching {
            windowManager.addView(scrim, lp)
            menuAdded = true
        }
    }

    private fun hidePromptMenu() {
        val v = menuView
        if (menuAdded && v != null) runCatching { windowManager.removeView(v) }
        menuAdded = false
        menuView = null
    }

    /**
     * The icon this prompt wears on the keyboard's prompt strip, as a drawable the menu can use.
     *
     * Deliberately the same three-way split as `dictatePromptIcon` — snippet, needs-a-selection,
     * everything else — so a prompt looks the same wherever the user meets it. If that split ever
     * changes, both places change together.
     */
    private fun promptIcon(prompt: PromptModel): Int = when {
        prompt.snippetBody() != null -> R.drawable.ic_dictate_prompt_snippet
        prompt.requiresSelection -> R.drawable.ic_dictate_prompt_selection
        else -> R.drawable.ic_dictate_prompt_auto
    }

    /** Runs one of the menu's actions (#408). The menu is already closed by the time this is called. */
    private fun runMenuAction(action: BubbleMenuAction) = when (action) {
        BubbleMenuAction.TRANSCRIBE_FILE -> launchFromBubble(TranscribeShareActivity.pickIntent(context))
        BubbleMenuAction.HISTORY -> openSettingsScreen("settings/dictate/history")
        BubbleMenuAction.SETTINGS -> openSettingsScreen("settings/dictate")
    }

    /**
     * The hairline that separates the menu's actions (#408) from the prompts below them.
     *
     * It measures itself at **no** width until it is given an exact one. A bare [View] takes the whole
     * width it is offered under an AT_MOST spec, so an ordinary divider made the menu as wide as its cap
     * instead of as wide as its longest row — a line has nothing to say about how wide the card should
     * be. The row above it decides that, and the vertical [LinearLayout] then stretches every
     * MATCH_PARENT child, this one included, to the width it settled on.
     */
    private fun menuDivider(): View = object : View(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val exact = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
            setMeasuredDimension(
                if (exact) MeasureSpec.getSize(widthMeasureSpec) else 0,
                MeasureSpec.getSize(heightMeasureSpec),
            )
        }
    }.apply {
        setBackgroundColor(ColorUtils.setAlphaComponent(color(R.color.dictate_overlay_icon), 40))
    }

    /**
     * Opens one of our own screens from the bubble, which is the first thing this overlay starts that is
     * not a dictation.
     *
     * A new task because an accessibility service has no activity of its own to grow a back stack on —
     * the same launch the keyboard's error chips already use ([DictateController.openProviderSettings]).
     * Activity starts are allowed from here: a service the system itself binds, which an accessibility
     * service is, is exempt from the background-start restriction.
     */
    private fun launchFromBubble(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /**
     * Opens a settings screen by its deep link. The component is named explicitly rather than left to
     * resolution, and CATEGORY_BROWSABLE is required — without it [FlorisAppActivity] reads a VIEW
     * intent as an extension import and lands somewhere else entirely.
     */
    private fun openSettingsScreen(path: String) = launchFromBubble(
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("ui://florisboard/$path"),
            context,
            FlorisAppActivity::class.java,
        ).addCategory(Intent.CATEGORY_BROWSABLE),
    )

    private fun roundedRect(colorInt: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(colorInt)
    }

    /** Places the cancel button on the inward side of the bubble, vertically centered, tracking its position. */
    private fun positionCancel() {
        val clp = cancelParams ?: return
        val cancel = cancelView ?: return
        val blp = params ?: return
        val bubble = rootView ?: return
        val cw = cancelSize
        val gap = sideButtonGap
        // The skin's pinned height when the view has not measured yet: this runs the moment the button is
        // added, and a height of zero would centre the circle half a diameter above the bubble.
        val bubbleHeight = bubble.height.takeIf { it > 0 } ?: skin?.shapeHeight ?: cw
        clp.y = (blp.y + (bubbleHeight - cw) / 2).coerceIn(0, (screenHeight() - cw).coerceAtLeast(0))
        // Put the cancel button on the side that has more room (the inward side). The side itself is read
        // from [sideIsRight], which the drag updates from the bubble's current centre as it moves — so the
        // button still flips on crossing the middle of the screen, in step with the row beside it.
        // Also measured to the visible shape, so the gap looks the same whichever design is on.
        val inset = skin?.visualInset ?: 0
        val left = leftX()
        val rawX = if (sideIsRight) left + inset - gap - cw else left + bubble.width - inset + gap
        clp.x = rawX.coerceIn(0, (screenWidth() - cw).coerceAtLeast(0))
        if (cancelAdded) runCatching { windowManager.updateViewLayout(cancel, clp) }
    }

    private fun createView(): View {
        val newSkin = when (currentDesign) {
            DictateFloatingButtonDesign.RING -> RingSkin(context)
            DictateFloatingButtonDesign.PILL -> PillSkin(context)
            DictateFloatingButtonDesign.ORB -> OrbSkin(context)
            DictateFloatingButtonDesign.CLOUD -> CloudSkin(context)
            DictateFloatingButtonDesign.AURORA -> AuroraSkin(context)
            DictateFloatingButtonDesign.LATTICE -> LatticeSkin(context)
        }
        skin = newSkin
        // Before the first layout pass: a pill built for the right edge has to be mirrored already when it
        // is measured, or its first frame is the unmirrored one. Pushed straight at the skin rather than
        // through [setSide], which would find the side unchanged and tell a skin that no longer exists.
        newSkin.onAnchorSideChanged(sideIsRight)
        // The window the finger meets: a transparent view sized to the shape the user sees. The visuals
        // live in a window of their own — see [positionVisual] for why.
        val root = View(context)
        attachTouch(root)
        // React only when the view's *width* changes (the pill expanding/collapsing). A plain position
        // change from dragging/snapping also fires this listener, and re-clamping then would fight the
        // drag — pulling the bubble back to the edge mid-drag (flicker). The width check ignores those,
        // so dragging is smooth and it only snaps back on release (via snapToEdge).
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (needsInitialPlacement && right - left > 0) {
                // First time the bubble has a real size: drop it at the default spot (right edge + margin,
                // vertically centered). Independent of snap-to-edge so the margin is always there.
                needsInitialPlacement = false
                applyInitialPlacement()
            } else if (kotlin.math.abs((right - left) - (oldRight - oldLeft)) > dp(2)) {
                // Only real size changes. The pill's running timer used to nudge the width by a fraction of
                // a pixel every second, and repositioning the window on each of those made the whole bubble
                // visibly flicker (#231). This is the invisible touch window now, sized to the settled
                // shape in one step per state, so a nudge cannot reach it at all.
                repositionForSize()
                if (cancelAdded) positionCancel() // keep the cancel button beside the (resized) pill
                if (undoAdded) positionUndo()
            } else if ((bottom - top) != (oldBottom - oldTop)) {
                // Height alone changing never moved the bubble, but the cancel button is centred on that
                // height — and it is placed before the bubble has measured, so at the larger button sizes
                // it ended up sitting slightly high beside the pill until something else nudged it.
                if (cancelAdded) positionCancel()
                if (undoAdded) positionUndo()
            }
        }
        newSkin.applyState(DictateController.state.value)
        return root
    }

    /** Rebuilds the view with the currently selected skin, preserving whether it was shown. */
    private fun rebuildSkin() {
        val wasShown = added
        hide()
        skin?.destroy()
        skin = null
        rootView = null
        // The cancel and undo buttons are built once at the scale in force at the time and then cached, so
        // without dropping them here they kept the size they were born with — which is how a smaller button
        // ended up beside a cancel circle bigger than itself.
        cancelView = null
        cancelParams = null
        undoView = null
        undoParams = null
        lastAppliedState = null // fresh skin starts blank; force the next applyState to paint it
        if (wasShown) ensureShown()
    }

    private fun createParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            // Measured from the wall the bubble is on, not from the left of the screen — see [wallGravity].
            gravity = Gravity.TOP or wallGravity(sideIsRight)
            // Rough seed: a screen's width past that wall, i.e. off-screen on the side it will appear from,
            // so nothing flashes at an edge before the bubble is measured. The exact default (the edge
            // margin, vertically centered) is applied in applyInitialPlacement once we know the bubble's
            // size; the anchor field already points at the same side, so nothing has to say so twice.
            x = -screenWidth()
            y = (screenHeight() * 2 / 5 - dp(28)).coerceAtLeast(0)
        }
    }

    /** Params for the canvas window; see [positionVisual] for what it is and why it is untouchable. */
    private fun createVisualParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or wallGravity(sideIsRight)
            // Seeded off-screen like the touch window; [positionVisual] derives the real place from it.
            x = -screenWidth()
            y = 0
        }
    }

    // --- Touch: tap toggles dictation, drag repositions + snaps to the edge ----------------------

    private fun attachTouch(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startLeft = 0
        var startY = 0
        var moved = false
        var longPressFired = false
        val longPress = Runnable {
            if (!moved) {
                longPressFired = true
                onLongPress()
            }
        }
        view.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startLeft = leftX()
                    startY = lp.y
                    moved = false
                    longPressFired = false
                    snapAnim?.cancel()
                    cancelDim()
                    applyDim(false) // wake the bubble on touch
                    v.postDelayed(longPress, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        v.removeCallbacks(longPress) // a drag cancels the pending long-press
                    }
                    if (moved) {
                        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
                        val maxY = (screenHeight() - v.height).coerceAtLeast(0)
                        val left = (startLeft + dx.toInt()).coerceIn(0, maxX)
                        lp.y = (startY + dy.toInt()).coerceIn(0, maxY)
                        // Turn the row round the instant the bubble's centre crosses the middle, which is
                        // the same instant the cancel button jumps to the other side (#399). Before the new
                        // x is written, since the side is also the wall that x is measured from — and before
                        // the buttons are placed, since mirroring is what decides where their side is.
                        setSide(left + v.width / 2 >= screenWidth() / 2)
                        setLeftX(left)
                        pushBubble()
                        if (cancelAdded) positionCancel() // keep the cancel button following the bubble
                        if (undoAdded) positionUndo()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    when {
                        longPressFired -> Unit // handled by the long-press (prompt menu)
                        !moved -> onTap()
                        // When snapping is off the bubble stays where it was dropped (already clamped within
                        // the screen by ACTION_MOVE); otherwise it animates to the nearer side edge.
                        prefs.dictate.floatingButtonSnapToEdge.get() -> snapToEdge()
                    }
                    if (moved) rememberCurrentPosition()
                    scheduleDim() // restart the idle timer after the interaction
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Gap between the screen edge and the shape the user actually sees when the bubble is parked at a side.
     *
     * Every caller has to go through here. There used to be two spellings of it — one subtracting the
     * skin's [BubbleSkin.visualInset] and one not — so the same button sat at two different distances
     * depending on how it got there: dragged and snapped, or laid out afresh after a design, size or colour
     * change. It is measured to the visible shape, so the glow designs' empty halo does not count as part
     * of the gap.
     */
    private fun edgeMargin(maxX: Int): Int =
        (dp(EDGE_MARGIN_DP) - (skin?.visualInset ?: 0)).coerceAtLeast(0).coerceAtMost(maxX / 2)

    /** Animates the bubble to whichever side edge is nearer, clamping the vertical position on screen. */
    private fun snapToEdge() {
        val lp = params ?: return
        val v = rootView ?: return
        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
        val margin = edgeMargin(maxX)
        lp.y = lp.y.coerceIn(0, (screenHeight() - v.height).coerceAtLeast(0))
        // The drag decided which side; read that off the dropped position before animating towards it.
        // After the clamp, so a drop past the bottom edge is anchored where it lands, not where it went.
        captureAnchor()
        // The window is measured from the wall it is on, so the resting place is the same number on either
        // side: the margin.
        val targetX = margin
        snapAnim?.cancel()
        snapAnim = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                lp.x = it.animatedValue as Int
                pushBubble()
                if (cancelAdded) positionCancel() // let the cancel button ride along to the edge
                if (undoAdded) positionUndo()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    rememberCurrentPosition() // persist the final snapped position for this app
                }
            })
            start()
        }
    }

    /**
     * Keeps the window on screen after a size change (the pill opening or closing).
     *
     * The end the user is pointing at holds still by construction: the window is measured from the wall
     * the bubble is on ([wallGravity]), so the window manager pins that edge itself, in the same relayout
     * that carries the new size. It used to be pinned from here instead, by moving x by the change in
     * width once the layout had happened — one frame after the window had already grown at the old x. On
     * the right the far edge therefore poked out and snapped back on every frame of the open animation,
     * and the small steps at its tail fell under the guard in the layout listener and were never made up
     * at all; the left never needed moving and never juddered.
     *
     * What is left to do is the far end: a snapped bubble is put back at its margin, a free one is kept
     * from running off the opposite edge of the screen.
     */
    private fun repositionForSize() {
        val lp = params ?: return
        val v = rootView ?: return
        if (!added) return
        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
        val maxY = (screenHeight() - v.height).coerceAtLeast(0)
        val snapped = prefs.dictate.floatingButtonSnapToEdge.get()
        val nx = (if (snapped) edgeMargin(maxX) else lp.x).coerceIn(0, maxX)
        val ny = lp.y.coerceIn(0, maxY)
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            pushBubble()
        }
    }

    /** Places the bubble at its default spot — right edge with a margin, ~60% up from the bottom — once it
     *  has been measured. Used for the first show; the margin is applied whether or not snap-to-edge is on. */
    private fun applyInitialPlacement() {
        val lp = params ?: return
        val v = rootView ?: return
        // A position remembered from a previous run beats the default. It was restored before the bubble
        // had a size to place it against, so this is the first moment it can actually be honoured.
        if (anchorIsPlaced) {
            applyAnchor()
            return
        }
        val maxX = (screenWidth() - v.width).coerceAtLeast(0)
        val maxY = (screenHeight() - v.height).coerceAtLeast(0)
        val margin = edgeMargin(maxX)
        setLeftX((maxX - margin).coerceAtLeast(0))
        // Vertically center the bubble at ~60% up from the bottom edge (≈40% down from the top).
        lp.y = (screenHeight() * 2 / 5 - v.height / 2).coerceIn(0, maxY)
        // Read the anchor off the spot we just placed, rather than the other way round: the default keeps
        // its margin to the edge, and the margin is a length that only exists once the bubble is measured.
        captureAnchor()
        pushBubble()
    }

    /**
     * The size of the area the bubble's coordinates are measured in — which is **not** the display.
     *
     * `WindowManager.LayoutParams.x/y` are relative to the window's parent frame, and this window does not
     * ask for `FLAG_LAYOUT_IN_SCREEN`, so that frame is the display minus the system decorations. In
     * portrait the difference is only vertical and nothing gives it away. In landscape it is horizontal:
     * on a Galaxy A55 the camera cutout takes a strip off the side, and the frame is 2251 px wide on a
     * 2340 px display. Measuring against the display and placing against the frame put the bubble 89 px
     * further out than there was room for, and it hung over the edge (issue #323, found on hardware).
     *
     * The system-bar insets are subtracted as well as the cutout, even though the frame does not always
     * shrink for them — a gesture bar does not inset it, a three-button navigation bar does, and in
     * landscape that one sits on a side too. Subtracting one too many costs the bubble a few pixels of
     * reach at an edge it was never parked against; subtracting one too few puts it off the screen, where
     * it cannot be dragged back. Only one of those two is recoverable.
     *
     * Read fresh on every call, never cached: the answer changes underneath a running overlay whenever the
     * device is rotated, unfolded or moved into split screen, which is the whole of the issue.
     */
    private fun screenWidth(): Int = screenSize().x

    private fun screenHeight(): Int = screenSize().y

    private fun screenSize(): Point {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Point(
                (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(0),
                (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(0),
            )
        }
        val dm = context.resources.displayMetrics
        return Point(dm.widthPixels, dm.heightPixels)
    }

    /** How far the bubble can travel horizontally, i.e. the x of the right edge. */
    private fun travelX(): Int = (screenWidth() - (rootView?.width ?: 0)).coerceAtLeast(0)

    /** How far the bubble can travel vertically, i.e. the y of the bottom edge. */
    private fun travelY(): Int = (screenHeight() - (rootView?.height ?: 0)).coerceAtLeast(0)

    /** Reads the anchor back out of wherever the window currently sits. */
    private fun captureAnchor() {
        val lp = params ?: return
        anchor = BubbleAnchor.capture(leftX(), lp.y, travelX(), travelY())
        anchorIsPlaced = true
        syncSkinToAnchor()
    }

    /**
     * Which half of the screen the bubble is on, as the single answer everything that has a side takes
     * its side from: the cancel button, the undo button, the way a growing design lays its row out, and
     * the wall the window itself is measured from — which is what decides which end of the bubble holds
     * still when it changes width (#399, [wallGravity]).
     *
     * It used to be worked out three times over, and that was survivable only as long as the pieces
     * involved could not contradict each other. Once the row itself has a side, they can: the cancel
     * button flips the moment the bubble's centre crosses the middle of the screen, mid-drag, and a row
     * that waited for the drop would have sat the wrong way round against a button that had already
     * moved. One value, set in one place, cannot disagree with itself.
     */
    private var sideIsRight = true

    /**
     * The window gravity for the wall the bubble is on.
     *
     * The window is measured *from that wall*: its x is the gap to the wall, and the edge against the
     * wall is the one the window manager keeps still when the window changes size, in the same relayout
     * that carries the new size. The left side has always worked this way, which is why the pill opened
     * smoothly there; the right side used to be measured from the left as well and moved by hand a frame
     * later, and juddered. Measured from its own wall it is the left side's mirror image.
     *
     * LEFT and RIGHT rather than START and END on purpose: END is resolved through the layout direction,
     * and which wall the bubble is parked at has nothing to do with the language.
     */
    @SuppressLint("RtlHardcoded")
    private fun wallGravity(onRight: Boolean): Int = if (onRight) Gravity.RIGHT else Gravity.LEFT

    /**
     * The window's left edge, whichever wall its params measure from.
     *
     * The controller keeps thinking in left edges — the anchor, the drag and the side buttons all do —
     * and only the params speak in gaps to the wall. [bubbleXFromWall] translates, and is its own inverse,
     * so [setLeftX] is the same conversion the other way round.
     */
    private fun leftX(): Int {
        val lp = params ?: return 0
        return bubbleXFromWall(lp.x, rootView?.width ?: 0, screenWidth(), sideIsRight)
    }

    /** Writes a left edge into the params in the terms of the wall they measure from; not pushed to the window. */
    private fun setLeftX(left: Int) {
        val lp = params ?: return
        lp.x = bubbleXFromWall(left, rootView?.width ?: 0, screenWidth(), sideIsRight)
    }

    /** Pushes the touch window's params to the window manager, and moves the canvas with it. */
    private fun pushBubble() {
        val v = rootView
        val lp = params
        if (added && v != null && lp != null) runCatching { windowManager.updateViewLayout(v, lp) }
        positionVisual()
    }

    /**
     * Puts the canvas window where the touch window says the shape is.
     *
     * Why two windows at all: the pill grows when a recording starts, and a window that grows with it is
     * resized on every frame of that animation. The window manager applies each new size and position in
     * a transaction of its own, the app's buffer for that size arrives in another, and nothing ties the
     * two together for a client-requested resize — `WindowManagerService.relayoutWindow` hands out a sync
     * id only inside a sync group (a transition, an IME animation), never for a plain relayout. Measured
     * on the A55 (2026-09-17, 60 fps screen recording of the open animation): the position advanced on
     * every frame with the requested width while the buffer caught up only every 7–13 frames, so the
     * whole pill, stop glyph included, slid inwards on a stale buffer and jumped back out by 20–40 px on
     * each fresh one, into the shadow margin. Which end the position is measured from (#399) changes
     * nothing about that; the left side was only ever smooth because its position never changes.
     *
     * So the visible window is never resized. It is a canvas as wide as the open pill plus the same again
     * on the far side, centred on the button, and the pill grows *inside* it — an ordinary in-window
     * animation with no relayout involved. Its transparent parts must not swallow taps, so it carries
     * [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] (accessibility overlays count as trusted overlays,
     * so touches pass straight through to the app), and the window the finger meets is a separate,
     * transparent one sized to the settled shape — resized on a state change, which nobody can see. The
     * two are moved together and land in the same frame, the window manager placing both in one surface
     * placement; and the touch window is invisible in any case, so only the canvas is ever seen moving.
     *
     * The canvas is centred on the button rather than hung off its inboard side so that changing sides
     * mid-drag re-expresses the same frame instead of moving the window — see [BubbleSkin.canvasOverhang].
     */
    private fun positionVisual() {
        val lp = params ?: return
        val vlp = visualParams ?: return
        val s = skin ?: return
        vlp.gravity = Gravity.TOP or wallGravity(sideIsRight)
        // The canvas reaches past the shape on the wall side by its overhang, so it sits that much closer
        // to the wall — off-screen, for the pill. It is as tall as the shape, so y is simply shared.
        vlp.x = lp.x - s.canvasOverhang
        vlp.y = lp.y
        if (visualAdded) runCatching { windowManager.updateViewLayout(s.root, vlp) }
    }

    /**
     * Sizes the touch window to the shape the skin reports for its state. Nobody can see this window, so
     * a size change here costs nothing — which is the whole point of keeping it apart from the canvas.
     */
    private fun syncShapeSize() {
        val lp = params ?: return
        val s = skin ?: return
        if (lp.width == s.shapeWidth && lp.height == s.shapeHeight) return
        lp.width = s.shapeWidth
        lp.height = s.shapeHeight
        val v = rootView
        if (added && v != null) {
            service.noteOwnWindowChange()
            runCatching { windowManager.updateViewLayout(v, lp) }
        }
    }

    /**
     * Moves everything that has a side onto [onRight], if that is not where it already is.
     *
     * The window's params are re-expressed from the new wall first, frame unchanged, and pushed at once:
     * a window still measured from the old wall would grow the wrong way if the pill opened before
     * anything else had reason to update it. The skin is told before the buttons are placed, because
     * mirroring the row is what changes where the inward side *is* — placing them first would put them
     * beside the shape as it was.
     */
    private fun setSide(onRight: Boolean) {
        if (sideIsRight == onRight) return
        val left = leftX() // read through the old wall, before the side changes hands
        sideIsRight = onRight
        params?.let { lp ->
            lp.gravity = Gravity.TOP or wallGravity(onRight)
            setLeftX(left)
            pushBubble()
        }
        skin?.onAnchorSideChanged(onRight)
        if (cancelAdded) positionCancel()
        if (undoAdded) positionUndo()
    }

    /**
     * Tells the skin which wall the bubble is parked at, so a design that grows can grow the right way
     * (#399). Called from every place the anchor is set — read back off the screen, or restored for an
     * app — and once for each freshly built skin, which is the whole of it: nothing else assigns [anchor].
     */
    private fun syncSkinToAnchor() {
        setSide(anchoredToRight)
    }

    /**
     * Moves the window to where the anchor points on the *current* screen.
     *
     * Deliberately one-way: this never reads the position back. Every caller runs at a moment when the
     * geometry is in flux — a hinge delivers several configuration changes for one movement — and an
     * apply that also captured would fold each intermediate state into the anchor and walk the bubble
     * across the screen.
     */
    private fun applyAnchor() {
        val lp = params ?: return
        val maxX = travelX()
        val maxY = travelY()
        val nx = anchor.toX(maxX, edgeMargin(maxX), prefs.dictate.floatingButtonSnapToEdge.get())
        val ny = anchor.toY(maxY)
        // The only way to observe where the bubble ended up without guessing at a screenshot. Debug builds
        // only; flog compiles out of a release.
        flogDebug {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.let { "${it.width()}x${it.height()}" }
            } else {
                "?"
            }
            "Bubble anchor ${anchor.edge} x=${anchor.xFraction} y=${anchor.yFraction} " +
                "-> ($nx, $ny) travel ${maxX}x$maxY in frame ${screenWidth()}x${screenHeight()} " +
                "of display $display"
        }
        if (nx == leftX() && ny == lp.y) return
        setLeftX(nx)
        lp.y = ny
        pushBubble()
        if (cancelAdded) positionCancel()
        if (undoAdded) positionUndo()
    }

    /**
     * Puts the bubble back on screen after the screen itself changed shape — rotation, a foldable opening,
     * a move into or out of split screen.
     *
     * Nothing used to react to this at all. The window keeps raw coordinates and carries
     * [WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS], so an x measured on a wider screen was simply
     * drawn past the edge of a narrower one and stayed there; only an unrelated event that happened to
     * re-clamp the position (an app switch, a skin resize) brought it back.
     *
     * Runs regardless of the "remember position per app" setting: that setting decides whether an anchor
     * is filed under a package, not whether the bubble is allowed to stay on screen.
     */
    fun onScreenGeometryChanged() {
        applyAnchor()
        // The metrics can still describe the old screen when the callback arrives. One trip through the
        // view's own queue lands after the window has been laid out again; applying twice costs nothing
        // because the second pass finds the position already correct and returns.
        rootView?.post { applyAnchor() }
    }

    /** Saves the bubble position for the leaving app and restores the position saved for the new app. */
    private fun onForegroundPackageChanged(pkg: String?) {
        if (!prefs.dictate.floatingButtonRememberPosition.get()) {
            currentPackage = pkg
            return
        }
        rememberCurrentPosition()
        currentPackage = pkg
        val saved = pkg?.let { prefs.dictate.floatingButtonPositions.get().toMap()[it] } ?: return
        anchor = saved
        anchorIsPlaced = true
        syncSkinToAnchor()
        applyAnchor()
    }

    /**
     * Files the current position under the foreground app, in preferences rather than in memory.
     *
     * The anchor is read back even when the per-app setting is off: it describes where the bubble *is*,
     * which the next geometry change needs whether or not anyone asked for it to be remembered. Only the
     * filing under a package name is what the setting governs.
     *
     * Nothing is written before the bubble has been placed for the first time. The service can be
     * connected long before the preference store has finished loading — at boot, for instance — and a
     * position for a bubble that has never been on screen would only overwrite the real ones with a
     * default.
     */
    private fun rememberCurrentPosition() {
        if (needsInitialPlacement) return
        captureAnchor()
        if (!prefs.dictate.floatingButtonRememberPosition.get()) return
        val pkg = currentPackage ?: return
        val stored = prefs.dictate.floatingButtonPositions.get().toMap()
        stored.remove(pkg) // re-insert at the back, so the list stays in least-recently-used order
        stored[pkg] = anchor
        // The write is suspending; the read above is not, and is done before the launch so the map cannot
        // be built from a store that a second drag has already moved on from.
        scope.launch { prefs.dictate.floatingButtonPositions.set(BubbleAnchors.of(stored)) }
    }

    private fun scheduleDim() {
        cancelDim()
        if (!prefs.dictate.floatingButtonAutoDim.get()) return
        dimJob = scope.launch {
            delay(AUTO_DIM_DELAY_MS)
            if (added && DictateController.state.value is DictateController.UiState.Idle) applyDim(true)
        }
    }

    private fun cancelDim() {
        dimJob?.cancel()
        dimJob = null
    }

    /** Fades + shrinks the bubble to a small dot (or restores it), pivoting toward the anchored edge. */
    private fun applyDim(dim: Boolean) {
        if (dimmed == dim) return
        dimmed = dim
        val view = skin?.root ?: return
        // Shrink toward the wall the bubble is on, so the dimmed dot stays put instead of appearing to
        // drift toward the middle. [sideIsRight] is read off the live position while dragging, so it is
        // never stale here. The pivot is the button's wall-side edge *inside the canvas*, which reaches
        // past the button on both sides (see [positionVisual]).
        val overhang = skin?.canvasOverhang ?: 0
        view.pivotX = if (sideIsRight) (view.width - overhang).toFloat() else overhang.toFloat()
        view.pivotY = view.height / 2f
        view.animate()
            .alpha(if (dim) 0.45f else 1f)
            .scaleX(if (dim) 0.5f else 1f)
            .scaleY(if (dim) 0.5f else 1f)
            .setDuration(200)
            .start()
        // Keep the undo button in step with the bubble: hide it while dimmed, restore it on wake.
        if (dim) hideUndo() else manageUndo(DictateController.state.value, added)
    }

    private fun vibrateTap() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun onTap() {
        if (prefs.dictate.floatingButtonHaptic.get()) vibrateTap()
        val current = DictateController.state.value
        // Recovery (#160): if the previous floating-button dictation failed but its recording was kept
        // (e.g. a flaky signal dropped the upload), a tap re-sends that audio instead of starting a new
        // recording — so the dictation isn't lost just because the bubble has no resend chip of its own.
        if (current is DictateController.UiState.Error &&
            current.action == DictateController.ErrorAction.RESEND
        ) {
            service.startMicForeground()
            weStartedDictation = true
            DictateController.sendRetainedAudio(context)
            return
        }
        startingDictation { DictateController.onMicClick(context, DictateController.OutputTarget.OVERLAY) }
    }

    /**
     * Runs anything that may start a bubble dictation, with the microphone promoted to a foreground
     * service first — Android 14 refuses the microphone to a background service, so the promotion has to
     * be in place *before* capture starts. Demoted again when the dictation finishes, see
     * [manageForeground].
     *
     * Asked as "would this start a recording", not as "is the state exactly idle": every resting state the
     * machine can hold — a nudge, an interrupted recording, a failure with nothing to resend — starts one
     * just the same, and getting that wrong means recording without the promotion (#339).
     *
     * Every entry point that records goes through here. The prompt menu's Live Prompt (#230) did not, and
     * so recorded a spoken instruction with no promotion at all — the tap was the only path that had ever
     * been given one.
     */
    private inline fun startingDictation(start: () -> Unit) {
        if (DictateController.canStartRecording()) {
            service.startMicForeground()
            weStartedDictation = true
        }
        start()
    }

    // --- State → visuals -------------------------------------------------------------------------

    private fun applyState(state: DictateController.UiState) {
        if (holding) return // a transient error/success flash is currently shown
        if (state == lastAppliedState) return // combine re-emits on focus/window churn; ignore no-op repeats
        skin?.applyState(state)
        syncShapeSize()
        lastAppliedState = state
    }

    /**
     * Reacts to a finished bubble dictation. On [DictateController.UiState.Error] it flashes the error
     * indicator on the button and shows the message as a toast (there is no inline text), then clears the
     * error — unless it offers an action — so the keyboard's own chip does not also fire. A clean finish
     * (a busy state returning to idle) flashes a brief success check; a plain cancel just resets the flag.
     */
    private fun reportTerminalState(state: DictateController.UiState) {
        when (state) {
            is DictateController.UiState.Error -> if (weStartedDictation) {
                weStartedDictation = false
                // When the recording was kept (#160), tell the user a tap re-sends it.
                val msg = if (state.action == DictateController.ErrorAction.RESEND) {
                    context.getString(R.string.dictate__floating_button_retry_hint, state.message)
                } else {
                    state.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                holdVisual(ERROR_HOLD_MS, FlashKind.ERROR)
                // Nothing to react to → don't leave the state machine parked in Error. The four-second
                // auto-clear lives in the Smartbar, which is not on screen when the floating button is
                // used with another keyboard, so the state would sit there until the next dictation — and
                // the next tap, seeing something other than Idle, would skip promoting the microphone
                // foreground service. Since #284 this also covers the notice after a *successful*
                // dictation whose rewording failed. The RESEND error stays: a tap re-sends it (#160).
                if (state.action == DictateController.ErrorAction.NONE) DictateController.clearError()
            }
            is DictateController.UiState.Idle -> {
                val finishedWork = prevState is DictateController.UiState.Transcribing ||
                    prevState is DictateController.UiState.Rewording
                if (weStartedDictation && finishedWork) holdVisual(SUCCESS_HOLD_MS, FlashKind.SUCCESS)
                weStartedDictation = false
            }
            else -> Unit // recording / transcribing / rewording still in flight
        }
    }

    /** Applies a transient flash for [durationMs], suppressing live-state updates, then restores them. */
    private fun holdVisual(durationMs: Long, kind: FlashKind) {
        stopTicker()
        skin?.showFlash(kind)
        syncShapeSize()
        lastAppliedState = null // the flash overwrote the visuals; force a re-apply when it ends
        holding = true
        holdJob?.cancel()
        holdJob = scope.launch {
            delay(durationMs)
            holding = false
            applyState(DictateController.state.value)
        }
    }

    // --- Recording level ticker ------------------------------------------------------------------

    private fun manageTicker(state: DictateController.UiState) {
        if (!holding && state is DictateController.UiState.Recording) startTicker() else stopTicker()
    }

    /**
     * Keep the screen awake while dictating from the floating button (issue #231): without physical touch,
     * Android's screen timeout would otherwise fire and tear down the recording. Honors the same
     * "keep screen awake" preference the keyboard/legacy recording views use, and only while actually
     * recording, so the bubble doesn't hold the screen on once dictation finishes.
     */
    private fun manageKeepScreenOn(state: DictateController.UiState) {
        rootView?.keepScreenOn =
            state is DictateController.UiState.Recording && prefs.dictate.keepScreenAwake.get()
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive) {
                val rec = recordingState
                val level = if (rec?.paused == true) {
                    0f
                } else {
                    DictateController.audioLevel.value
                }
                val elapsed = rec?.let { elapsedOf(it) } ?: 0L
                skin?.onRecordingTick(level, elapsed)
                delay(TICK_MS)
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun elapsedOf(rec: DictateController.UiState.Recording): Long =
        rec.accumulatedMs + if (rec.paused) 0L else SystemClock.elapsedRealtime() - rec.startedAtMs

    private fun manageForeground(state: DictateController.UiState) {
        when (state) {
            is DictateController.UiState.Recording,
            is DictateController.UiState.Transcribing,
            is DictateController.UiState.Rewording,
            -> Unit // keep the microphone foreground service running
            else -> service.stopMicForeground()
        }
    }

    // --- Shared drawing helpers ------------------------------------------------------------------

    private fun circle(colorRes: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color(colorRes))
    }

    /** The cancel/undo glyph, on whichever disc the current skin wants beside it. */
    private fun sideButtonIcon(resId: Int, pad: Int): ImageView = ImageView(context).apply {
        setImageResource(resId)
        setPadding(pad, pad, pad, pad)
        background = skin?.sideButtonBackground() ?: circle(R.color.dictate_overlay_cancel)
        imageTintList = ColorStateList.valueOf(skin?.sideButtonForeground ?: Color.WHITE)
        elevation = sdpf(6f)
    }

    /**
     * The window root a [sideButtonIcon] hangs in.
     *
     * The disc fills its own window exactly, so its round shadow had nowhere to fall: the host clipped
     * it to the disc's bounds, and the window's surface was that same square again, which left the ×
     * standing in a hard-edged rectangle of shadow instead of wearing a round one. Every design was
     * affected, because every skin puts its disc through here.
     *
     * The answer is the pill canvas's: stop clipping, and give the host a Z so the surface is padded
     * for what falls outside it — ViewRootImpl reads the root's Z when the window is added and sizes
     * the surface insets from it. The host keeps no background and therefore no outline, so it casts
     * nothing itself, and the window frame that [positionCancel] and [positionUndo] place is unchanged.
     */
    private fun sideButtonHost(icon: ImageView, size: Int): FrameLayout = FrameLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        elevation = sdpf(6f)
        addView(icon, FrameLayout.LayoutParams(size, size))
    }

    /**
     * Resolves a color resource — except the accent, which is overridden by the user's chosen button color
     * so every skin's idle/accent visuals follow the preference without each call site needing to change.
     */
    private fun color(colorRes: Int): Int =
        if (colorRes == R.color.dictate_overlay_accent) accentColor
        else ContextCompat.getColor(context, colorRes)

    /** Mixes [color] towards white by [amount] (0..1) — the light inside the aurora orb (#253). */
    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255),
        (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255),
    )

    /** Mixes [color] towards black by [amount] (0..1) — the shaded body behind that light. */
    private fun darken(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) * (1f - amount)).toInt().coerceIn(0, 255),
        (Color.green(color) * (1f - amount)).toInt().coerceIn(0, 255),
        (Color.blue(color) * (1f - amount)).toInt().coerceIn(0, 255),
    )

    /**
     * White by default, switching to black only for *very light* button colors, so the glyph stays legible
     * on a near-white accent without flipping on ordinary colors like the default light-blue. Threshold is
     * intentionally high (not the WCAG contrast crossover).
     */
    private fun contrastForeground(bg: Int): Int {
        val opaque = bg or 0xFF000000.toInt()
        return if (ColorUtils.calculateLuminance(opaque) > 0.7) Color.BLACK else Color.WHITE
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun dpf(value: Float): Float = value * context.resources.displayMetrics.density

    /** Like [dp]/[dpf] but scaled by the user's chosen button size; used for the skin dimensions. */
    private fun sdp(value: Int): Int = (value * sizeScale * context.resources.displayMetrics.density).toInt()

    private fun sdpf(value: Float): Float = value * sizeScale * context.resources.displayMetrics.density

    /** A transient, non-state flash shown briefly on the button. */
    private enum class FlashKind { ERROR, SUCCESS }

    /** Strategy that renders the bubble for a given design; owned by the controller. */
    private interface BubbleSkin {
        /**
         * The visuals. Laid out in the untouchable canvas window, which is never resized while it is on
         * screen, so a design that changes shape has to do so *inside* its root — see [positionVisual].
         */
        val root: View

        /**
         * The footprint of the shape as it settles in the current state, in px: what the touch window is
         * sized to, and what every geometry question (travel, clamping, the side buttons) is asked about.
         * Reported for the settled state as soon as the state is applied, even while an animation is
         * still on its way there, so the touch window and the cancel button are placed once rather than
         * dragged along frame by frame. The height must equal the root's.
         */
        val shapeWidth: Int
        val shapeHeight: Int

        /**
         * How far [root] reaches beyond the shape on the wall side, in px.
         *
         * A design that grows inwards needs room to grow into, and the canvas gives it that room on
         * *both* sides of the button — as much beyond the wall (off-screen, which FLAG_LAYOUT_NO_LIMITS
         * allows) as inboard — so the canvas is symmetric about the button and changing sides moves
         * nothing but the direction of growth. This is the width of that room; the fixed designs have none.
         */
        val canvasOverhang: Int get() = 0

        /**
         * Transparent margin between the window edge and the shape the user actually sees, in px.
         *
         * The glow designs reserve a ring of empty space for their halo, the pill none at all. Measuring
         * the wall gap and the cancel button from the window edge therefore parked them at visibly
         * different distances depending on the design; both subtract this instead.
         */
        val visualInset: Int get() = 0

        /**
         * Fill for the round cancel/undo buttons beside the bubble, and the colour of the glyph on it.
         *
         * The flat designs are happy with the shared neutral disc, but beside a cloud, an aurora or a dot
         * orb that same disc read as a control borrowed from another app. Returning null keeps the shared
         * one; a design that has a surface of its own cuts the button from it.
         */
        fun sideButtonBackground(): GradientDrawable? = null
        val sideButtonForeground: Int? get() = null

        /**
         * Which side of the screen the bubble is parked at, told to the skin whenever it changes.
         *
         * Only a design that *grows* has anything to do with it. A design that stays the same size never
         * moves its glyph, so the default is to ignore it; see the pill's override for why the side
         * matters there (#399).
         */
        fun onAnchorSideChanged(onRight: Boolean) {}

        fun applyState(state: DictateController.UiState)
        fun showFlash(kind: FlashKind)
        fun onRecordingTick(level: Float, elapsedMs: Long)
        fun destroy()
    }

    // --- Waveform --------------------------------------------------------------------------------

    /** A small rolling bar waveform fed normalized 0..1 mic levels. */
    private inner class WaveformView(context: Context, barCount: Int = WAVE_BARS) : View(context) {
        var barColor: Int = color(R.color.dictate_overlay_icon)
        private val levels = FloatArray(barCount)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun push(level: Float) {
            for (i in 0 until levels.size - 1) levels[i] = levels[i + 1]
            levels[levels.size - 1] = level.coerceIn(0f, 1f)
            invalidate()
        }

        fun reset() {
            levels.fill(0f)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val n = levels.size
            val gap = dpf(2f)
            val barW = (width - gap * (n - 1)) / n
            val radius = barW / 2f
            val cy = height / 2f
            val minH = dpf(4f)
            val maxH = height - dpf(2f)
            paint.color = barColor
            for (i in 0 until n) {
                val h = (minH + levels[i] * (maxH - minH)).coerceIn(minH, height.toFloat())
                val left = i * (barW + gap)
                canvas.drawRoundRect(left, cy - h / 2f, left + barW, cy + h / 2f, radius, radius, paint)
            }
        }
    }

    // --- Ring skin (design 1) --------------------------------------------------------------------

    private enum class RingMode { SOLID, SPIN, PULSE }

    private inner class RingSkin(context: Context) : BubbleSkin {
        // Filled core with the ring set a touch outside it: a small transparent gap so the ring reads as a
        // ring, but not so wide that it looks detached (a middle ground between the two earlier extremes).
        private val viewSize = sdp(64)
        private val coreSize = sdp(40)
        private val iconInset = sdp(10)
        private val ringStrokePx = sdpf(3f)
        private val ringRadiusPx = sdpf(25f)

        /** The ring spans 50dp of the 64dp window; the rest is room for its glow. */
        override val visualInset: Int = (viewSize - sdp(50)) / 2

        private val ring = RingView(context)
        private val core = View(context).apply {
            background = circle(R.color.dictate_overlay_accent)
            elevation = sdpf(6f)
        }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_mic)
            setPadding(iconInset, iconInset, iconInset, iconInset)
            elevation = sdpf(6f)
            imageTintList = ColorStateList.valueOf(contrastForeground(accentColor))
        }
        private val wave = WaveformView(context).apply {
            visibility = View.GONE
            elevation = sdpf(6f)
        }
        private var ringAnim: ValueAnimator? = null

        override val shapeWidth: Int get() = viewSize
        override val shapeHeight: Int get() = viewSize

        override val root: View = FrameLayout(context).apply {
            addView(ring, FrameLayout.LayoutParams(viewSize, viewSize))
            addView(core, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            addView(wave, FrameLayout.LayoutParams(sdp(24), sdp(16), Gravity.CENTER))
        }

        override fun applyState(state: DictateController.UiState) {
            when (state) {
                is DictateController.UiState.Recording -> {
                    setCore(R.color.dictate_overlay_recording)
                    showWave(true)
                    pulseRing(R.color.dictate_overlay_recording)
                }
                is DictateController.UiState.Transcribing -> {
                    setCore(R.color.dictate_overlay_accent)
                    showGlyph(R.drawable.ic_dictate_overlay_mic)
                    spinRing(R.color.dictate_overlay_accent)
                }
                is DictateController.UiState.Rewording -> {
                    setCore(R.color.dictate_overlay_accent)
                    showGlyph(R.drawable.ic_dictate_overlay_mic)
                    spinRing(R.color.dictate_overlay_rewording)
                }
                else -> {
                    setCore(R.color.dictate_overlay_accent)
                    showGlyph(R.drawable.ic_dictate_overlay_mic)
                    setSolidRing(R.color.dictate_overlay_accent)
                }
            }
        }

        override fun showFlash(kind: FlashKind) {
            when (kind) {
                FlashKind.ERROR -> {
                    setCore(R.color.dictate_overlay_recording)
                    showGlyph(R.drawable.ic_dictate_overlay_error)
                    setSolidRing(R.color.dictate_overlay_recording)
                }
                FlashKind.SUCCESS -> {
                    setCore(R.color.dictate_overlay_success)
                    showGlyph(R.drawable.ic_dictate_overlay_check)
                    setSolidRing(R.color.dictate_overlay_success)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            wave.push(level)
        }

        override fun destroy() {
            ringAnim?.cancel()
            ringAnim = null
        }

        private fun setCore(colorRes: Int) {
            core.background = circle(colorRes)
            icon.imageTintList = ColorStateList.valueOf(contrastForeground(color(colorRes)))
        }

        private fun showGlyph(resId: Int) {
            icon.setImageResource(resId)
            icon.alpha = 1f
            icon.visibility = View.VISIBLE
            wave.visibility = View.GONE
        }

        private fun showWave(show: Boolean) {
            wave.visibility = if (show) View.VISIBLE else View.GONE
            icon.visibility = if (show) View.GONE else View.VISIBLE
            if (show) wave.reset()
        }

        private fun setSolidRing(colorRes: Int) {
            ringAnim?.cancel()
            ringAnim = null
            ring.ringColor = color(colorRes)
            ring.mode = RingMode.SOLID
            ring.invalidate()
        }

        private fun spinRing(colorRes: Int) {
            ring.ringColor = color(colorRes)
            ring.mode = RingMode.SPIN
            ringAnim?.cancel()
            ringAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener {
                    ring.spinDeg = it.animatedValue as Float
                    ring.invalidate()
                }
                start()
            }
        }

        private fun pulseRing(colorRes: Int) {
            ring.ringColor = color(colorRes)
            ring.mode = RingMode.PULSE
            ringAnim?.cancel()
            ringAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 480
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener {
                    ring.pulse = it.animatedValue as Float
                    ring.invalidate()
                }
                start()
            }
        }

        private inner class RingView(context: Context) : View(context) {
            var ringColor: Int = color(R.color.dictate_overlay_accent)
            var mode: RingMode = RingMode.SOLID
            var spinDeg: Float = 0f
            var pulse: Float = 0f

            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
            private val oval = RectF()

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = ringRadiusPx
                paint.color = ringColor
                paint.alpha = 255
                paint.strokeWidth = ringStrokePx
                when (mode) {
                    RingMode.SOLID -> canvas.drawCircle(cx, cy, r, paint)
                    RingMode.SPIN -> {
                        oval.set(cx - r, cy - r, cx + r, cy + r)
                        canvas.drawArc(oval, spinDeg, 270f, false, paint)
                    }
                    RingMode.PULSE -> {
                        // A strong heartbeat: the ring grows and thickens and brightens with the pulse.
                        paint.strokeWidth = ringStrokePx + pulse * sdpf(4f)
                        paint.alpha = (170 + pulse * 85f).toInt().coerceIn(0, 255)
                        canvas.drawCircle(cx, cy, r + pulse * sdpf(3f), paint)
                    }
                }
            }
        }
    }

    // --- Pill skin (design 2) --------------------------------------------------------------------

    private inner class PillSkin(context: Context) : BubbleSkin {
        // Match the ring design's overall footprint, scaled by the chosen size.
        private val pillHeight = sdp(48)
        private val iconSize = sdp(24)
        private val pad = sdp(12)
        private val timerWidth = sdp(48)
        private val waveWidth = sdp(48)
        // The row reads icon, waveform, timer outwards from the button (#399): the waveform is what the
        // button is doing, so it belongs against it, and the elapsed time is the piece you glance at.
        private val waveMarginStart = sdp(8)
        private val waveMarginEnd = sdp(8)
        private val timerMarginEnd = sdp(2)
        // What the opened pill measures, from the same values the layout below is built with — keep the
        // two in step. Pinning to this is what stops the timer from ever resizing the overlay window.
        private val expandedContentWidth =
            waveMarginStart + waveWidth + waveMarginEnd + timerWidth + timerMarginEnd

        private val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillHeight / 2f
            setColor(color(R.color.dictate_overlay_accent))
        }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_mic)
            imageTintList = ColorStateList.valueOf(contrastForeground(accentColor))
        }
        private val timer = TextView(context).apply {
            setTextColor(color(R.color.dictate_overlay_icon))
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sdpf(14f))
            // Tabular figures and an exact width: the reserved minimum from #231 held the ordinary values
            // steady, but anything wider than it still grew the pill, so the width is fixed outright (#253).
            fontFeatureSettings = "tnum"
            isSingleLine = true
            gravity = Gravity.CENTER
            // The pill mirrors itself when it is parked at the right edge (see [onAnchorSideChanged]), and
            // that flips the paragraph direction of everything inside it. An elapsed time is not a
            // sentence — it reads the same way round wherever the bubble sits.
            textDirection = View.TEXT_DIRECTION_LTR
        }
        // Thinner bars: more bars across a similar width than the ring's waveform.
        private val wave = WaveformView(context, barCount = 13)

        // The timer + waveform live in this container, which grows from 0 width (circle) to its content
        // width (pill) when recording starts, so the circle→pill transition animates instead of jumping.
        private val expand = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            visibility = View.GONE
            addView(wave, LinearLayout.LayoutParams(waveWidth, sdp(20)).apply {
                marginStart = waveMarginStart
                marginEnd = waveMarginEnd
            })
            addView(timer, LinearLayout.LayoutParams(
                timerWidth,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = timerMarginEnd })
        }

        private var spinAnim: ValueAnimator? = null
        private var expandAnim: ValueAnimator? = null
        private var displayedSecond = -1L

        /** Which way the row currently runs, or null before the side has ever been told. */
        private var mirrored: Boolean? = null

        /** Whether the pill has been told to open: what [shapeWidth] answers for, even mid-animation. */
        private var settledExpanded = false

        override val shapeWidth: Int
            get() = if (settledExpanded) pillHeight + expandedContentWidth else pillHeight
        override val shapeHeight: Int = pillHeight
        override val canvasOverhang: Int = expandedContentWidth

        /** The pill itself: the button, and the waveform and timer that unfold from it. */
        private val row: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = bg
            elevation = sdpf(6f)
            minimumHeight = pillHeight
            minimumWidth = pillHeight
            setPadding(pad, 0, pad, 0)
            addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            addView(expand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        /**
         * The canvas the pill opens inside: the open pill's width and the same again on the far side,
         * with the button in the middle, so the window never has to change size or position while the
         * row grows away from the wall (see [positionVisual]). [onAnchorSideChanged] pins the row to one
         * end of it.
         */
        override val root: View = FrameLayout(context).apply {
            minimumWidth = pillHeight + 2 * expandedContentWidth
            minimumHeight = pillHeight
            clipChildren = false
            clipToPadding = false
            // The window's surface is padded for the *root's* shadow (ViewRootImpl sizes the surface
            // insets from the root's Z). The canvas has no outline and casts none; this only keeps the
            // room the row's shadow needs.
            elevation = sdpf(6f)
            addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, pillHeight))
        }

        /**
         * Keeps the button under the finger that tapped it, whichever side of the screen it is on (#399).
         *
         * The pill is the one design that changes size, and it opens to the side. Built one way round, the
         * button is the first thing in the row: fine at the left wall, where the row grows off to the
         * right past a button that does not move — and wrong at the right wall, where the pill can only
         * open inwards, so the window's left edge travels and takes the button with it. You tapped the mic
         * against the wall and the stop glyph was suddenly a pill's width inboard, with the cancel × the
         * nearest thing to where you were looking. The tap itself never moved; nothing on screen said so.
         *
         * Mirroring the whole row answers it: on the right the button is laid out last and stays at the
         * wall, with the waveform and then the elapsed time reaching inwards from it, and the cancel ×
         * beyond them at the far end. On the left it reads outwards from the wall the same way round.
         * The canvas is centred on the button and the row is pinned to its wall-side end, so the button
         * is the one thing in it that never moves; the window itself never changes shape at all — see
         * [positionVisual].
         *
         * Set as a layout direction rather than by reordering the children, so the margins inside the row
         * mirror with it, and pinned explicitly in both directions — an RTL *locale* would otherwise
         * decide this, and which wall the bubble is parked at has nothing to do with the language. The
         * row's place in the canvas is absolute LEFT/RIGHT for the same reason.
         */
        @SuppressLint("RtlHardcoded")
        override fun onAnchorSideChanged(onRight: Boolean) {
            if (mirrored == onRight) return
            mirrored = onRight
            row.layoutDirection = if (onRight) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            // The button sits in the middle of the canvas whichever way round: the row is pinned to the
            // wall-side end with the far side's room left as a margin, and grows inwards from there.
            row.layoutParams = (row.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = (if (onRight) Gravity.RIGHT else Gravity.LEFT) or Gravity.CENTER_VERTICAL
                leftMargin = if (onRight) 0 else expandedContentWidth
                rightMargin = if (onRight) expandedContentWidth else 0
            }
        }

        override fun applyState(state: DictateController.UiState) {
            stopSpin()
            when (state) {
                is DictateController.UiState.Recording -> {
                    setColor(R.color.dictate_overlay_recording)
                    icon.alpha = 1f
                    icon.rotation = 0f
                    icon.setImageResource(R.drawable.ic_dictate_overlay_stop)
                    wave.reset()
                    timer.text = formatElapsed(0)
                    displayedSecond = 0L
                    setExpanded(true)
                }
                is DictateController.UiState.Transcribing -> busySpinner(R.color.dictate_overlay_accent)
                is DictateController.UiState.Rewording -> busySpinner(R.color.dictate_overlay_rewording)
                else -> {
                    setColor(R.color.dictate_overlay_accent)
                    icon.alpha = 1f
                    icon.rotation = 0f
                    icon.setImageResource(R.drawable.ic_dictate_overlay_mic)
                    setExpanded(false)
                }
            }
        }

        override fun showFlash(kind: FlashKind) {
            stopSpin()
            icon.alpha = 1f
            icon.rotation = 0f
            setExpanded(false)
            when (kind) {
                FlashKind.ERROR -> {
                    setColor(R.color.dictate_overlay_recording)
                    icon.setImageResource(R.drawable.ic_dictate_overlay_error)
                }
                FlashKind.SUCCESS -> {
                    setColor(R.color.dictate_overlay_success)
                    icon.setImageResource(R.drawable.ic_dictate_overlay_check)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            wave.push(level)
            // The tick arrives with the audio level, twenty times a second; the label changes once.
            val second = elapsedMs / 1000L
            if (second != displayedSecond) {
                displayedSecond = second
                timer.text = formatElapsed(elapsedMs)
            }
        }

        override fun destroy() {
            stopSpin()
            expandAnim?.cancel()
        }

        private fun busySpinner(colorRes: Int) {
            setColor(colorRes)
            setExpanded(false)
            icon.alpha = 1f
            icon.setImageResource(R.drawable.ic_dictate_overlay_spinner)
            spinAnim?.cancel()
            spinAnim = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener { icon.rotation = it.animatedValue as Float }
                start()
            }
        }

        /** Animates the expand container open (pill) or closed (circle). */
        private fun setExpanded(expanded: Boolean) {
            settledExpanded = expanded
            expandAnim?.cancel()
            if (expanded) {
                expand.visibility = View.VISIBLE
                val target = expandedContentWidth
                expandAnim = ValueAnimator.ofInt(expand.width, target).apply {
                    duration = 240
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val w = it.animatedValue as Int
                        setExpandWidth(w)
                        expand.alpha = if (target > 0) (w.toFloat() / target).coerceIn(0f, 1f) else 1f
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // Stays at the exact width. Handing it back to WRAP_CONTENT was the opening the
                            // timer could still grow through, once its text outran the reserved minimum.
                            setExpandWidth(target)
                            expand.alpha = 1f
                        }
                    })
                    start()
                }
            } else {
                val start = if (expand.width > 0) expand.width else 0
                if (start == 0 || expand.visibility != View.VISIBLE) {
                    setExpandWidth(0)
                    expand.visibility = View.GONE
                    return
                }
                expandAnim = ValueAnimator.ofInt(start, 0).apply {
                    duration = 200
                    interpolator = DecelerateInterpolator()
                    addUpdateListener {
                        val w = it.animatedValue as Int
                        setExpandWidth(w)
                        expand.alpha = (w.toFloat() / start).coerceIn(0f, 1f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            expand.visibility = View.GONE
                        }
                    })
                    start()
                }
            }
        }

        private fun setExpandWidth(w: Int) {
            val lp = expand.layoutParams
            lp.width = w
            expand.layoutParams = lp
        }

        private fun stopSpin() {
            spinAnim?.cancel()
            spinAnim = null
            icon.rotation = 0f
        }

        private fun setColor(colorRes: Int) {
            bg.setColor(color(colorRes))
            val fg = contrastForeground(color(colorRes))
            icon.imageTintList = ColorStateList.valueOf(fg)
            timer.setTextColor(fg)
        }

        private fun formatElapsed(ms: Long): String {
            val totalSec = (ms / 1000).toInt()
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }

    // --- Orb skin (design 3) ---------------------------------------------------------------------

    private inner class OrbSkin(context: Context) : BubbleSkin {
        private val viewSize = sdp(64)
        // The pill's idle circle is the reference every design matches, so they are all the same object
        // at rest and only their decoration differs.
        private val coreSize = sdp(48)
        private val iconInset = sdp(11)
        private val coreRadiusPx = coreSize / 2f
        private val minGlowPx = sdpf(2f)
        private val maxGlowPx = sdpf(8f)

        override val visualInset: Int = (viewSize - coreSize) / 2

        private val glow = GlowView(context)
        private val core = View(context).apply {
            background = circle(R.color.dictate_overlay_accent)
            elevation = sdpf(6f)
        }
        private val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_dictate_overlay_mic)
            setPadding(iconInset, iconInset, iconInset, iconInset)
            elevation = sdpf(6f)
            imageTintList = ColorStateList.valueOf(contrastForeground(accentColor))
        }
        private var breatheAnim: ValueAnimator? = null
        private var smoothed = 0f

        override val shapeWidth: Int get() = viewSize
        override val shapeHeight: Int get() = viewSize

        override val root: View = FrameLayout(context).apply {
            addView(glow, FrameLayout.LayoutParams(viewSize, viewSize))
            addView(core, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
        }

        override fun applyState(state: DictateController.UiState) {
            stopBreathe()
            when (state) {
                is DictateController.UiState.Recording -> {
                    setCore(R.color.dictate_overlay_recording)
                    setGlyph(R.drawable.ic_dictate_overlay_stop)
                    glow.glowColor = color(R.color.dictate_overlay_recording)
                    smoothed = 0f
                    setGlow(0f) // the ticker drives it from the live amplitude
                }
                is DictateController.UiState.Transcribing -> {
                    setCore(R.color.dictate_overlay_accent)
                    setGlyph(R.drawable.ic_dictate_overlay_mic)
                    startBreathe(R.color.dictate_overlay_accent)
                }
                is DictateController.UiState.Rewording -> {
                    setCore(R.color.dictate_overlay_accent)
                    setGlyph(R.drawable.ic_dictate_overlay_mic)
                    startBreathe(R.color.dictate_overlay_rewording)
                }
                else -> {
                    setCore(R.color.dictate_overlay_accent)
                    setGlyph(R.drawable.ic_dictate_overlay_mic)
                    setGlow(0f)
                }
            }
        }

        override fun showFlash(kind: FlashKind) {
            stopBreathe()
            setGlow(0f)
            when (kind) {
                FlashKind.ERROR -> {
                    setCore(R.color.dictate_overlay_recording)
                    setGlyph(R.drawable.ic_dictate_overlay_error)
                }
                FlashKind.SUCCESS -> {
                    setCore(R.color.dictate_overlay_success)
                    setGlyph(R.drawable.ic_dictate_overlay_check)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            smoothed += (level - smoothed) * 0.35f
            setGlow(smoothed)
        }

        override fun destroy() {
            stopBreathe()
        }

        private fun setCore(colorRes: Int) {
            core.background = circle(colorRes)
            icon.imageTintList = ColorStateList.valueOf(contrastForeground(color(colorRes)))
        }

        private fun setGlyph(resId: Int) {
            icon.setImageResource(resId)
            icon.alpha = 1f
        }

        /** Drives the glow radius/alpha and a subtle orb scale from a 0..1 level. */
        private fun setGlow(level: Float) {
            val l = level.coerceIn(0f, 1f)
            glow.level = l
            glow.invalidate()
            val s = 1f + l * 0.08f
            core.scaleX = s
            core.scaleY = s
            icon.scaleX = s
            icon.scaleY = s
        }

        private fun startBreathe(colorRes: Int) {
            glow.glowColor = color(colorRes)
            breatheAnim?.cancel()
            breatheAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { setGlow(0.2f + 0.5f * (it.animatedValue as Float)) }
                start()
            }
        }

        private fun stopBreathe() {
            breatheAnim?.cancel()
            breatheAnim = null
        }

        private inner class GlowView(context: Context) : View(context) {
            var glowColor: Int = color(R.color.dictate_overlay_accent)
            var level: Float = 0f
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            override fun onDraw(canvas: Canvas) {
                if (level <= 0.01f || width == 0) return
                val cx = width / 2f
                val cy = height / 2f
                val glowR = coreRadiusPx + minGlowPx + level * maxGlowPx
                if (glowR <= coreRadiusPx) return
                val inner = (coreRadiusPx / glowR).coerceIn(0f, 0.95f)
                val a = (50 + level * 150f).toInt().coerceIn(0, 255)
                val rgb = glowColor and 0x00FFFFFF
                val cIn = rgb or (a shl 24)
                paint.shader = RadialGradient(
                    cx, cy, glowR,
                    intArrayOf(cIn, cIn, rgb),
                    floatArrayOf(0f, inner, 1f),
                    Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(cx, cy, glowR, paint)
            }
        }
    }

    // --- Aurora skin (design 5) ------------------------------------------------------------------

    /**
     * A thinking orb (#253): coloured light moving inside a sphere, in the visual language AI interfaces
     * have converged on. Every state is the same orb at a different temperament rather than a different
     * widget — it drifts when idle, swells with the voice while recording, and churns while the transcript
     * is being worked on, so the button never has to swap in a spinner to say it is busy.
     *
     * Drawn rather than composed from views: three blurred blobs orbiting inside a clipped circle is a
     * handful of drawing calls, where the same look in views would need layers, masks and a blur pass.
     * The blur wants a software layer, which is why the view asks for one.
     */
    private inner class AuroraSkin(context: Context) : BubbleSkin {
        // Same footprint as the ring, orb and cloud designs: a 64dp window with a 44dp body inside it.
        // Drawn edge to edge it looked markedly bigger than the rest, since those keep the outer ring for
        // glow rather than for the shape itself.
        private val viewSize = sdp(64)
        private val coreSize = sdp(48)
        private val iconInset = (viewSize - sdp(22)) / 2

        override val visualInset: Int = (viewSize - coreSize) / 2

        private val orb = DictateAuroraOrbView(context).apply { bodyRadius = coreSize / 2f }
        // No mic or stop glyph: the orb's temperament already says which state it is in, and a badge on top
        // only fought the light inside it. Only the terminal marks below still get one.
        private val icon = ImageView(context).apply {
            setPadding(iconInset, iconInset, iconInset, iconInset)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0f
        }

        override val shapeWidth: Int get() = viewSize
        override val shapeHeight: Int get() = viewSize

        override val root: View = FrameLayout(context).apply {
            addView(orb, FrameLayout.LayoutParams(viewSize, viewSize))
            addView(icon, FrameLayout.LayoutParams(viewSize, viewSize))
        }

        /** Lit from up-left and falling off into the shaded body: the orb's own shading, in miniature. */
        override fun sideButtonBackground(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(lighten(accentColor, 0.4f), darken(accentColor, 0.55f))
            setGradientCenter(0.34f, 0.28f)
            gradientRadius = cancelSize * 0.9f
        }

        override fun applyState(state: DictateController.UiState) {
            icon.alpha = 0f
            when (state) {
                is DictateController.UiState.Recording ->
                    orb.setMood(DictateAuroraOrbView.Mood.RECORDING, color(R.color.dictate_overlay_recording))
                is DictateController.UiState.Transcribing ->
                    thinking(R.color.dictate_overlay_accent)
                is DictateController.UiState.Rewording ->
                    thinking(R.color.dictate_overlay_rewording)
                else -> orb.setMood(DictateAuroraOrbView.Mood.IDLE, accentColor)
            }
        }

        private fun thinking(colorRes: Int) {
            orb.setMood(DictateAuroraOrbView.Mood.THINKING, color(colorRes))
        }

        override fun showFlash(kind: FlashKind) {
            icon.alpha = 1f
            when (kind) {
                FlashKind.ERROR -> {
                    icon.setImageResource(R.drawable.ic_dictate_overlay_error)
                    orb.setMood(DictateAuroraOrbView.Mood.IDLE, color(R.color.dictate_overlay_recording))
                }
                FlashKind.SUCCESS -> {
                    icon.setImageResource(R.drawable.ic_dictate_overlay_check)
                    orb.setMood(DictateAuroraOrbView.Mood.IDLE, color(R.color.dictate_overlay_success))
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) = orb.pushLevel(level)

        override fun destroy() = orb.stop()
    }

    // --- Cloud skin (design 4) -------------------------------------------------------------------

    private inner class CloudSkin(context: Context) : BubbleSkin {
        private val viewSize = sdp(64)
        private val coreSize = sdp(48)
        private val idleInset = sdp(13)

        override val visualInset: Int = (viewSize - coreSize) / 2

        private val cloud = AudioReactiveCloudOrbView(context)
        private val icon = ImageView(context).apply {
            elevation = sdpf(6f)
        }

        override val shapeWidth: Int get() = viewSize
        override val shapeHeight: Int get() = viewSize

        override val root: View = FrameLayout(context).apply {
            // The cloud spans the whole button so it has room to grow while recording; a small glyph
            // overlays it as the idle/terminal affordance (there is no glyph while recording).
            addView(cloud, FrameLayout.LayoutParams(viewSize, viewSize, Gravity.CENTER))
            addView(icon, FrameLayout.LayoutParams(coreSize, coreSize, Gravity.CENTER))
            minimumWidth = viewSize
            minimumHeight = viewSize
        }

        /** Cut from the same sky as the cloud, down to the dark glyph it puts on that surface. */
        override fun sideButtonBackground(): GradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            AudioReactiveCloudOrbView.SURFACE_GRADIENT,
        ).apply { shape = GradientDrawable.OVAL }

        override val sideButtonForeground: Int = CLOUD_GLYPH_COLOR

        override fun applyState(state: DictateController.UiState) {
            when (state) {
                is DictateController.UiState.Recording -> {
                    cloud.setPaused(state.paused)
                    cloud.setLevel(0f) // the shared level ticker takes over immediately after this state update
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.LISTENING)
                    icon.visibility = View.INVISIBLE // the growing, turbulent cloud alone signals recording
                }
                is DictateController.UiState.Transcribing,
                is DictateController.UiState.Rewording -> {
                    cloud.setPaused(false)
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.THINKING)
                    icon.visibility = View.INVISIBLE // the cloud draws its own activity spinner
                }
                else -> showIdle()
            }
        }

        override fun showFlash(kind: FlashKind) {
            // Keep the cloud alive and tint the whole field, so the terminal feedback stays part of the
            // same design instead of swapping in a detached colored circle.
            cloud.setPaused(false)
            when (kind) {
                FlashKind.ERROR -> {
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.ERROR)
                    showGlyph(R.drawable.ic_dictate_overlay_error, idleInset, Color.WHITE, 0.95f)
                }
                FlashKind.SUCCESS -> {
                    cloud.setMode(AudioReactiveCloudOrbView.Mode.SUCCESS)
                    showGlyph(R.drawable.ic_dictate_overlay_check, idleInset, Color.WHITE, 0.95f)
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) {
            cloud.setLevel(level)
        }

        override fun destroy() {
            cloud.stop()
        }

        // Idle: the cloud itself is the button, with a mic hint so it reads as "tap to dictate".
        private fun showIdle() {
            cloud.setPaused(false)
            cloud.setMode(AudioReactiveCloudOrbView.Mode.IDLE)
            showGlyph(R.drawable.ic_dictate_overlay_mic, idleInset, CLOUD_GLYPH_COLOR, 0.9f)
        }

        private fun showGlyph(resId: Int, inset: Int, tint: Int, alpha: Float) {
            icon.visibility = View.VISIBLE
            icon.setImageResource(resId)
            icon.setPadding(inset, inset, inset, inset)
            icon.imageTintList = ColorStateList.valueOf(tint)
            icon.alpha = alpha
        }
    }

    // --- Lattice skin (design 6) -----------------------------------------------------------------

    /**
     * A dot orb (#253): a constellation wiring itself while it waits and the same one racing, red and
     * riding the voice, while recording — then a wave rolling through it while the transcript comes back,
     * and a sphere twisting itself apart and back together while that text is reworded.
     *
     * It carries no mic or stop glyph at all: every state already has its own unmistakable motion, and a
     * badge on top only fought the dots for the middle of the button. Only the terminal error/success marks
     * remain, because those say something the motion does not.
     */
    private inner class LatticeSkin(context: Context) : BubbleSkin {
        private val viewSize = sdp(64)
        private val coreSize = sdp(48)
        private val iconInset = (viewSize - sdp(22)) / 2

        override val visualInset: Int = (viewSize - coreSize) / 2

        private val sphere = DictateLatticeSphereView(context, sizeScale = sizeScale)
            .apply { bodyDiameter = coreSize.toFloat() }
        private val icon = ImageView(context).apply {
            setPadding(iconInset, iconInset, iconInset, iconInset)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            alpha = 0f
        }

        override val shapeWidth: Int get() = viewSize
        override val shapeHeight: Int get() = viewSize

        override val root: View = FrameLayout(context).apply {
            addView(sphere, FrameLayout.LayoutParams(viewSize, viewSize))
            addView(icon, FrameLayout.LayoutParams(viewSize, viewSize))
        }

        /**
         * The same tinted dark substrate the dots sit on — opaque here, unlike the orb itself: the sphere
         * can afford to be translucent because it is covered in dots, while a lone glyph on a see-through
         * disc would lose its contrast over a light app.
         */
        override fun sideButtonBackground(): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.blendARGB(accentColor, Color.BLACK, 0.58f))
        }

        override fun applyState(state: DictateController.UiState) {
            icon.alpha = 0f
            when (state) {
                is DictateController.UiState.Recording -> {
                    sphere.setPaused(state.paused)
                    // The same constellation as at rest, turned red and run at the tempo the library
                    // actually ships it at — six times the idle one. Because the mode does not change, the
                    // motion carries straight on from wherever it was instead of restarting.
                    sphere.setMode(
                        DictateLatticeSphereView.Mode.WEB,
                        color(R.color.dictate_overlay_recording),
                        speedScale = RECORDING_SPEED_UP,
                    )
                }
                is DictateController.UiState.Transcribing -> {
                    sphere.setPaused(false)
                    sphere.setMode(DictateLatticeSphereView.Mode.WAVE, color(R.color.dictate_overlay_accent))
                }
                is DictateController.UiState.Rewording -> {
                    sphere.setPaused(false)
                    sphere.setMode(DictateLatticeSphereView.Mode.RUBIK, color(R.color.dictate_overlay_rewording))
                }
                else -> {
                    sphere.setPaused(false)
                    sphere.setMode(DictateLatticeSphereView.Mode.WEB, accentColor)
                }
            }
        }

        /**
         * The terminal marks stay on the *idle* motion, only recoloured: a flash is the button on its way
         * back to rest, so putting a different mode on screen for a second read as yet another state.
         */
        override fun showFlash(kind: FlashKind) {
            sphere.setPaused(false)
            when (kind) {
                FlashKind.ERROR -> {
                    showGlyph(R.drawable.ic_dictate_overlay_error)
                    sphere.setMode(DictateLatticeSphereView.Mode.WEB, color(R.color.dictate_overlay_recording))
                }
                FlashKind.SUCCESS -> {
                    showGlyph(R.drawable.ic_dictate_overlay_check)
                    sphere.setMode(DictateLatticeSphereView.Mode.WEB, color(R.color.dictate_overlay_success))
                }
            }
        }

        override fun onRecordingTick(level: Float, elapsedMs: Long) = sphere.pushLevel(level)

        override fun destroy() = sphere.stop()

        private fun showGlyph(resId: Int) {
            icon.alpha = 1f
            icon.setImageResource(resId)
        }
    }

    private companion object {
        private const val ERROR_HOLD_MS = 1800L
        private const val SUCCESS_HOLD_MS = 1700L
        private const val TICK_MS = 50L
        private const val AUTO_DIM_DELAY_MS = 3500L

        /**
         * How far the bubble's visible shape parks from the screen edge, in dp. The wider of the two gaps
         * the code used to produce by accident: pressed right up against the edge it read as something the
         * system had shoved aside rather than something placed there.
         */
        private const val EDGE_MARGIN_DP = 16

        /** How much of the screen the hold menu may take before its rows wrap / it scrolls (#408). */
        private const val MENU_MAX_WIDTH = 0.8f
        private const val MENU_MAX_HEIGHT = 0.6f

        /** The card's own padding, subtracted from the caps so the shares above measure the card. */
        private const val MENU_PADDING_DP = 8

        /** How long the cancel button waits before its window is added; see [manageCancel]. */
        private const val CANCEL_SHOW_DELAY_MS = 40L

        /** Aurora orb (#253): three blobs, started apart and orbiting at rates that never quite repeat. */
        private const val FULL_TURN = 6.2831855f
        private val BLOB_ANGLES = floatArrayOf(0f, 2.1f, 4.2f)
        private val BLOB_RATES = floatArrayOf(1f, -0.62f, 0.41f)
        private val BLOB_TINTS = floatArrayOf(0.45f, 0.18f, 0.68f)
        private const val WAVE_BARS = 7
        private const val CLOUD_GLYPH_COLOR = 0xFF343B8F.toInt()

        /** Lattice (#253): recording runs its idle constellation at the tempo the library ships it at. */
        private const val RECORDING_SPEED_UP = 6f
    }
}
