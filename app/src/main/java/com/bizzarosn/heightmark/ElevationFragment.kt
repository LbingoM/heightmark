package com.bizzarosn.heightmark

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/**
 * The app's one screen. It renders whatever [ElevationTracker] publishes and
 * owns only what genuinely needs a Fragment: the views, the two toggles and
 * their persistence, the permission handler's launcher, and the dialogs.
 *
 * Nothing here decides *what* the reading is worth — [ElevationUiState] arrives
 * already derived, so the hero number, the settling line and the
 * location-services prompt cannot drift out of step with each other.
 */
@AndroidEntryPoint
class ElevationFragment : Fragment() {

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    private val tracker: ElevationTracker by viewModels()

    // Null between onDestroyView and the next onViewCreated. They cannot stay
    // lateinit: a Fragment outlives its view, and one child View reference keeps
    // the whole destroyed hierarchy — and the Activity behind it — reachable.
    private var elevationLabel: TextView? = null
    private var elevationTextView: TextView? = null
    private var stabilityLine: StabilityLineView? = null
    private var blockedActionButton: MaterialButton? = null
    private var detailsToggle: TextView? = null
    private var detailsPanel: ViewGroup? = null

    /** The text appearance currently on the hero number; see [applyTextAppearance]. */
    private var appliedTextAppearanceAttr: Int? = null

    /** The last whole number rendered, so a change can be given the lift. */
    private var lastHeroValue: Int? = null

    /** Set by [renderHero], consumed by [renderStabilityLine]'s animator. */
    private var heroLiftPending = false

    /** Offline region boundaries; null until the asset has been read, or if it fails. */
    private var adminRegions: AdminRegions? = null

    private var useMetricUnit = true
    private var showDetails = false
    private var hasRequestedLocationPermission = false
    private var locationOffDialog: AlertDialog? = null

    private lateinit var permissionHandler: LocationPermissionHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionHandler = LocationPermissionHandler(
            fragment = this,
            onPermissionStateChanged = { state -> tracker.onPermissionState(state) },
            hasShownUpgradeDialog = { tracker.upgradeDialogShown },
            onUpgradeDialogShown = { tracker.markUpgradeDialogShown() },
            hasRequestedPermissionBefore = { hasRequestedLocationPermission },
            onPermissionRequested = ::markPermissionRequested
        )
        permissionHandler.initialize()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_elevation, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // A fresh view starts at the layout's default appearance; the cache
        // belongs to the view that is gone
        appliedTextAppearanceAttr = null
        lastHeroValue = null
        heroLiftPending = false

