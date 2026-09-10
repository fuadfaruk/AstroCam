package com.cusapps.astrocam.controls

/**
 * Thread-safe holder for [ManualControlsState].
 *
 * Two notification channels mirror the gesture lifecycle, which is what keeps the
 * Camera2 pipeline healthy:
 *  - [onStateChanged] fires on every scrub and is cheap: it refreshes labels and any
 *    sibling widget, and never touches the camera.
 *  - [onCommit] fires once per completed gesture and is the only thing allowed to call
 *    `setRepeatingRequest`. A momentum fling can emit hundreds of detents; committing
 *    per detent would restart the capture pipeline continuously and stall the preview.
 *
 * [state] is `@Volatile` because the camera background thread reads it through
 * `applyCurrentSettingsToPreview()` while the UI thread mutates it.
 */
class ManualControlsController(initial: ManualControlsState = ManualControlsState()) {

    @Volatile
    var state: ManualControlsState = initial
        private set

    /** Cheap, per-detent. Drives labels and mirror widgets. */
    var onStateChanged: ((ManualControlsState) -> Unit)? = null

    /** Expensive, per-gesture. Drives Camera2 exclusively. */
    var onCommit: ((ManualControlsState) -> Unit)? = null

    fun scrub(transform: (ManualControlsState) -> ManualControlsState) {
        state = transform(state)
        onStateChanged?.invoke(state)
    }

    fun commit() {
        onCommit?.invoke(state)
    }

    fun scrubAndCommit(transform: (ManualControlsState) -> ManualControlsState) {
        scrub(transform)
        commit()
    }
}
