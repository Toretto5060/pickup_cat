package com.toretto5060.pickupcatapp.express

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The five SMS templates from 2026-11-04, parsed by the built-in rule. Asserts the exact code /
 * company / address / cabinet the plan promised - cabinet pulled out of the address and into the code.
 */
class ExpressParseTest {

    @Test
    fun t1Shentong() {
        val p = ExpressParse.parse("【申通快递】凭11-2-3014到城市岸泊门口驿站妈妈取货，地址：城市岸泊333弄")!!
        assertEquals("申通快递", p.company)
        assertEquals("11-2-3014", p.code)
        assertEquals("城市岸泊333弄", p.address)
        assertEquals("", p.cabinet)
        assertEquals("短信", p.source)
    }

    @Test
    fun t2Duoduo() {
        val p = ExpressParse.parse("【多多代收点】凭1019-0165到欧特福超市取尾号5619包裹")!!
        assertEquals("多多代收点", p.company)
        assertEquals("1019-0165", p.code)
        assertEquals("欧特福超市", p.address)
        assertEquals("", p.cabinet)
    }

    @Test
    fun t3Yunda() {
        val p = ExpressParse.parse(
            "【韵达快递】快递员提醒您，请凭取件码72307130到城市岸泊西门链家隔壁妈妈驿站6号柜取您的快递",
        )!!
        assertEquals("韵达快递", p.company)
        assertEquals("72307130", p.code)
        assertEquals("城市岸泊西门链家隔壁妈妈驿站", p.address)
        assertEquals("6号柜", p.cabinet)
    }

    @Test
    fun t4Yuantong() {
        val p = ExpressParse.parse(
            "【圆通速递】取货码27851532，您有圆通包裹已到城市岸泊西门链家隔壁妈妈驿站6号柜",
        )!!
        assertEquals("圆通速递", p.company)
        assertEquals("27851532", p.code)
        assertEquals("城市岸泊西门链家隔壁妈妈驿站", p.address)
        assertEquals("6号柜", p.cabinet)
    }

    @Test
    fun t5Tuxi() {
        val p = ExpressParse.parse(
            "【兔喜生活】您有包裹已到达城市岸泊欧特福店，取件码为7-22-2057，地址:欧特福购物广场(吴杨东路店)",
        )!!
        assertEquals("兔喜生活", p.company)
        assertEquals("7-22-2057", p.code)
        assertEquals("欧特福购物广场(吴杨东路店)", p.address)
        assertEquals("", p.cabinet)
    }

    @Test
    fun groupKeySameForSameAddressCabinet() {
        val a = ExpressParse.parse(
            "【韵达快递】快递员提醒您，请凭取件码72307130到城市岸泊西门链家隔壁妈妈驿站6号柜取您的快递",
        )!!
        val b = ExpressParse.parse(
            "【圆通速递】取货码27851532，您有圆通包裹已到城市岸泊西门链家隔壁妈妈驿站6号柜",
        )!!
        val ga = ExpressGroup(a.address, a.cabinet, listOf(a.code), listOf(a.company), a.source, 0)
        val gb = ExpressGroup(b.address, b.cabinet, listOf(b.code), listOf(b.company), b.source, 0)
        assertEquals(ga.key, gb.key)
        assertEquals("6柜 72307130、27851532", ExpressGroup(
            a.address, a.cabinet,
            listOf(a.code, b.code), listOf(a.company, b.company), a.source, 0,
        ).displayCode)
    }

    @Test
    fun notAPickupIsDropped() {
        assertNull(ExpressParse.parse("您好，您的验证码是 123456，请勿泄露"))
        assertNull(ExpressParse.parse("今晚一起吃饭吗"))
    }

    /** 纠错保存：按下标切分原文生成整体模板，规则优先于内置规则，且能还原四个字段。 */
    @Test
    fun templateRuleRoundTrip() {
        val sample = "【韵达快递】快递员提醒您，请凭取件码72307130到城市岸泊西门链家隔壁妈妈驿站6号柜取您的快递"
        val rule = buildTemplateRule(
            sample,
            code = "72307130",
            company = "韵达快递",
            address = "城市岸泊西门链家隔壁妈妈驿站",
            cabinet = "6号柜",
        )
        val p = ExpressParse.parse(sample, extraRules = listOf(rule))!!
        assertEquals("韵达快递", p.company)
        assertEquals("72307130", p.code)
        assertEquals("城市岸泊西门链家隔壁妈妈驿站", p.address)
        assertEquals("6号柜", p.cabinet)
    }

    /** 纠错后模板应只对同格式文本命中：换一个取件码/地址仍能按同样位置提取。 */
    @Test
    fun templateRuleGeneralizes() {
        val sample = "【韵达快递】快递员提醒您，请凭取件码72307130到城市岸泊西门链家隔壁妈妈驿站6号柜取您的快递"
        val rule = buildTemplateRule(
            sample,
            code = "72307130",
            company = "韵达快递",
            address = "城市岸泊西门链家隔壁妈妈驿站",
            cabinet = "6号柜",
        )
        val next = "【韵达快递】快递员提醒您，请凭取件码99114488到城市岸泊南门菜鸟驿站3号柜取您的快递"
        val p = ExpressParse.parse(next, extraRules = listOf(rule))!!
        assertEquals("99114488", p.code)
        assertEquals("城市岸泊南门菜鸟驿站", p.address)
        assertEquals("3号柜", p.cabinet)
    }
}
