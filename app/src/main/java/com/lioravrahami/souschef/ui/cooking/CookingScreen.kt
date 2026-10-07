package com.lioravrahami.souschef.ui.cooking

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lioravrahami.souschef.AppContainer
import com.lioravrahami.souschef.data.model.Step
import com.lioravrahami.souschef.data.model.TrialStatus
import com.lioravrahami.souschef.data.settings.AppSettings
import com.lioravrahami.souschef.ui.TestTags
import com.lioravrahami.souschef.ui.components.BigButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How far the cook has to drag down on a page to open the overview. */
private val SWIPE_DOWN_THRESHOLD = 120.dp

/**
 * The cooking session: one big page per step (swipe or tap "Next"), wait steps with a
 * reliable countdown, a final "Done" page, and a swipe-down overview of the whole recipe.
 *
 * @param onFinished called with [trialId] once cooking is finished and the rating is due.
 * @param onExit called with the recipe id when the cook leaves without rating.
 */
@Composable
fun CookingScreen(
    container: AppContainer,
    trialId: String,
    onFinished: (trialId: String) -> Unit,
    onExit: (recipeId: String) -> Unit,
) {
    val viewModel: CookingViewModel = viewModel(
        key = "cooking-$trialId",
        factory = viewModelFactory { initializer { CookingViewModel(container, trialId) } },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    KeepScreenOnWhileVisible(container.settings)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val current = state
        when {
            viewModel.leaving || current is CookingUiState.Loading -> CenteredProgress()
            current is CookingUiState.Missing -> {
                val back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                MessagePage(
                    message = "This cooking session no longer exists.",
                    buttonText = "Back",
                    onClick = { current.recipeId?.let(onExit) ?: back?.onBackPressed() },
                )
            }
            current is CookingUiState.AwaitingRating -> MessagePage(
                message = "Cooking is finished — only the rating is missing.",
                buttonText = "Rate this cooking",
                onClick = { onFinished(current.trialId) },
            )
            current is CookingUiState.Closed -> MessagePage(
                message = if (current.status == TrialStatus.DONE) {
                    "This cooking was already rated."
                } else {
                    "This cooking was discarded."
                },
                buttonText = "Back to the recipe",
                onClick = { onExit(current.recipeId) },
            )
            current is CookingUiState.Ready -> CookingSessionContent(
                session = current.session,
                container = container,
                viewModel = viewModel,
                onFinished = onFinished,
                onExit = onExit,
            )
        }
    }
}

