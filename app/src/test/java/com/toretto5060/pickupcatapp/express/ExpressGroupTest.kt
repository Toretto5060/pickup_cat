package com.toretto5060.pickupcatapp.express

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冻结批次分组：已取件卡片在「全部取出那一刻」定格，之后同地址的新信息另起一批，
 * 不会把旧卡拉回待取件；已取件按全部取出时间倒序（多码取组内最晚的 picked_at）。
 */
class ExpressGroupTest {

    private fun row(
        id: Long,
        batch: Int,
        picked: Int,
        pickedAt: Long,
        receivedAt: Long = 1_000L + id,
        address: String = "妈妈驿站",
    ) = ExpressRow(
        id = id,
        code = "C$id",
        company = "中通快递",
        source = "短信",
        address = address,
        cabinet = "6号柜",
        origin = ExpressConst.ORIGIN_SMS,
        originPkg = "",
        receivedAt = receivedAt,
        raw = "",
        smsHash = "",
        picked = picked,
        pickedAt = pickedAt,
        batch = batch,
    )

    @Test
    fun frozenBatchStaysInPickedTabWhenNewCodeArrives() {
        val rows = listOf(
            row(1, batch = 0, picked = 1, pickedAt = 900L),
            row(2, batch = 0, picked = 1, pickedAt = 910L),
            row(3, batch = 1, picked = 0, pickedAt = 0L, receivedAt = 2_000L),
        )
        val pending = groupRowsForTab(rows, pickedTab = false)
        assertEquals(1, pending.size)
        assertEquals(1, pending[0].batch)
        assertEquals(listOf(3L), pending[0].rows.map { it.id })

        val done = groupRowsForTab(rows, pickedTab = true)
        assertEquals(1, done.size)
        assertEquals(0, done[0].batch)
        assertEquals(listOf(1L, 2L), done[0].rows.map { it.id })
    }

    @Test
    fun twoFrozenBatchesAreTwoSeparateCards() {
        val rows = listOf(
            row(1, batch = 0, picked = 1, pickedAt = 900L),
            row(2, batch = 0, picked = 1, pickedAt = 910L),
            row(3, batch = 1, picked = 1, pickedAt = 1_500L),
        )
        val done = groupRowsForTab(rows, pickedTab = true)
        assertEquals(2, done.size)
        assertEquals(listOf(1, 0), done.map { it.batch })
        // 多码卡片的排序键是组内最晚的 picked_at（910），不是逐码到站时间。
        assertEquals(910L, done[1].pickedAt)
    }

    @Test
    fun sameBatchStillAggregatesWhilePendingExists() {
        val rows = listOf(
            row(1, batch = 0, picked = 0, pickedAt = 0L),
            row(2, batch = 0, picked = 0, pickedAt = 0L),
            row(3, batch = 0, picked = 1, pickedAt = 800L),
        )
        val pending = groupRowsForTab(rows, pickedTab = false)
        assertEquals(1, pending.size)
        assertEquals(3, pending[0].rows.size)
        assertTrue(groupRowsForTab(rows, pickedTab = true).isEmpty())
    }

    @Test
    fun multiCodeCardUsesLatestPickedAtForOrdering() {
        val rows = listOf(
            row(1, batch = 0, picked = 1, pickedAt = 1_000L),
            row(2, batch = 0, picked = 1, pickedAt = 3_000L),
            row(3, batch = 1, picked = 1, pickedAt = 2_000L, address = "丰巢"),
        )
        val done = groupRowsForTab(rows, pickedTab = true)
        assertEquals(listOf(0, 1), done.map { it.batch })
        assertEquals(3_000L, done[0].pickedAt)
    }

    @Test
    fun missingPickedAtFallsBackToReceivedAt() {
        val rows = listOf(row(1, batch = 0, picked = 1, pickedAt = 0L, receivedAt = 5_000L))
        assertEquals(5_000L, groupRowsForTab(rows, pickedTab = true).single().pickedAt)
    }
}