        elevationLabel = view.findViewById(R.id.elevation_label)
        elevationTextView = view.findViewById(R.id.elevation_text_view)
        stabilityLine = view.findViewById(R.id.stability_line)
        warnIfNoBarometer(view)
        blockedActionButton = view.findViewById(R.id.blocked_action_button)
        detailsToggle = view.findViewById(R.id.details_toggle)
        detailsPanel = view.findViewById(R.id.details_panel)
        val unitToggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.unit_toggle_group)

        // Nothing else consumes the navigation-bar or display-cutout insets, so
        // the scrim column absorbs them itself; otherwise the details toggle
        // sits under the gesture bar, and in landscape the camera cutout covers
        // the side of the hero number.
        val contentContainer = view.findViewById<View>(R.id.content_container)
        val initialPaddingLeft = contentContainer.paddingLeft
        val initialPaddingRight = contentContainer.paddingRight
        val initialPaddingBottom = contentContainer.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(contentContainer) { v, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = initialPaddingLeft + safe.left,
                right = initialPaddingRight + safe.right,
                bottom = initialPaddingBottom + safe.bottom
            )
            insets
        }

        viewLifecycleOwner.lifecycleScope.launch {
            useMetricUnit = preferencesRepository.useMetricUnit.first()
            unitToggleGroup.check(if (useMetricUnit) R.id.button_meters else R.id.button_feet)
            applyDetailsVisibility(preferencesRepository.showDetails.first())
            hasRequestedLocationPermission =
                preferencesRepository.hasRequestedLocationPermission.first()
            permissionHandler.checkPermission()
        }

        // The region table is 1.4 MB of JSON: read and index it off the main
        // thread, then repaint once it is available
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val regions = withContext(Dispatchers.IO) { AdminRegions.load(context) }
            if (!isAdded) return@launch
            adminRegions = regions
            render(tracker.uiState.value)
        }

        // Both toggles repaint first and persist after: a DataStore write goes
        // to disk, and neither the new units nor the panel should wait on it
        unitToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            useMetricUnit = checkedId == R.id.button_meters
            // Only the unit-bearing parts of the current state change
            val state = tracker.uiState.value
            renderHero(state.hero)
            renderDetails(state.details)
            // Persisting on the fragment's scope, not the view's: a rotation
            // right after a tap must not cancel the write
            lifecycleScope.launch { preferencesRepository.setUseMetricUnit(useMetricUnit) }
        }

        detailsToggle?.setOnClickListener {
            val show = !showDetails
            applyDetailsVisibility(show)
            lifecycleScope.launch { preferencesRepository.setShowDetails(show) }
        }

        // Reads the action at click time so the listener is installed once;
        // with the details panel open, state republishes every second
        blockedActionButton?.setOnClickListener {
            tracker.uiState.value.blockedAction?.let(::performBlockedAction)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                tracker.uiState.collect { render(it) }
            }
        }
    }

    /**
     * Elevation comes from GNSS alone: the barometer only sharpens short height
     * changes on top of it. A device without one still works, so the hero card
     * says why the reading reacts slowly rather than failing or showing nothing.
     */
    private fun warnIfNoBarometer(view: View) {
        val sensorManager =
            requireContext().getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        view.findViewById<TextView>(R.id.barometer_warning).isVisible =
            sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE) == null
    }

    override fun onResume() {
        super.onResume()
        tracker.onForeground()
    }

    override fun onPause() {
        super.onPause()
        tracker.onBackground()
        dismissLocationOffDialog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // The views go with the view; the Fragment stays alive and must not hold
        // a single one of them, or the destroyed tree survives until onDestroy.
        elevationLabel = null
        elevationTextView = null
        stabilityLine = null
        blockedActionButton = null
        detailsToggle = null
        detailsPanel = null
        appliedTextAppearanceAttr = null
        dismissLocationOffDialog()
    }

    /**
     * Records that the system permission dialog has been triggered at least
     * once, so a later cold start never mistakes a returning, already-decided
     * user for a true first launch. Persisting on the fragment's scope, not
     * the view's, for the same reason the toggles do: a rotation right after
     * the request must not cancel the write.
     */
    private fun markPermissionRequested() {
        if (hasRequestedLocationPermission) return
        hasRequestedLocationPermission = true
        lifecycleScope.launch { preferencesRepository.setHasRequestedLocationPermission(true) }
    }

    private fun applyDetailsVisibility(show: Boolean) {
        showDetails = show
        val panel = detailsPanel ?: return
        val toggle = detailsToggle ?: return
        // The panel grows from a few rows to a dozen; animating the height keeps
        // the hero from jumping between two frames
        (panel.parent as? ViewGroup)?.let { container ->
            TransitionManager.beginDelayedTransition(
                container,
                AutoTransition().setDuration(DETAILS_ANIM_MS)
            )
        }
        panel.isVisible = show
        toggle.text = getString(if (show) R.string.details_hide else R.string.details_show)
        toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0,
            0,
            if (show) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down,
            0
        )
        toggle.compoundDrawablePadding = (CHEVRON_PADDING_DP * resources.displayMetrics.density).toInt()
        toggle.stateDescription = getString(
            if (show) R.string.details_state_expanded else R.string.details_state_collapsed
        )
        tracker.setDetailsVisible(show)
    }

    private fun render(state: ElevationUiState) {
        renderHero(state.hero)
        renderStabilityLine(state.readingState)
        renderDetails(state.details)
        renderLocationOffPrompt(state.promptLocationSettings)
        renderBlockedAction(state.blockedAction)
    }

    /**
     * The only writer of the hero TextView and the label naming it. Both modes
     * set the same properties so switching modes can never leave a stale one
     * behind.
     */
    private fun renderHero(hero: ElevationUiState.Hero) {
        val label = elevationLabel ?: return
        val text = elevationTextView ?: return
        when (hero) {
            is ElevationUiState.Hero.Status -> {
                label.setText(R.string.current_elevation)
                // Status messages use headline type; the hero display size is
                // reserved for the value
                applyTextAppearance(com.google.android.material.R.attr.textAppearanceHeadlineSmall)
                // Prose, not a reading: at headline size a two-line Chinese
                // sentence took over the whole hero area
                text.textSize = STATUS_TEXT_SP
                // Status sentences run long ("Still searching…") and must not clip
                text.maxLines = 3
                // Errors and search-status changes are the moments a screen-reader
                // user must hear unprompted; the ticking elevation number is not
                text.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                text.contentDescription = null
                text.text = getString(hero.messageRes)
            }
            is ElevationUiState.Hero.Value -> {
                // A height the device could not convert is not sea-level
                // elevation, and the label over the number says so rather than
                // letting it pass for one
                val ellipsoid = hero.datum == ElevationDatum.ELLIPSOID
                label.setText(
                    if (ellipsoid) R.string.ellipsoid_elevation else R.string.current_elevation
                )
                applyTextAppearance(
                    com.google.android.material.R.attr.textAppearanceDisplayLargeEmphasized
                )
                text.maxLines = 2
                // The value refreshes ~every second while converging; a live region
                // would make TalkBack announce every tick. Screen-reader users read
                // the value on focus instead, with the unit spoken in full.
                text.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
                val formatted = LengthFormatter.hero(hero.meters, useMetricUnit)
                val unit = getString(formatted.unitRes)
                val rendered = getString(R.string.elevation_text, formatted.value, unit)
                // In the hero's display size the unit stands as tall as the
                // number, so the line reads as one block of text instead of a
                // figure with a caption. Drop it to a caption and dim it.
                val styled = SpannableString(rendered)
                val unitStart = rendered.lastIndexOf(unit)
                if (unit.isNotEmpty() && unitStart >= 0) {
                    val unitEnd = unitStart + unit.length
                    styled.setSpan(
                        RelativeSizeSpan(UNIT_TEXT_SCALE),
                        unitStart,
                        unitEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    styled.setSpan(
                        ForegroundColorSpan(
                            requireContext().getColor(R.color.hm_on_scrim_secondary)
                        ),
                        unitStart,
                        unitEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                text.text = styled
                // An odometer step: the new figure rises into place. It is applied
                // by renderStabilityLine, because a view has a single
                // ViewPropertyAnimator and a second .start() would cancel the
                // dim/undim animation it is already running
                if (lastHeroValue != null && lastHeroValue != formatted.value) {
                    heroLiftPending = true
                }
                lastHeroValue = formatted.value
                text.contentDescription = getString(
                    if (ellipsoid) R.string.elevation_a11y_ellipsoid else R.string.elevation_a11y,
                    formatted.value,
                    getString(formatted.spokenUnitRes)
                )
            }
        }
    }

    /**
     * Pushes the derived [ReadingState] to the settling line and the hero
     * number's opacity. A null state means the screen is explaining an error,
     * where a kinetic signal widget alongside would contradict it.
     */
    private fun renderStabilityLine(state: ReadingState?) {
        val line = stabilityLine ?: return
        val text = elevationTextView ?: return
        if (state == null) {
            line.isVisible = false
            text.animate().cancel()
            text.alpha = 1f
            return
        }
        line.isVisible = true
        line.setState(state)
        if (heroLiftPending) {
            heroLiftPending = false
            text.translationY = HERO_LIFT_DP * resources.displayMetrics.density
        }
        text.animate()
            .alpha(ReadingState.heroAlpha(state))
            .translationY(0f)
            .setDuration(HERO_FADE_MS)
            .start()
    }

    /**
     * Renders each [DetailsPanelPresenter.Row] as its own two-column child,
     * recycled across calls and updated only where the resolved text changed —
     * so a screen reader mid-readout on an untouched row never has that row's
     * node rewritten under it, and the 1 Hz fix-age ticker invalidates only the
     * one row it affects.
     */
    private fun renderDetails(facts: ElevationUiState.DetailsFacts?) {
        if (facts == null) return
        val panel = detailsPanel ?: return
        val rows = DetailsPanelPresenter.rows(
            DetailsPanelPresenter.Input(
                isIdle = facts.isIdle,
                isBlocked = facts.isBlocked,
                signalStale = facts.signalStale,
                location = facts.location,
                nowElapsedRealtimeNanos = facts.nowElapsedRealtimeNanos,
                satellitesUsed = facts.satellitesUsed,
                satellitesVisible = facts.satellitesVisible,
                pressureHpa = facts.pressureHpa,
                readingCount = facts.readingCount,
                datum = facts.datum,
                useMetric = useMetricUnit,
                locale = Locale.getDefault(),
                region = regionLabel(facts.location)
            )
        )
        val separator = getString(R.string.detail_label_separator)
        rows.forEachIndexed { index, row ->
            // The presenter still builds one "label: value" string per row; the
            // split into columns happens here so the presenter, and the tests
            // that pin its output, stay untouched
            val full = resolve(row)
            val split = full.indexOf(separator)
            val labelled = split > 0
            val label = if (labelled) full.substring(0, split) else ""
            val value = if (labelled) full.substring(split + separator.length) else full

            val rowView = rowViewAt(panel, index)
            val labelView = rowView.findViewById<TextView>(R.id.detail_label)
            val valueView = rowView.findViewById<TextView>(R.id.detail_value)
            if (labelView.text != label) labelView.text = label
            labelView.isVisible = labelled
            if (valueView.text != value) valueView.text = value
            // A row with no label (the "no fix yet" note) keeps the full width
            valueView.gravity = if (labelled) Gravity.END else Gravity.START
            val weight = if (labelled) LABELED_VALUE_WEIGHT else 1f
            val params = valueView.layoutParams as LinearLayout.LayoutParams
            if (params.weight != weight) {
                params.weight = weight
                valueView.layoutParams = params
            }
            // One accessible node per row, carrying what the single TextView
            // used to: the label node stays hidden from the screen reader
            valueView.contentDescription = full
        }
        while (panel.childCount > rows.size) {
            panel.removeViewAt(panel.childCount - 1)
        }
    }

    /**
     * "上海市 浦东新区" for the fix, or null when there is nothing worth saying:
     * the table is still loading, the fix has no location, or the point falls
     * outside the bundled boundaries.
     */
    private fun regionLabel(location: Location?): String? {
        val regions = adminRegions ?: return null
        val fix = location ?: return null
        return regions.find(fix.latitude, fix.longitude)
    }

    private fun rowViewAt(panel: ViewGroup, index: Int): View =
        panel.getChildAt(index) ?: LayoutInflater.from(requireContext())
            .inflate(R.layout.item_detail_row, panel, false)
            .also { panel.addView(it, index) }

    /** Resolves a presenter row, flattening the one nested length resource. */
    private fun resolve(row: DetailsPanelPresenter.Row): String {
        // Argument-less rows skip the formatting overload, which would choke on
        // a translation containing a literal percent sign
        if (row.args.isEmpty()) return getString(row.templateRes)
        val args = row.args.map { arg ->
            if (arg is LengthFormatter.Detail) getString(arg.templateRes, arg.valueText) else arg
        }
        return getString(row.templateRes, *args.toTypedArray())
    }

    private fun renderLocationOffPrompt(show: Boolean) {
        if (!show) {
            dismissLocationOffDialog()
            return
        }
        if (locationOffDialog?.isShowing == true) return
        // Every way the user can close this reports back, so the state stops
        // asking; the dismissal in onPause deliberately does not, leaving the
        // question standing for whenever the screen comes back
        locationOffDialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.location_services_off))
            .setMessage(getString(R.string.location_services_off_message))
            .setPositiveButton(getString(R.string.open_location_settings)) { _, _ ->
                tracker.onLocationPromptAnswered()
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                tracker.onLocationPromptAnswered()
            }
            .setOnCancelListener { tracker.onLocationPromptAnswered() }
            .create()
        locationOffDialog?.show()
    }

    private fun dismissLocationOffDialog() {
        locationOffDialog?.dismiss()
        locationOffDialog = null
    }

    /**
     * The blocked screen's persistent recovery path: unlike the dialogs,
     * which are dismissible and shown at most once per interruption, this
     * button stays available for the rest of the session so dismissing a
     * dialog never strands the user without a way to retry.
     */
    private fun renderBlockedAction(action: ElevationUiState.BlockedAction?) {
        val button = blockedActionButton ?: return
        if (action == null) {
            button.isVisible = false
            return
        }
        button.isVisible = true
        button.setText(action.labelRes)
    }

    private fun performBlockedAction(action: ElevationUiState.BlockedAction) {
        when (action) {
            ElevationUiState.BlockedAction.RequestPermission -> permissionHandler.requestPermissions()
            ElevationUiState.BlockedAction.OpenAppSettings -> permissionHandler.openAppSettings()
            ElevationUiState.BlockedAction.OpenLocationSettings ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
    }

    private fun applyTextAppearance(textAppearanceAttr: Int) {
        val text = elevationTextView ?: return
        // Applied on every repaint, which is once a second even when nothing but
        // the number changed. setTextAppearance requests a layout, so the whole
        // tree was being re-measured every second to produce the same result.
        if (appliedTextAppearanceAttr == textAppearanceAttr) return
        appliedTextAppearanceAttr = textAppearanceAttr
        val resolved = TypedValue()
        requireContext().theme.resolveAttribute(textAppearanceAttr, resolved, true)
        text.setTextAppearance(resolved.resourceId)
        text.setTextColor(requireContext().getColor(R.color.hm_on_scrim))
    }

    companion object {
        private const val HERO_FADE_MS = 250L

        /** The unit's size relative to the hero number it follows. */
        private const val UNIT_TEXT_SCALE = 0.42f

        /** How far the number rises into place when its whole value changes. */
        private const val HERO_LIFT_DP = 8f

        /** Status sentences are prose; they are set below the headline size. */
        private const val STATUS_TEXT_SP = 20f

        /** Share of a labelled detail row taken by the value column. */
        private const val LABELED_VALUE_WEIGHT = 0.66f

        /** Duration of the details panel's height animation. */
        private const val DETAILS_ANIM_MS = 180L

        /** Gap between the details label and its chevron. */
        private const val CHEVRON_PADDING_DP = 4f
    }
}
