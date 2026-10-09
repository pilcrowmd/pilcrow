// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.ui.components

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.pilcrowmd.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards **M-04**: during a pinch, blocks ended up at three different sizes.
 *
 * ## The defect
 *
 * The live pinch used to multiply each *currently attached* TextView by a per-frame **ratio**
 * (`setTextSize(PX, view.textSize * ratio)`). That makes a view's final size depend on **how many
 * frames it happened to be attached for**, and the focal-anchor logic re-scrolls every frame, so
 * holders are created and recycled mid-gesture. Three outcomes, one gesture:
 *
 * - a block attached the whole time accumulated every ratio — correct;
 * - a block created mid-gesture started at the pre-gesture base and got only the *remaining*
 *   ratios — too small;
 * - a block bound into a **recycled** holder inherited whatever stale scaled size that holder was
 *   last left at — arbitrary.
 *
 * The fix makes the live scale **absolute**: each TextView's size at the committed scale is
 * recorded once per gesture, and every frame sets `base × factor`. That is idempotent, so a view's
 * size stops depending on its attachment history.
 *
 * ## Why each test is shaped the way it is
 *
 * Every test below applies **two** frames, not one. A single frame passes against the old relative
 * code too (`base × f` and `textSize × f` agree on the first frame, when `textSize == base`), so a
 * one-frame fixture would be green for a reason unrelated to what it claims to check. The second
 * frame is what makes the absolute property load-bearing: mutate `base * factor` back to
 * `view.textSize * factor` and every test here fails.
 */
@RunWith(RobolectricTestRunner::class)
class PreviewPinchScaleTest {

    private lateinit var context: Context

