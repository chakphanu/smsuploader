package th.`in`.jane.smsreader

data class Stat(
        var Success: Int = 0,
        var Fail: Int = 0
){
    fun incrSuccess(){
        Success++
    }

    fun incrFail(){
        Fail++
    }
}