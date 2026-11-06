package com.toretto5060.pickupcatapp.express

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小部件「高度 → 可见条数 → 行高」的纯函数换算：
 * 3 格显示约 3 条、2 格约 2 条，最多 3 条，剩余条目在 ListView 里上下滑动查看。
 *
 * 注意：ListView 不再人为锁死可视高度（HyperOS 报告高度与实际可见区不符会导致最后一行被永久裁切），
 * 改由 match_parent + 动态行高保证「大致 N 条且不裁按钮」，因此这里只校验行高换算，不再测 listHeight 锁定。
 */
class ExpressWidgetSizingTest {

    @Test
    fun cellHeightMapsToRowCount() {
        assertEquals(1, ExpressWidgetSizing.visibleRows(80f))
        assertEquals(2, ExpressWidgetSizing.visibleRows(160f))
        assertEquals(3, ExpressWidgetSizing.visibleRows(240f))
        // 小米桌面格子 dp 与估算值略有出入时仍落到正确档位
        assertEquals(2, ExpressWidgetSizing.visibleRows(190f))
        assertEquals(3, ExpressWidgetSizing.visibleRows(250f))
    }

    @Test
    fun neverShowsMoreThanThreeRowsEvenIfHostIgnoresMaxResizeHeight() {
        assertEquals(3, ExpressWidgetSizing.visibleRows(280f))
        assertEquals(3, ExpressWidgetSizing.visibleRows(400f))
    }

    @Test
    fun rowHeightStaysWithinBoundsAndFillsMostOfTheCell() {
        for (height in listOf(240f, 280f, 360f, 400f)) {
            val cells = ExpressWidgetSizing.visibleRows(height)
            val row = ExpressWidgetSizing.rowHeightFor(height, cells)
            val usable = height - ExpressWidgetSizing.CHROME_DP
            assertTrue("height=$height cells=$cells row=$row 应 >= 下限", row >= 46f)
            assertTrue("height=$height cells=$cells row=$row 应 <= 上限", row <= 120f)
            // 行高应足够大，让 N 行铺满绝大多数可用高度（减少留白）；不锁 ListView 后即使行高
            // 略大于 usable/cells 也只是首屏少露、滚动可见，不会裁掉按钮。
            assertTrue(
                "height=$height cells=$cells row=$row：N 行应铺满至少 80% 可用高度",
                row * cells >= usable * 0.8f,
            )
        }
    }

    @Test
    fun twoCellRowHeightStaysCompactNotStretchedByThreeCellValue() {
        // 2 格必须回到紧凑行高（约 47~57dp），不能被 3 格的 88dp 撑大，否则 2 格只显 1 条。
        val row = ExpressWidgetSizing.rowHeightFor(160f, 2)
        assertTrue("2 格行高应紧凑(<=60)，实际 $row", row <= 60f)
        assertTrue("2 格行高应 >= 下限 46", row >= 46f)
        // 3 格行高应略高于内容高度（约 53dp），保证内容居中不贴底
        val row3 = ExpressWidgetSizing.rowHeightFor(240f, 3)
        assertTrue("3 格行高应舒展(>=55)，实际 $row3", row3 >= 55f)
    }
}