    /** An arbitrary exact PX size, so every expectation below is exact float arithmetic. */
    private val base = 40f

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun textView(sizePx: Float = base) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }

    @Test
    fun successiveFramesDoNotAccumulate() {
        val root = LinearLayout(context)
        val tv = textView()
        root.addView(tv)

        applyPinchTextScale(root, 1.2f)
        applyPinchTextScale(root, 1.5f)

        // Relative scaling would land on 40 × 1.2 × 1.5 = 72.
        assertEquals(base * 1.5f, tv.textSize, 0.001f)
    }

    @Test
    fun aViewCreatedMidGestureEndsAtTheSameSizeAsOneThatSurvived() {
        val root = LinearLayout(context)
        val survivor = textView()
        root.addView(survivor)

        applyPinchTextScale(root, 1.2f)

        // A holder created mid-gesture is built at the committed scale, so it enters at `base`.
        val createdMidGesture = textView()
        root.addView(createdMidGesture)

        applyPinchTextScale(root, 1.5f)

        assertEquals(
            "a block created mid-gesture must not be smaller than one that survived it",
            survivor.textSize,
            createdMidGesture.textSize,
            0.001f,
        )
        assertEquals(base * 1.5f, survivor.textSize, 0.001f)
    }

    @Test
    fun aRecycledHolderReboundToItsBaseEndsAtTheSameSizeAsOneThatSurvived() {
        val root = LinearLayout(context)
        val survivor = textView()
        val recycled = textView()
        root.addView(survivor)
        root.addView(recycled)

        applyPinchTextScale(root, 1.2f)

        // `bindHolder` re-sets the size on rebind for some entries (fenced code, footnotes), so a
        // recycled view can drop back to its base part-way through the gesture.
        recycled.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)

        applyPinchTextScale(root, 1.5f)

        assertEquals(
            "a rebound recycled block must not be smaller than one that survived the gesture",
            survivor.textSize,
            recycled.textSize,
            0.001f,
        )
        assertEquals(base * 1.5f, recycled.textSize, 0.001f)
    }

    @Test
    fun aViewCarryingAStaleSizeIsNotMeasuredFromThatStaleSize() {
        val root = LinearLayout(context)
        val tv = textView()
        root.addView(tv)

        applyPinchTextScale(root, 1.2f)

        // Simulate the RecyclerView handing back a pooled holder still carrying a mid-gesture size
        // (nothing resets `ProseBlockEntry`'s size on rebind — it is set only in `createHolder`).
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, 999f)

        applyPinchTextScale(root, 1.5f)

        // The recorded base decides the size, NOT whatever the view happens to carry.
        assertEquals(base * 1.5f, tv.textSize, 0.001f)
    }

    @Test
    fun clearingTheBasesRerecordsFromTheCurrentSize() {
        val root = LinearLayout(context)
        val tv = textView()
        root.addView(tv)

        applyPinchTextScale(root, 1.2f)
        // A new gesture starts from whatever the committed rebuild left on screen.
        clearPinchTextScaleBases(root)
        applyPinchTextScale(root, 1.5f)
        // A THIRD frame, and it is not decoration. With only two, the expected value is the same
        // whether the scaling is absolute or relative — clearing the bases makes the two arithmetics
        // agree for exactly one step — so the test would pass against the defect it sits next to.
        // The third frame separates them again: absolute gives 48 x 2 = 96, relative 48 x 1.5 x 2.
        applyPinchTextScale(root, 2.0f)

        val rebasedTo = base * 1.2f
        assertEquals(rebasedTo * 2.0f, tv.textSize, 0.001f)
    }

    @Test
    fun nestedContainersAreScaledToo() {
        // Tables and fenced code blocks put their TextView inside a HorizontalScrollView.
        val root = LinearLayout(context)
        val nested = FrameLayout(context)
        val tv = textView()
        nested.addView(tv)
        root.addView(nested)

        applyPinchTextScale(root, 1.2f)
        applyPinchTextScale(root, 1.5f)

        assertEquals(base * 1.5f, tv.textSize, 0.001f)
    }

    // ---- the Mermaid fallback path: a hidden view must not have a base recorded ----

    @Test
    fun aHiddenViewIsNotScaledAndGetsNoBaseRecorded() {
        val root = LinearLayout(context)
        val hidden = textView(sizePx = 12f).apply { visibility = View.GONE }
        root.addView(hidden)

        applyPinchTextScale(root, 1.2f)
        applyPinchTextScale(root, 1.5f)

        assertEquals("a GONE view must be left alone entirely", 12f, hidden.textSize, 0.001f)
    }

    @Test
    fun aViewSHOWNMidGestureIsMeasuredFromTheSizeItWasShownAt() {
        // `FencedCodeBlockEntry.bindMermaid` hides `codeScroll` WITHOUT setting `codeView`'s size,
        // and `fallbackToSource` — an async image load-error callback, so it can land mid-gesture —
        // makes it visible and sets that size at the committed scale. If a base had been recorded
        // while it was hidden, the block would jump to a size derived from the stale value.
        val root = LinearLayout(context)
        val survivor = textView()
        // Hidden, and carrying a size that is NOT the committed one — the whole hazard.
        val mermaidCode = textView(sizePx = 12f).apply { visibility = View.GONE }
        root.addView(survivor)
        root.addView(mermaidCode)

        applyPinchTextScale(root, 1.2f)

        // fallbackToSource: shown, and set to the committed scale.
        mermaidCode.visibility = View.VISIBLE
        mermaidCode.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)

        applyPinchTextScale(root, 1.5f)

        assertEquals(
            "a block revealed mid-gesture must land where the survivors are",
            survivor.textSize,
            mermaidCode.textSize,
            0.001f,
        )
        assertEquals(base * 1.5f, mermaidCode.textSize, 0.001f)
    }

    @Test
    fun clearingBasesReachesHiddenViewsToo() {
        // The asymmetry with applyPinchTextScale is deliberate: if a hidden view kept a stale tag,
        // becoming visible mid-gesture would hand that stale base straight to the next frame.
        val root = LinearLayout(context)
        val tv = textView()
        root.addView(tv)

        applyPinchTextScale(root, 1.2f) // records base = 40 while visible
        tv.visibility = View.GONE
        clearPinchTextScaleBases(root) // must reach it even though it is hidden
        tv.visibility = View.VISIBLE
        // Deliberately NOT `base`. Re-showing it at the size the stale tag already holds makes the
        // two outcomes identical, and the test passes whether the clear reached it or not — the
        // first version of this test did exactly that and survived the mutation that skips hidden
        // views. 20f is the only value that separates a fresh base (20 x 1.5 = 30) from the stale
        // one (40 x 1.5 = 60).
        val reshownAt = 20f
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, reshownAt)

        applyPinchTextScale(root, 1.5f)

        assertEquals(reshownAt * 1.5f, tv.textSize, 0.001f)
    }

    // ---- the gesture-begin reset: the pool clear is otherwise untested ----

    private class Holder(view: View) : RecyclerView.ViewHolder(view)

    @Test
    fun beginningAGestureEmptiesTheRecycledViewPool() {
        // `swapAdapter` keeps the pool across the end-of-gesture rebuild, so a holder stashed during
        // the PREVIOUS pinch still carries that gesture's size. clearPinchTextScaleBases walks the
        // ATTACHED tree and cannot reach it, so only emptying the pool prevents a wrong base.
        val rv = RecyclerView(context)
        val pool = rv.recycledViewPool
        val holder = Holder(textView())
        pool.putRecycledView(holder)
        // Read the type back off the holder rather than assuming 0 — the pool files by
        // `itemViewType`, and an unbound holder's is INVALID_TYPE. The precondition below earned
        // its place by catching exactly that: the assertion was querying an empty slot.
        val type = holder.itemViewType
        assertNotEquals(
            "precondition: the pool must actually hold something",
            0,
            pool.getRecycledViewCount(type),
        )

        beginPinchScaleGesture(rv)

        assertEquals(
            "a stale pooled holder must not survive into a new gesture",
            0,
            pool.getRecycledViewCount(type),
        )
    }

    @Test
    fun beginningAGestureAlsoForgetsTheBases() {
        val rv = RecyclerView(context)
        rv.layoutManager = LinearLayoutManager(context)
        val tv = textView()
        rv.addView(tv)

        applyPinchTextScale(rv, 1.2f) // base = 40
        beginPinchScaleGesture(rv) // must forget it, so 48 becomes the new base
        applyPinchTextScale(rv, 1.5f)

        assertEquals(base * 1.2f * 1.5f, tv.textSize, 0.001f)
    }

    @Test
    fun aViewMarkedExcludedIsNeverScaled() {
        // Chrome (the code block's Copy button, the Mermaid caption) and the LaTeX formula take
        // their size from the layout, NOT from the reader font scale, so no bindHolder re-applies
        // it. Scaling them would write a PX size that nothing can undo — permanently wrong at rest,
        // which is the same failure the bind-time reset fixes for document text.
        val root = LinearLayout(context)
        val documentText = textView()
        val chrome = textView(sizePx = 12f).apply { setTag(R.id.pinch_excluded, true) }
        root.addView(documentText)
        root.addView(chrome)

        applyPinchTextScale(root, 1.2f)
        applyPinchTextScale(root, 1.5f)

        assertEquals("document text still scales", base * 1.5f, documentText.textSize, 0.001f)
        assertEquals("excluded chrome must be untouched", 12f, chrome.textSize, 0.001f)
    }

    @Test
    fun anExcludedContainerShieldsItsChildren() {
        // The LaTeX formula sits inside a HorizontalScrollView; excluding the subtree root must
        // stop the walk before it reaches the TextView underneath.
        val root = LinearLayout(context)
        val excludedGroup = FrameLayout(context).apply { setTag(R.id.pinch_excluded, true) }
        val inner = textView(sizePx = 12f)
        excludedGroup.addView(inner)
        root.addView(excludedGroup)

        applyPinchTextScale(root, 1.5f)

        assertEquals(12f, inner.textSize, 0.001f)
    }

    // ---- the item cache: a row parked there mid-gesture is handed back without a rebind ----

    /** Fixed-height rows, so the gesture's text size cannot move the layout under the scroll. */
    private inner class RowAdapter : RecyclerView.Adapter<Holder>() {
        val binds = IntArray(ROWS)

        override fun getItemCount() = ROWS

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            textView().apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ROW_PX)
            },
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            binds[position]++
            // As every reader entry does: the bind re-sets the size at the committed scale.
            (holder.itemView as TextView).setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
        }
    }

    private fun layOut(rv: RecyclerView) {
        val spec = View.MeasureSpec.makeMeasureSpec(VIEWPORT_PX, View.MeasureSpec.EXACTLY)
        rv.measure(spec, spec)
        rv.layout(0, 0, VIEWPORT_PX, VIEWPORT_PX)
    }

    @Test
    fun aRowCachedAboveTheScreenMidGestureComesBackAtItsBaseSize() =
        assertCachedRowComesBackAtBase(firstOnScreen = 0, dy = ROW_PX + ROW_PX / 2, cachedRow = 0)

    @Test
    fun aRowCachedBelowTheScreenMidGestureComesBackAtItsBaseSize() =
        // Rows 5..7 fill the screen; scrolling up parks row 7 in the cache.
        assertCachedRowComesBackAtBase(firstOnScreen = 5, dy = -(ROW_PX + ROW_PX / 2), cachedRow = 7)

    /**
     * A row scrolled just off-screen during a pinch goes to RecyclerView's item cache, which hands it
     * back WITHOUT a rebind, so the bind-time size reset never reaches it. When the gesture ends on
     * the scale it started at there is no adapter rebuild either, so only the end-of-gesture step can
     * put it right.
     */
    private fun assertCachedRowComesBackAtBase(firstOnScreen: Int, dy: Int, cachedRow: Int) {
        val adapter = RowAdapter()
        val lm = LinearLayoutManager(context)
        val rv = RecyclerView(context).apply {
            layoutManager = lm
            this.adapter = adapter
        }
        lm.scrollToPositionWithOffset(firstOnScreen, 0)
        layOut(rv)
        val row = rv.findViewHolderForAdapterPosition(cachedRow)
        assertNotNull("precondition: row $cachedRow must be on screen", row)
        row!!

        beginPinchScaleGesture(rv)
        applyPinchTextScale(rv, 1.3f)
        rv.scrollBy(0, dy)

        // Precondition: the row is in the item cache. Detached, and NOT in the pool, where a rebind
        // would reset it and make this test pass for a reason unrelated to what it checks.
        assertNull("precondition: row $cachedRow must be off-screen", rv.findViewHolderForAdapterPosition(cachedRow))
        assertEquals(
            "precondition: row $cachedRow must be in the item cache, not the pool",
            0,
            rv.recycledViewPool.getRecycledViewCount(row.itemViewType),
        )
        assertEquals(
            "precondition: row $cachedRow must still carry the gesture size",
            base * 1.3f,
            (row.itemView as TextView).textSize,
            0.001f,
        )

        val onScreen = (0 until rv.childCount).map { rv.getChildAdapterPosition(rv.getChildAt(it)) }
        val bindsBefore = onScreen.map { adapter.binds[it] }

        endPinchScaleGestureWithoutRebuild(rv)
        layOut(rv)

        // Only the rows off screen may be rebound: rebinding a visible block re-renders it, restarts
        // its code colouring and reloads its images, all for a size the reset above already fixed.
        assertEquals(
            "rows on screen ($onScreen) must not be rebound",
            bindsBefore,
            onScreen.map { adapter.binds[it] },
        )
        rv.scrollBy(0, -dy)

        val back = rv.findViewHolderForAdapterPosition(cachedRow)
        assertNotNull("row $cachedRow must be back on screen", back)
        assertSame("row $cachedRow must come back in its own holder", row, back)
        assertEquals(
            "a row cached mid-gesture must come back at rest at its base size " +
                "(same holder: ${back === row}, binds: ${adapter.binds[cachedRow]})",
            base,
            (back!!.itemView as TextView).textSize,
            0.001f,
        )
    }

    private companion object {
        const val ROWS = 10
        const val ROW_PX = 100
        const val VIEWPORT_PX = 300
    }
}
