package com.suzuri.lmdroid.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.suzuri.lmdroid.R
import kotlin.math.roundToInt

/** Slider upper bound in tokens — see [com.suzuri.lmdroid.data.settings.AppSettings.thinkingBudget]. */
const val THINKING_BUDGET_MAX = 32768

/** Slider granularity in tokens. */
const val THINKING_BUDGET_STEP = 1024

/**
 * A ModalBottomSheet with a Slider for the 思考予算 — the maximum number of tokens a
 * reasoning-capable model may spend in its thinking block (see
 * [com.suzuri.lmdroid.data.settings.AppSettings.thinkingBudget]). A bottom sheet (rather than a
 * DropdownMenu row) is used because a Slider needs more width than a compact menu can offer — the
 * same convention as the composer's "+" add menu. It is opened from the model selector sheet's
 * 思考予算 row (see [ModelSelectorButton]), which consolidates the former standalone toolbar
 * button. 0 means "no explicit cap (server default)".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingBudgetSheet(
    budget: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_thinking_budget_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (budget > 0) {
                    stringResource(R.string.chat_thinking_budget_value, budget)
                } else {
                    stringResource(R.string.chat_thinking_budget_default)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Slider(
                value = budget.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = 0f..THINKING_BUDGET_MAX.toFloat(),
                // Discrete stops at every THINKING_BUDGET_STEP (number of stops BETWEEN the
                // endpoints, not including them).
                steps = THINKING_BUDGET_MAX / THINKING_BUDGET_STEP - 1,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
