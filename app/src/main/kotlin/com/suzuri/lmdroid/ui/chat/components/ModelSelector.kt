package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import com.suzuri.lmdroid.data.db.ModelOptionRow
import com.suzuri.lmdroid.data.db.ThinkingEffort
import com.suzuri.lmdroid.data.settings.SelectedModel
import kotlinx.coroutines.delay

/**
 * Lets the user switch which enabled profile's model chat uses, without leaving this screen.
 * Hidden by the caller when [options] is empty — with no enabled profile there's nothing to
 * switch between. The button shows just the current profile's name (Claude-style, rather than a
 * "profile · model" pair on every row) since that's typically the more meaningful label to
 * glance at (e.g. "ローカルサーバー" vs a long/cryptic model id); the sheet itself is grouped by
 * profile — with a section header per profile and a checkmark on the active model, each row
 * still showing its own model id — instead of repeating the profile name on every single line.
 *
 * Below the model list, the sheet also consolidates the per-model generation controls that used
 * to be separate toolbar icons next to this button (ChatGPT/Claude-app style, where tapping the
 * model name opens one sheet holding both model choice and modes):
 *  - 思考(thinking) effort: tapping expands the [ThinkingEffort] options inline under the row
 *    (the sheet stays open so the current level is visible; picking one closes the sheet);
 *  - 思考予算(thinking budget): closes the sheet and opens [ThinkingBudgetSheet] — a Slider
 *    needs more room than a sheet row can offer;
 *  - 記憶を保持(memory): toggles in place, checkmark reflecting state (sheet stays open).
 * Controls the selected model demonstrably doesn't support — per the capability flags fetched at
 * registration time (see ApiModelEntity/ModelCapabilities) — are hidden from this sheet entirely;
 * models whose capabilities were never advertised keep showing all of them.
 *
 * The picker is a [ModalBottomSheet] using the same slide-up motion and row styling as the "+"
 * add button's sheet (see ChatInputBar's PlusSheetItem), not a floating dropdown — matching the
 * Claude app, where every composer-side menu (attach, model, modes) arrives the same way: one
 * consistent bottom-sheet gesture instead of a menu popping out of the tiny trigger. Rows
 * therefore never need resize gymnastics: the sheet springs up once, and only the 思考 sublist
 * animates (via [expandVertically], which the sheet's own window absorbs smoothly) while the
 * checkmarks fade/scale in and out of always-present slots, so no row ever reflows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorButton(
    options: List<ModelOptionRow>,
    selected: SelectedModel?,
    onSelect: (ModelOptionRow) -> Unit,
    thinkingEffort: ThinkingEffort,
    onThinkingEffortChange: (ThinkingEffort) -> Unit,
    thinkingBudget: Int,
    onThinkingBudgetChange: (Int) -> Unit,
    memoryEnabled: Boolean,
    onMemoryEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    // The 思考 row's inline expander state — kept separate from [expanded] so dismissing the
    // whole sheet (drag/swipe/tap-outside) also collapses the sublist for next time.
    var effortOptionsShown by remember { mutableStateOf(false) }
    var budgetSheetShown by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val selectedOption = options.find { it.profileId == selected?.profileId && it.modelId == selected.model }
    val label = selectedOption?.profileName ?: stringResource(R.string.chat_model_selector_placeholder)
    // Only worth labeling groups when there's more than one profile to tell apart — with a single
    // enabled profile, a lone section header would just repeat what the button already says.
    val groupedOptions = options.groupBy { it.profileId to it.profileName }
    val showGroupHeaders = groupedOptions.size > 1

    // Hide the per-model controls the selected model demonstrably can't honor — the supports*
    // flags were fetched from the server at registration (see ApiModelEntity). null means the
    // server never said, so the control stays visible exactly as before; only a definite false
    // hides it. A model whose template supports neither OFF nor effort levels loses the whole 思考
    // row, and with it the divider if nothing else remains below the model list.
    val thinkingRowSupported = selectedOption?.let { it.supportsThinking != false || it.supportsReasoningEffort != false } ?: true
    val thinkingOffSupported = selectedOption?.supportsThinking ?: true
    val effortLevelsSupported = selectedOption?.supportsReasoningEffort ?: true
    val budgetSupported = selectedOption?.supportsThinkingBudget ?: true
    val memorySupported = selectedOption?.supportsMemory ?: true
    val anyControlShown = thinkingRowSupported || budgetSupported || memorySupported

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.clickable { expanded = true },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The trigger arrow tracks the sheet: rotates 180° while it's open instead of
            // being a static glyph, so opening/closing has a visible cause-and-effect.
            val arrowRotation by animateFloatAsState(
                targetValue = if (expanded) 180f else 0f,
                animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
                label = "modelSelectorArrow",
            )
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
                    .rotate(arrowRotation),
            )
        }
    }

    if (expanded) {
        ModalBottomSheet(
            onDismissRequest = {
                expanded = false
                effortOptionsShown = false
            },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    // Cap so a long model list + expanded sublist scrolls instead of growing the
                    // sheet past the screen.
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                groupedOptions.entries.forEachIndexed { index, (profile, models) ->
                    if (index > 0) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                    if (showGroupHeaders) {
                        Text(
                            text = profile.second,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    models.forEach { option ->
                        SheetCheckRow(
                            checked = option.profileId == selected?.profileId && option.modelId == selected.model,
                            onClick = {
                                expanded = false
                                onSelect(option)
                            },
                            text = {
                                Text(option.modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                    }
                }

                // ── Per-model generation controls, consolidated from the toolbar icons ──
                if (anyControlShown) HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 思考 effort: an inline expander rather than a nested sheet (a ModalBottomSheet
                // can't nest another one behind the same scrim). Stays open on tap so the current
                // level stays visible.
                if (thinkingRowSupported) {
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (effortOptionsShown) 180f else 0f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
                        label = "effortChevron",
                    )
                    SheetRow(
                        onClick = { effortOptionsShown = !effortOptionsShown },
                        text = { Text(stringResource(R.string.chat_thinking_effort_title)) },
                        trailing = {
                            Text(
                                text = thinkingEffort.label(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // One icon rotated, not two swapped — the flip reads as a continuous
                            // motion either way.
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = null,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(18.dp)
                                    .rotate(chevronRotation),
                            )
                        },
                    )
                    // The sublist grows/shrinks in place, so nothing else moves around it.
                    val sublistBringIntoView = remember { BringIntoViewRequester() }
                    AnimatedVisibility(
                        visible = effortOptionsShown,
                        enter = expandVertically(tween(220, easing = FastOutSlowInEasing)) +
                            fadeIn(tween(160, delayMillis = 60)),
                        exit = shrinkVertically(tween(160, easing = LinearOutSlowInEasing)) +
                            fadeOut(tween(80)),
                    ) {
                        Column(modifier = Modifier.bringIntoViewRequester(sublistBringIntoView)) {
                            // OFF rides chat_template_kwargs.enable_thinking and the levels ride
                            // reasoning_effort — a model advertising only one of the two offers
                            // just that half of the list (see ModelCapabilities).
                            ThinkingEffort.entries
                                .filter { if (it == ThinkingEffort.OFF) thinkingOffSupported else effortLevelsSupported }
                                .forEach { option ->
                                    SheetCheckRow(
                                        checked = option == thinkingEffort,
                                        onClick = {
                                            expanded = false
                                            effortOptionsShown = false
                                            onThinkingEffortChange(option)
                                        },
                                        // Indent to read as a sublist of the 思考 row above.
                                        text = {
                                            Text(option.label(), modifier = Modifier.padding(start = 20.dp))
                                        },
                                    )
                                }
                        }
                    }
                    // The sheet's content scrolls past the 480.dp cap; when it already is,
                    // scroll the freshly expanded sublist back into view — after the expand
                    // animation, so the requested rect is the final one.
                    LaunchedEffect(effortOptionsShown) {
                        if (effortOptionsShown) {
                            delay(240)
                            sublistBringIntoView.bringIntoView()
                        }
                    }
                }

                // 思考予算: the Slider lives in its own sheet (needs room a row can't give), so
                // this row hands off to it — same hand-over the "+" menu does for its actions.
                if (budgetSupported) {
                    val budgetValueText = if (thinkingBudget > 0) {
                        stringResource(R.string.chat_thinking_budget_value, thinkingBudget)
                    } else {
                        stringResource(R.string.chat_thinking_budget_unlimited)
                    }
                    SheetRow(
                        onClick = {
                            expanded = false
                            budgetSheetShown = true
                        },
                        text = { Text(stringResource(R.string.chat_thinking_budget_label)) },
                        trailing = {
                            Crossfade(
                                targetState = budgetValueText,
                                animationSpec = tween(150),
                                modifier = Modifier.heightIn(max = 20.dp),
                                label = "budgetValue",
                            ) { value ->
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    )
                }

                // 記憶: toggles in place (sheet stays open) so the checkmark appearing/disappearing
                // confirms the change, like ChatGPT's mode toggles.
                if (memorySupported) {
                    SheetCheckRow(
                        checked = memoryEnabled,
                        onClick = { onMemoryEnabledChange(!memoryEnabled) },
                        text = { Text(stringResource(R.string.chat_memory_toggle_label)) },
                    )
                }
            }
        }
    }

    if (budgetSheetShown) {
        ThinkingBudgetSheet(
            budget = thinkingBudget,
            onChange = onThinkingBudgetChange,
            onDismiss = { budgetSheetShown = false },
        )
    }
}

/**
 * A full-width row in the model selector's bottom sheet, deliberately mirroring the "+" add
 * sheet's PlusSheetItem (see ChatInputBar): the same 20/16.dp padding, no-ripple clickable and
 * bodyLarge label, with an optional [trailing] composable on the right. [text] takes the
 * remaining width.
 */
@Composable
private fun SheetRow(
    onClick: () -> Unit,
    text: @Composable () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { text() }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/**
 * A [SheetRow] with a trailing selection checkmark that animates in/out of an always-present
 * fixed-size slot ([AnimatedVisibility] with [scaleIn]/[scaleOut]) instead of appearing from
 * nothing, so toggling selection never reflows the row.
 */
@Composable
private fun SheetCheckRow(
    checked: Boolean,
    onClick: () -> Unit,
    text: @Composable () -> Unit,
) {
    SheetRow(
        onClick = onClick,
        text = text,
        trailing = {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) {
                AnimatedVisibility(
                    visible = checked,
                    enter = fadeIn(tween(140)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 600f), initialScale = 0.4f),
                    exit = fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.6f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}
