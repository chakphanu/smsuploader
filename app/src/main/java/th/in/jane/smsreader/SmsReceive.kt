package th.`in`.jane.smsreader

data class SmsReceive(
        var type: String? = "",
        var body: String?="",
        var originatingAddress: String?="",
        var timestampMillis: Long?=0
)