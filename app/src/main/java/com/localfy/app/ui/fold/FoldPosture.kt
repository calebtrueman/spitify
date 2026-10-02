package com.localfy.app.ui.fold

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.map

/**
 * How the Fold is physically held right now.
 *
 * - [Kind.Flat]: cover screen, or the inner screen fully open.
 * - [Kind.Tabletop]: half-open with a horizontal hinge (Samsung "Flex Mode"), propped on a desk.
 * - [Kind.Book]: half-open with a vertical hinge, held like a book.
 */
@Immutable
data class FoldPosture(
    val kind: Kind = Kind.Flat,
    /** Hinge bounds in window pixels (only when a fold is present on this display). */
    val hingeLeft: Int = 0,
    val hingeTop: Int = 0,
    val hingeRight: Int = 0,
    val hingeBottom: Int = 0,
    /** True if the fold physically separates content (half-open, or a real gap). */
    val isSeparating: Boolean = false,
    val hasVerticalFold: Boolean = false,
) {
    enum class Kind { Flat, Tabletop, Book }
}

@Composable
fun rememberFoldPosture(activity: Activity): State<FoldPosture> {
    val flow = remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map { info ->
            val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                ?: return@map FoldPosture()
            val b = fold.bounds
            val halfOpen = fold.state == FoldingFeature.State.HALF_OPENED
            val kind = when {
                halfOpen && fold.orientation == FoldingFeature.Orientation.HORIZONTAL -> FoldPosture.Kind.Tabletop
                halfOpen && fold.orientation == FoldingFeature.Orientation.VERTICAL -> FoldPosture.Kind.Book
                else -> FoldPosture.Kind.Flat
            }
            FoldPosture(
                kind = kind,
                hingeLeft = b.left, hingeTop = b.top, hingeRight = b.right, hingeBottom = b.bottom,
                isSeparating = fold.isSeparating,
                hasVerticalFold = fold.orientation == FoldingFeature.Orientation.VERTICAL,
            )
        }
    }
    return flow.collectAsStateWithLifecycle(FoldPosture())
}
