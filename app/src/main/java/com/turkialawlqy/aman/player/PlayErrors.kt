package com.turkialawlqy.aman.player

import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.upstream.HttpDataSource

/** سبب واضح لفشل التشغيل (يطلع للأب على الشاشة عشان يعرف وين المشكلة) */
object PlayErrors {
    fun reason(e: PlaybackException): String {
        val http = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val code = http?.responseCode
        val msg = when {
            code == 401 || code == 403 || code == 458 || code == 456 ->
                "سيرفر الاشتراك رفض التشغيل ($code).\nغالباً حد الأجهزة، أو الحساب منتهي، أو شبكة البيت تحجب السيرفر.\nجرّب «اختبر التشغيل من السيرفر» في لوحة التحكم."
            code == 404 -> "الملف مو موجود على سيرفر الاشتراك (404)"
            code == 509 || code == 429 -> "سيرفر الاشتراك مشغول أو وصلت حد الاتصالات ($code) — جرّب بعد شوي"
            code != null -> "سيرفر الاشتراك رد بخطأ ($code)"
            e.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "الجهاز منع الاتصال غير المشفّر (http)"
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "ما قدر يتصل بسيرفر الاشتراك — تأكد من الإنترنت"
            e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "صيغة الملف ما يقدر يقراها المشغّل"
            e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                e.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "صيغة الصورة أو الصوت ما يدعمها هذا الجهاز"
            e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "البث تأخر — يعيد التشغيل"
            else -> null
        }
        return listOfNotNull(msg, e.errorCodeName).joinToString("\n")
    }
}
