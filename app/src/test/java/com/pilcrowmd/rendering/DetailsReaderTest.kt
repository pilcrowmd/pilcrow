// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 pleree

package com.pilcrowmd.rendering

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pilcrowmd.R
import com.pilcrowmd.domain.markdown.Details
import com.pilcrowmd.domain.markdown.ReaderDocument
import com.pilcrowmd.ui.theme.DarkColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * M-161 in a real reader list: the production adapter and decoration, laid out in an Activity. The
 * assertions are on laid-out heights, because "hidden" means "takes no space" — a flag that says so
 * while the block still occupies the screen would pass any state-only test.
 */
@RunWith(RobolectricTestRunner::class)
class DetailsReaderTest {

    // 0 para · 1 header · 2 body para · 3 body list · 4 </details> · 5 para
    private val markdown =
        "Before.\n\n<details>\n<summary>Section</summary>\n\nHidden body.\n\n- item\n</details>\n\nAfter."

    private fun reader(
        markdown: String = this.markdown,
        viewportPx: Int = 4000,
    ): Triple<RecyclerView, DetailsState, DetailsDecoration> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val markwon = buildPilcrowMarkwon(activity)
        val state = DetailsState()
        val list = RecyclerView(activity)
        list.layoutManager = LinearLayoutManager(activity)
        list.setTag(R.id.details_state, state)
        val decoration = DetailsDecoration(activity, state, DarkColorScheme)
        list.addItemDecoration(decoration)
        val adapter = RecyclerAdapterEntries.buildMarkdownAdapter(activity, markwon, details = state)
        val document = ReaderDocument.transform(markwon.parse(markdown))
        state.load(markdown, Details.sections(document))
        adapter.setParsedMarkdown(markwon, document)
        list.adapter = adapter
        activity.setContentView(list)
        settle(list, viewportPx)
        return Triple(list, state, decoration)
    }

    private fun settle(list: RecyclerView, viewportPx: Int = 4000) {
        shadowOf(Looper.getMainLooper()).idle()
        list.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportPx, View.MeasureSpec.EXACTLY),
        )
        list.layout(0, 0, 1080, viewportPx)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun height(list: RecyclerView, position: Int): Int =
        list.findViewHolderForAdapterPosition(position)!!.itemView.height

    @Test
    fun `a closed section takes no space below its header`() {
        val (list, _) = reader()
        assertTrue("the header shows", height(list, 1) > 0)
        assertEquals("body paragraph", 0, height(list, 2))
        assertEquals("body list", 0, height(list, 3))
        assertEquals("the close block never paints", 0, height(list, 4))
        assertTrue("the text after the section shows", height(list, 5) > 0)
    }

    @Test
    fun `tapping the header opens the section, and tapping again closes it`() {
        val (list, _) = reader()
        list.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
        settle(list)
        assertTrue("body paragraph shown after a tap", height(list, 2) > 0)
        assertTrue("body list shown after a tap", height(list, 3) > 0)
        list.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
        settle(list)
        assertEquals("closed again", 0, height(list, 2))
    }

    private fun text(list: RecyclerView, position: Int): String =
        (list.findViewHolderForAdapterPosition(position)!!.itemView as TextView).text.toString()

    /** A closed section's body is bound as the list fills the screen past it, but never rendered. */
    @Test
    fun `a hidden block is not rendered`() {
        val (list, _) = reader()
        assertEquals("the hidden paragraph was rendered", "", text(list, 2))
    }

    /** Since a hidden block is not rendered, showing it must render it, or the body shows empty. */
    @Test
    fun `an opened section shows its own text`() {
        val (list, _) = reader()
        list.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
        settle(list)
        assertEquals("Hidden body.", text(list, 2).trim())
    }

    @Test
    fun `a jump to a block inside a closed section opens it`() {
        val (list, _) = reader()
        list.findViewHolderForAdapterPosition(0)!!.itemView.jumpToBlock(3)
        settle(list)
        assertTrue("the jump target is visible", height(list, 3) > 0)
    }

    /**
     * The decoration skips a section that reaches none of the laid-out rows. This is the case that
     * skip must not catch, and the fixture leaves it as the only thing deciding the outcome: the
     * section is open, so its box is due, and only its header is off screen, above the first row.
     */
    @Test
    fun `an open section whose header scrolled off the top still draws its box`() {
        val body = (1..30).joinToString("\n\n") { "Body paragraph $it." }
        val (list, _, decoration) = reader("<details open>\n<summary>Section</summary>\n\n$body\n\n</details>", 800)
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(15, 0)
        settle(list, 800)
        assertNull("the header is still laid out", list.findViewHolderForAdapterPosition(0))
        assertTrue("no body row is laid out", list.findViewHolderForAdapterPosition(15) != null)

        val boxes = mutableListOf<RectF>()
        val canvas = object : Canvas() {
            override fun drawRoundRect(rect: RectF, rx: Float, ry: Float, paint: Paint) {
                boxes += RectF(rect)
            }
        }
        decoration.onDraw(canvas, list, RecyclerView.State())
        assertTrue("the open section drew no box over its body", boxes.isNotEmpty())
    }

    /** Back-to-back sections: the block that closes A also opens B, and must show as B's header. */
    @Test
    fun `back-to-back sections each show their own header`() {
        val (list, _) = reader(
            "<details>\n<summary>A</summary>\n\nBody A\n\n" +
                "</details>\n<details>\n<summary>B</summary>\n\nBody B\n\n</details>",
        )
        assertTrue("A's header shows", height(list, 0) > 0)
        assertEquals("A's body is closed", 0, height(list, 1))
        assertTrue("B's header shows", height(list, 2) > 0)
        assertEquals("B's header shows its summary, not its tags", "B", text(list, 2).trim())
        assertEquals("B's body is closed", 0, height(list, 3))
    }

    /**
     * `</details>` with a text line straight under it: the section ends there and the text shows. The
     * bug hid the text and everything after it, because the section never ended.
     */
    @Test
    fun `text straight after a close tag shows, and so does everything after it`() {
        // 0 para · 1 header · 2 body · 3 </details>+text · 4 para
        val (list, _) = reader(
            "Before.\n\n<details>\n<summary>Section</summary>\n\nHidden body.\n\n</details>\nAfter text.\n\nTail.",
        )
        assertTrue("the header shows", height(list, 1) > 0)
        assertEquals("the body is closed", 0, height(list, 2))
        assertTrue("the text under the close tag shows", height(list, 3) > 0)
        assertTrue("it is the text, not blank", text(list, 3).contains("After text."))
        assertTrue("the paragraph after it shows", height(list, 4) > 0)
    }

    /**
     * A link reference definition sits in the parsed tree but not in the adapter: the section must be
     * indexed by adapter positions. The bug hid the paragraph after the section and showed its body.
     */
    @Test
    fun `a link reference definition before a section does not shift which blocks it hides`() {
        // 0 header · 1 body · 2 </details> · 3 para   (the definition is not an adapter item)
        val (list, _) = reader(
            "[r]: https://example.test\n\n<details>\n<summary>Section</summary>\n\nHidden body.\n\n" +
                "</details>\n\nAfter.",
        )
        assertTrue("the header shows", height(list, 0) > 0)
        assertEquals("the body is closed", 0, height(list, 1))
        assertTrue("the paragraph after the section shows", height(list, 3) > 0)
    }
}