/** Keeps the display on while the screen is visible, if the user enabled that setting. */
@Composable
private fun KeepScreenOnWhileVisible(settings: AppSettings) {
    val settingsVersion by settings.changes.collectAsStateWithLifecycle()
    val keepOn = remember(settingsVersion) { settings.keepScreenOn }
    val view = LocalView.current
    DisposableEffect(view, keepOn) {
        if (keepOn) view.keepScreenOn = true
        onDispose { if (keepOn) view.keepScreenOn = false }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CookingSessionContent(
    session: CookingSession,
    container: AppContainer,
    viewModel: CookingViewModel,
    onFinished: (trialId: String) -> Unit,
    onExit: (recipeId: String) -> Unit,
) {
    val latestSession by rememberUpdatedState(session)
    val pagerState = rememberPagerState(initialPage = session.initialPage) { latestSession.pageCount }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var confirmExit by rememberSaveable { mutableStateOf(false) }
    val ringing by container.timerScheduler.isRinging.collectAsStateWithLifecycle()

    // Coming back to the session (after a force-stop, an update, or granting exact alarms)
    // re-registers the system alarm of a running wait, or rings a wait that ended meanwhile.
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { container.timerScheduler.ensureScheduled() }
        onPauseOrDispose { job.cancel() }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val t = latestSession.trial
            viewModel.onPageSettled(page, freshSession = t.currentStep == 0 && t.timerStartedAt == null)
        }
    }
    // Back opens the overview instead of leaving the session by accident.
    BackHandler(enabled = !showSheet) { showSheet = true }

    fun goTo(page: Int) {
        scope.launch { pagerState.animateScrollToPage(page.coerceIn(0, session.pageCount - 1)) }
    }

    fun closeSheet(then: () -> Unit = {}) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            showSheet = false
            then()
        }
    }

    val finish = { viewModel.finish { onFinished(viewModel.trialId) } }
    val trial = session.trial
    val timerStep = trial.timerStepIndex?.takeIf { trial.timerEndAt != null }
    val settledPage = pagerState.settledPage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        val onTimerPage = timerStep != null && settledPage == timerStep
        // The timer's own page offers "Stop alarm & continue" only once its time is up.
        val timesUp = rememberIsPast(trial.timerEndAt)
        if (ringing && !(onTimerPage && timesUp)) {
            StopAlarmBar(onClick = { viewModel.clearWait(timerStep) })
        } else if (timerStep != null && !onTimerPage) {
            // Ringing is false here: a finished timer is a leftover silenced elsewhere
            // (notification, auto-stop, missed alarm), so the bar clears it in place.
            OtherTimerBar(
                timerStep = timerStep,
                endAt = trial.timerEndAt ?: 0L,
                onGoBack = { goTo(timerStep) },
                onDismiss = { viewModel.clearWait(timerStep) },
            )
        }
        ReliabilityBanner(
            timers = container.timerScheduler,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
        CookingHeader(
            session = session,
            pagerState = pagerState,
            onOverview = { showSheet = true },
        )
        if (session.valuesMismatch) {
            Text(
                text = "The saved amounts don't match this version — showing the amounts as written.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        val thresholdPx = with(LocalDensity.current) { SWIPE_DOWN_THRESHOLD.toPx() }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(thresholdPx) {
                    var total = 0f
                    var fired = false
                    detectVerticalDragGestures(
                        onDragStart = {
                            total = 0f
                            fired = false
                        },
                        onVerticalDrag = { change, dragAmount ->
                            total += dragAmount
                            if (!fired && total > thresholdPx) {
                                fired = true
                                showSheet = true
                            }
                            change.consume()
                        },
                    )
                },
        ) {
            HorizontalPager(
                state = pagerState,
                key = { it },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(TestTags.COOK_PAGER),
            ) { page ->
                val step = session.steps.getOrNull(page)
                val back: (() -> Unit)? = if (page > 0) ({ goTo(page - 1) }) else null
                when (step) {
                    null -> DonePage(
                        onRate = finish,
                        onExitWithoutRating = { confirmExit = true },
                        onBack = back,
                    )
                    is Step.Text -> StepPage(
                        session = session,
                        stepIndex = page,
                        step = step,
                        onNext = { goTo(page + 1) },
                        onBack = back,
                    )
                    is Step.Wait -> WaitPage(
                        session = session,
                        stepIndex = page,
                        step = step,
                        isSettled = settledPage == page,
                        autoStartArmed = viewModel.autoStartArmed,
                        alreadyHandled = page in viewModel.handledWaits,
                        onAutoStart = { seconds ->
                            // Replacing another step's finished timer: that step is done, so it
                            // must not start again by itself when the cook swipes back to it.
                            val t = latestSession.trial
                            t.timerStepIndex
                                ?.takeIf { it != page && t.timerEndAt != null }
                                ?.let(viewModel::markHandled)
                            viewModel.autoStartWait(page, seconds)
                        },
                        onStart = { seconds -> viewModel.startWait(page, seconds) },
                        onClearAndAdvance = {
                            viewModel.clearWait(page)
                            goTo(page + 1)
                        },
                        onAdvance = {
                            viewModel.markHandled(page)
                            goTo(page + 1)
                        },
                        alarmRinging = ringing,
                    )
                }
            }
        }
    }

    if (showSheet) {
        OverviewSheet(
            session = session,
            currentPage = pagerState.currentPage,
            sheetState = sheetState,
            onDismiss = { closeSheet() },
            onJumpTo = { page -> closeSheet { goTo(page) } },
            onFinishAndRate = { closeSheet(finish) },
            onExitWithoutRating = { closeSheet { confirmExit = true } },
        )
    }
    if (confirmExit) {
        ExitWithoutRatingDialog(
            onConfirm = {
                confirmExit = false
                viewModel.abort { onExit(session.trial.recipeId) }
            },
            onDismiss = { confirmExit = false },
        )
    }
}

/** "Step 3 of 8", the recipe name, the overview button and a thin progress bar. */
@Composable
private fun CookingHeader(session: CookingSession, pagerState: PagerState, onOverview: () -> Unit) {
    val stepCount = session.steps.size
    val page = pagerState.currentPage
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = CookingTexts.progressLabel(page, stepCount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = session.recipeName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledTonalButton(
                onClick = onOverview,
                modifier = Modifier
                    .heightIn(min = 56.dp)
                    .testTag(TestTags.COOK_OVERVIEW),
            ) {
                Text("Overview", style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
        }
        LinearProgressIndicator(
            progress = { CookingTexts.progressFraction(page, stepCount) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "swipe down for overview",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Slim bar shown on every page while the alarm rings; one tap silences it and clears the timer. */
@Composable
private fun StopAlarmBar(onClick: () -> Unit) {
    BigButton(
        text = "Stop alarm",
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Compact bar on other pages: while a step's timer runs, tapping jumps back to that step
 * ([onGoBack]); once it has finished (and is not ringing), tapping clears it ([onDismiss]).
 */
@Composable
private fun OtherTimerBar(timerStep: Int, endAt: Long, onGoBack: () -> Unit, onDismiss: () -> Unit) {
    val now = rememberWallClock(ticking = endAt > System.currentTimeMillis())
    val remaining = WaitClock.remainingSeconds(endAt, now)
    Surface(
        onClick = if (remaining > 0) onGoBack else onDismiss,
        color = if (remaining > 0) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (remaining > 0) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .heightIn(min = 56.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(
                text = CookingTexts.otherTimerBar(timerStep, remaining),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Whether the wall-clock time [endAtMillis] has passed; flips exactly once, without ticking. */
@Composable
internal fun rememberIsPast(endAtMillis: Long?): Boolean {
    val isPast by produceState(
        initialValue = endAtMillis != null && endAtMillis <= System.currentTimeMillis(),
        endAtMillis,
    ) {
        value = endAtMillis != null && endAtMillis <= System.currentTimeMillis()
        if (endAtMillis != null && !value) {
            delay(endAtMillis - System.currentTimeMillis())
            value = true
        }
    }
    return isPast
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

/** A short explanation with one way out, for sessions that can no longer be cooked. */
@Composable
private fun MessagePage(message: String, buttonText: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        BigButton(text = buttonText, onClick = onClick)
    }
}
