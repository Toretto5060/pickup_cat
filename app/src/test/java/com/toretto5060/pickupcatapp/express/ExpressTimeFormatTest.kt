package com.toretto5060.pickupcatapp.express

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 时间显示五档规则（今天 / 昨天 / 前天 / 今年 / 跨年）。用相对日期构造，避免受运行当天影响；
 * 相对日用中英任一文案都算通过（取决于 JVM 默认 Locale）。
 */
class ExpressTimeFormatTest {

    private fun daysAgo(days: Int): Long =
        Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }.timeInMillis

    @Test
    fun todayIsClockOnly() {
        assertTrue(formatReceivedAt(System.currentTimeMillis()).matches(Regex("""\d{2}:\d{2}""")))
    }

    @Test
    fun yesterdayUsesRelativeWordAndWeekday() {
        assertTrue(formatReceivedAt(daysAgo(1)).matches(Regex("""(昨天|Yesterday) \([^)]+\) \d{2}:\d{2}""")))
    }

    @Test
    fun dayBeforeYesterdayUsesRelativeWordAndWeekday() {
        assertTrue(formatReceivedAt(daysAgo(2)).matches(Regex("""(前天|2 days ago) \([^)]+\) \d{2}:\d{2}""")))
    }

    @Test
    fun earlierThisYearShowsMonthDayAndWeekday() {
        val now = Calendar.getInstance()
        if (now.get(Calendar.DAY_OF_YEAR) < 6) return
        val early = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_YEAR, 2)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 20)
        }.timeInMillis
        assertTrue(formatReceivedAt(early).matches(Regex("""\d{2}\.\d{2} \([^)]+\) \d{2}:\d{2}""")))
    }

    @Test
    fun lastYearShowsFullDateWeekdayAndClock() {
        val lastYear = Calendar.getInstance().apply { add(Calendar.YEAR, -1) }.timeInMillis
        assertTrue(formatReceivedAt(lastYear).matches(Regex("""\d{4}\.\d{2}\.\d{2} \([^)]+\) \d{2}:\d{2}""")))
    }
}
