package com.animerift

import org.json.JSONArray
import org.json.JSONObject

/**
 * اختيار المصادر التي ستُفتح لكل حلقة.
 *
 * ## لماذا هذه الطبقة منفصلة؟
 * البوابة تعيد ~24 مصدراً لحلقة واحدة، وفتح كل منها يحتاج طلبين
 * (`can_play` + `direct_link`) أي ~48 طلباً دفعة واحدة — وهو ما يتجاوز سقف
 * البوابة (429) ويؤدي في النهاية إلى حظر الـ IP (403 ACCESS_DENIED).
 *
 * لذلك نختار المصادر بـ**ميزانية طلبات** محدودة، مع تفضيل المصادر التي
 * تعطي أكثر جودات بأقل عدد طلبات.
 *
 * كل المنطق هنا خالص (بلا Android) ليُختبر مباشرة.
 */
object SourceSelector {

    /** أقصى عدد طلبات بوابة مسموح به لكل حلقة. */
    const val REQUEST_BUDGET = 20

    /** أقصى عدد مصادر غير عربية تُفتح. */
    const val MAX_FOREIGN_SOURCES = 2

    /**
     * تكلفة فتح مصدر: طلب `can_play` واحد + طلب `direct_link` لكل جودة.
     */
    fun cost(item: JSONObject): Int {
        val qualities = item.optJSONArray("qualities")
        val count = if (qualities == null) 1 else qualities.length().coerceAtLeast(1)
        return 1 + count
    }

    private fun isArabic(item: JSONObject): Boolean =
        item.optString("sub_title").startsWith("ar_")

    /**
     * يختار المصادر المطلوبة بالترتيب:
     *  1. العربية (مترجم/مدبلج) — الأغنى بجودات أولاً.
     *  2. غير العربية — بعدد محدود جداً.
     */
    fun select(items: JSONArray): List<JSONObject> {
        val ordered = ArrayList<JSONObject>(items.length())
        for (pass in 0..1) {
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val arabic = isArabic(item)
                if ((pass == 0 && arabic) || (pass == 1 && !arabic)) ordered.add(item)
            }
        }

        val selected = ArrayList<JSONObject>(ordered.size)
        var budget = REQUEST_BUDGET

        // مجموعات (خادم + نوع مسار) — كل مجموعة مصادرها بنفس اللغة.
        val groups = LinkedHashMap<String, ArrayList<JSONObject>>()
        for (item in ordered) {
            if (!isArabic(item)) continue
            val key = "${item.optString("provider")}|${item.optString("sub_title")}"
            groups.getOrPut(key) { ArrayList() }.add(item)
        }
        // المجموعات الأغنى بجودات أولاً (مصدر واحد = عدة جودات).
        val sortedGroups = groups.entries.sortedByDescending { entry ->
            entry.value.sumOf { cost(it) - 1 }
        }
        // توزيع دوري: مصدر واحد من كل مجموعة قبل تكرار أي مجموعة، حتى لا
        // تستهلك مجموعة واحدة (مثل streamtape) الميزانية كاملة.
        var round = 0
        var progressed = true
        while (progressed) {
            progressed = false
            for (group in sortedGroups) {
                val item = group.value.getOrNull(round) ?: continue
                val c = cost(item)
                if (c > budget) continue
                budget -= c
                selected.add(item)
                progressed = true
            }
            round++
        }

        var foreignCount = 0
        for (item in ordered) {
            if (isArabic(item)) continue
            if (foreignCount >= MAX_FOREIGN_SOURCES) break
            val c = cost(item)
            if (c > budget) break
            budget -= c
            foreignCount++
            selected.add(item)
        }

        return selected
    }
}
